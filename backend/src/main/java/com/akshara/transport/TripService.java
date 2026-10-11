package com.akshara.transport;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.transport.TransportForms.DelayForm;
import com.akshara.transport.TransportForms.PositionForm;
import com.akshara.transport.TransportPeople.StudentInfo;
import com.akshara.transport.TransportViews.DeviceAck;
import com.akshara.transport.TransportViews.LiveBoard;
import com.akshara.transport.TransportViews.LiveRoute;
import com.akshara.transport.TransportViews.LiveTrip;
import com.akshara.transport.TransportViews.MyRoute;
import com.akshara.transport.TransportViews.MyTrips;
import com.akshara.transport.TransportViews.RouteRef;
import com.akshara.transport.TransportViews.StopRef;
import com.akshara.transport.TransportViews.TripBrief;
import com.akshara.transport.TransportViews.TripStop;
import com.akshara.transport.TransportViews.TripStudent;
import com.akshara.transport.TransportViews.TripView;
import com.akshara.transport.TripStates.TripState;

/**
 * Running a trip from the attendant's phone: start it, share the bus's position, mark each student boarded, dropped
 * or absent, report a delay, and end it. The driver and attendant of a route run its trips; users with
 * transport.manage may run any route's trip. Anyone else gets a 404 for the route or trip. Positions also come from
 * the vehicle's GPS device ({@link #devicePosition}).
 *
 * <p>Marks, delays, starting and ending are audited and kept in the boarding log; positions are kept in the trip log
 * (not the audit log, as they arrive every 30 seconds).
 */
@Service
@Transactional
public class TripService {

    /** A position older than this is refused: phones send what they have now. */
    static final Duration MAX_POSITION_AGE = Duration.ofHours(1);

    private final TripRepository trips;
    private final TripEventRepository events;
    private final PositionRepository positions;
    private final RouteRepository routes;
    private final StopRepository stops;
    private final VehicleRepository vehicles;
    private final CrewMemberRepository crew;
    private final TripStates states;
    private final TransportAlerts alerts;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;

    TripService(TripRepository trips, TripEventRepository events, PositionRepository positions,
            RouteRepository routes, StopRepository stops, VehicleRepository vehicles, CrewMemberRepository crew,
            TripStates states, TransportAlerts alerts, AuditService audit, ApplicationEventPublisher publisher) {
        this.trips = trips;
        this.events = events;
        this.positions = positions;
        this.routes = routes;
        this.stops = stops;
        this.vehicles = vehicles;
        this.crew = crew;
        this.states = states;
        this.alerts = alerts;
        this.audit = audit;
        this.publisher = publisher;
    }

    // ------------------------------------------------------------------ access

    /** The crew member behind the sign-in, if any. */
    private Optional<CrewMember> crewOf(UUID userId) {
        return crew.findByUserId(userId);
    }

    private static String roleOn(Route r, CrewMember m) {
        if (m == null) {
            return null;
        }
        if (m.getId().equals(r.getDriverId())) {
            return "DRIVER";
        }
        if (m.getId().equals(r.getAttendantId())) {
            return "ATTENDANT";
        }
        return null;
    }

    /** The route if the user may run its trips; 404 otherwise, so other routes stay invisible. */
    private Route runnableRoute(UUID routeId, UUID userId, boolean manager) {
        Route route = routes.findById(routeId).orElseThrow(() -> ApiException.notFound("Route"));
        if (!manager && roleOn(route, crewOf(userId).orElse(null)) == null) {
            throw ApiException.notFound("Route");
        }
        return route;
    }

    private Trip runnableTrip(UUID tripId, UUID userId, boolean manager, boolean lock) {
        Trip trip = (lock ? trips.lockById(tripId) : trips.findById(tripId))
                .orElseThrow(() -> ApiException.notFound("Trip"));
        Route route = routes.findById(trip.getRouteId()).orElseThrow();
        if (!manager && roleOn(route, crewOf(userId).orElse(null)) == null) {
            throw ApiException.notFound("Trip");
        }
        return trip;
    }

    private static void requireRunning(Trip trip) {
        if (!trip.isRunning()) {
            throw ApiException.conflict("This trip has ended.", "status");
        }
    }

    // ------------------------------------------------------------------ the attendant's day

    /** The routes the user may run today with today's trips: their own as crew, or every active route for managers. */
    @Transactional(readOnly = true)
    public MyTrips myTrips(UUID userId, boolean manager) {
        TenantContext.require();
        LocalDate today = TransportRules.today();
        CrewMember me = crewOf(userId).orElse(null);
        List<Route> mine;
        if (manager) {
            mine = routes.ordered().stream().filter(Route::isActive).toList();
        } else if (me != null) {
            mine = routes.activeWithCrew(List.of(me.getId()));
        } else {
            mine = List.of();
        }
        if (mine.isEmpty()) {
            return new MyTrips(today, List.of());
        }
        Map<UUID, Vehicle> vehicleById = vehicles.findAll().stream()
                .collect(Collectors.toMap(Vehicle::getId, Function.identity()));
        Map<String, Trip> todays = new HashMap<>();
        for (Trip t : trips.onDayForRoutes(today, mine.stream().map(Route::getId).toList())) {
            todays.put(t.getRouteId() + "/" + t.getKind(), t);
        }
        List<MyRoute> result = new ArrayList<>();
        for (Route r : mine) {
            String role = roleOn(r, me);
            Vehicle v = r.getVehicleId() == null ? null : vehicleById.get(r.getVehicleId());
            result.add(new MyRoute(r.getId(), r.getName(), v == null ? null : v.getRegistrationNo(),
                    role == null ? "MANAGER" : role, r.isRunsMorning(), r.isRunsAfternoon(),
                    brief(todays.get(r.getId() + "/" + TripKind.MORNING)),
                    brief(todays.get(r.getId() + "/" + TripKind.AFTERNOON)),
                    states.riders(r.getId(), today).size()));
        }
        return new MyTrips(today, result);
    }

    private static TripBrief brief(Trip t) {
        return t == null ? null : new TripBrief(t.getId(), t.getStatus(), t.getStartedAt(), t.getEndedAt());
    }

    /** Today's trip of a route, if started (for the phone screen to pick up where it was). */
    @Transactional(readOnly = true)
    public Optional<TripView> today(UUID routeId, TripKind kind, UUID userId, boolean manager) {
        TenantContext.require();
        Route route = runnableRoute(routeId, userId, manager);
        return trips.findByRouteIdAndTripDateAndKind(route.getId(), TransportRules.today(), kind)
                .map(t -> view(states.load(t, route), Instant.now()));
    }

    public TripView start(UUID routeId, TripKind kind, Actor actor, boolean manager) {
        TenantContext.require();
        Route route = runnableRoute(routeId, actor.id(), manager);
        if (!route.isActive()) {
            throw ApiException.conflict("This route is not running.", "routeId");
        }
        if (!route.runs(kind)) {
            throw ApiException.badRequest("This route has no " + kind.name().toLowerCase(Locale.ROOT)
                    + " trip.", "kind");
        }
        LocalDate today = TransportRules.today();
        if (trips.findByRouteIdAndTripDateAndKind(routeId, today, kind).isPresent()) {
            throw ApiException.conflict("Today's " + kind.name().toLowerCase(Locale.ROOT)
                    + " trip of " + route.getName() + " has already been started.", "kind");
        }
        Instant now = Instant.now();
        Trip trip = trips.saveAndFlush(new Trip(routeId, today, kind, route.getVehicleId(), actor, now));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("route", route.getName());
        details.put("kind", kind);
        details.put("date", today);
        audit.record(actor, "transport_trip.started", "transport_trip", trip.getId(), details);
        return view(states.load(trip, route), now);
    }

    @Transactional(readOnly = true)
    public TripView trip(UUID tripId, UUID userId, boolean manager) {
        TenantContext.require();
        Trip trip = runnableTrip(tripId, userId, manager, false);
        return view(states.load(trip, routes.findById(trip.getRouteId()).orElseThrow()), Instant.now());
    }

    /**
     * Marks a student on the trip. Morning trips take BOARDED (at the student's stop) or ABSENT; afternoon trips also
     * take DROPPED (at the student's stop). Marking the same again changes nothing; a different mark replaces it and
     * cancels the earlier message if it has not gone out. A student who is not on the trip is a 404.
     */
    public TripView mark(UUID tripId, UUID studentId, BoardingStatus status, Actor actor, boolean manager) {
        TenantContext.require();
        Trip trip = runnableTrip(tripId, actor.id(), manager, true);
        requireRunning(trip);
        if (trip.getKind() == TripKind.MORNING && status == BoardingStatus.DROPPED) {
            throw ApiException.badRequest("On the morning trip, mark students boarded or absent.", "status");
        }
        Route route = routes.findById(trip.getRouteId()).orElseThrow();
        TripState state = states.load(trip, route);
        Allocation rider = state.riders().get(studentId);
        if (rider == null) {
            throw ApiException.notFound("Student");
        }
        BoardingStatus previous = state.mark(studentId);
        Instant now = Instant.now();
        if (previous == status) {
            return view(state, now);
        }
        StudentInfo student = state.students().get(studentId);
        int stopIndex = state.indexOf(rider.getStopId());
        Stop stop = stopIndex >= 0 ? state.stops().get(stopIndex) : null;
        // Afternoon boarding happens at school, not at the student's stop.
        Stop where = trip.getKind() == TripKind.AFTERNOON && status == BoardingStatus.BOARDED ? null : stop;
        TripEvent event = events.saveAndFlush(TripEvent.mark(trip.getId(), studentId, status, where, actor, now));
        if (previous != null) {
            alerts.correct(trip.getId(), studentId, previous, status);
        }
        if (status == BoardingStatus.BOARDED) {
            alerts.boarded(trip.getId(), route.getName(), student, where == null ? null : where.getName(), now);
        } else if (status == BoardingStatus.DROPPED) {
            alerts.dropped(trip.getId(), route.getName(), student, stop == null ? "" : stop.getName(), now);
        }
        // A pickup (morning) or a drop (afternoon) at a stop means the bus is there.
        boolean atStop = (trip.getKind() == TripKind.MORNING && status == BoardingStatus.BOARDED)
                || (trip.getKind() == TripKind.AFTERNOON && status == BoardingStatus.DROPPED);
        TripState after = states.load(trip, route);
        if (atStop && stopIndex > state.reached()) {
            after = advance(trip, route, after, stopIndex, now);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("route", route.getName());
        details.put("kind", trip.getKind());
        details.put("student", student == null ? null : student.name());
        details.put("studentId", studentId);
        details.put("status", status);
        details.put("previous", previous);
        details.put("stop", where == null ? null : where.getName());
        audit.record(actor, "transport_trip.student_marked", "transport_trip", trip.getId(), details);
        publisher.publishEvent(new TransportBoardingRecorded(TenantContext.require(), trip.getId(), route.getId(),
                trip.getKind(), studentId, where == null ? null : where.getId(), status, event.getRecordedAt()));
        return view(after, now);
    }

    /** A position from the attendant's phone. */
    public TripView position(UUID tripId, PositionForm form, Actor actor, boolean manager) {
        TenantContext.require();
        Trip trip = runnableTrip(tripId, actor.id(), manager, true);
        requireRunning(trip);
        Instant now = Instant.now();
        Instant at = recordedAt(form.recordedAt(), now);
        Route route = routes.findById(trip.getRouteId()).orElseThrow();
        TripState state = record(trip, route, form, at, now, PositionSource.PHONE);
        return view(state, now);
    }

    /**
     * A position from the vehicle's GPS device, already authenticated by its token and working as its school. Kept as
     * the vehicle's latest position; when the vehicle is on a running trip today, also in the trip log, moving the
     * trip on like a phone's position.
     */
    DeviceAck devicePosition(UUID vehicleId, PositionForm form) {
        TenantContext.require();
        Instant now = Instant.now();
        Instant at = recordedAt(form.recordedAt(), now);
        vehicles.recordPosition(vehicleId, form.latitude(), form.longitude(), at);
        List<UUID> running = trips.runningOfVehicle(vehicleId, TransportRules.today());
        if (running.isEmpty()) {
            return new DeviceAck(true, false);
        }
        Trip trip = trips.lockById(running.getFirst()).orElseThrow();
        Route route = routes.findById(trip.getRouteId()).orElseThrow();
        record(trip, route, form, at, now, PositionSource.DEVICE);
        return new DeviceAck(true, true);
    }

    static Instant recordedAt(Instant given, Instant now) {
        if (given == null || given.isAfter(now)) {
            return now;
        }
        if (Duration.between(given, now).compareTo(MAX_POSITION_AGE) > 0) {
            throw ApiException.badRequest("This position is more than an hour old.", "recordedAt");
        }
        return given;
    }

    private TripState record(Trip trip, Route route, PositionForm form, Instant at, Instant now,
            PositionSource source) {
        positions.save(new Position(trip.getId(), trip.getVehicleId(), at, now, form.latitude(), form.longitude(),
                form.accuracyM(), form.speedKmh(), source));
        trip.position(form.latitude(), form.longitude(), form.accuracyM(), at, source);
        trips.saveAndFlush(trip);
        if (source == PositionSource.PHONE && trip.getVehicleId() != null) {
            vehicles.recordPosition(trip.getVehicleId(), form.latitude(), form.longitude(), at);
        }
        TripState state = states.load(trip, route);
        int reached = RouteMath.advance(state.reached(), state.stops().stream().map(Stop::point).toList(),
                form.latitude(), form.longitude());
        if (reached > state.reached()) {
            state = advance(trip, route, state, reached, now);
        }
        return state;
    }

    /**
     * The bus reached the stop at {@code index} (trip order): keeps it as the furthest stop reached and tells the
     * families at the next stop that the bus is approaching.
     */
    private TripState advance(Trip trip, Route route, TripState state, int index, Instant now) {
        int before = state.reached();
        trip.reach(state.stops().get(index).getId());
        trips.saveAndFlush(trip);
        OptionalInt approaching = RouteMath.approaching(before, index, state.stops().size());
        if (approaching.isPresent()) {
            Stop next = state.stops().get(approaching.getAsInt());
            List<StudentInfo> waiting = state.studentsAt(next.getId()).stream()
                    .filter(s -> waitingFor(trip.getKind(), state.mark(s.id())))
                    .toList();
            alerts.approaching(trip.getId(), route.getName(), waiting, next.getName(), now);
        }
        return states.load(trip, route);
    }

    /**
     * Whether a family still waits for the bus at their stop: in the morning until the student boarded or was marked
     * absent; in the afternoon until they were dropped (or absent).
     */
    private static boolean waitingFor(TripKind kind, BoardingStatus mark) {
        if (mark == BoardingStatus.ABSENT) {
            return false;
        }
        return kind == TripKind.MORNING ? mark == null : mark != BoardingStatus.DROPPED;
    }

    public TripView delay(UUID tripId, DelayForm form, Actor actor, boolean manager) {
        TenantContext.require();
        Trip trip = runnableTrip(tripId, actor.id(), manager, true);
        requireRunning(trip);
        Instant now = Instant.now();
        String reason = form.reason().trim();
        trip.delay(form.minutes(), reason, now);
        trips.saveAndFlush(trip);
        TripEvent event = events.saveAndFlush(TripEvent.delay(trip.getId(), form.minutes(), reason, actor, now));
        Route route = routes.findById(trip.getRouteId()).orElseThrow();
        TripState state = states.load(trip, route);
        // Only families still waiting for the bus: not yet picked up (morning) or not yet dropped (afternoon).
        List<StudentInfo> waiting = state.riders().keySet().stream()
                .filter(id -> waitingFor(trip.getKind(), state.mark(id)))
                .map(id -> state.students().get(id))
                .filter(s -> s != null)
                .sorted(Comparator.comparing(StudentInfo::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int queued = alerts.delayed(event.getId(), route.getName(), waiting, form.minutes(), reason, now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("route", route.getName());
        details.put("kind", trip.getKind());
        details.put("minutes", form.minutes());
        details.put("reason", reason);
        details.put("messagesQueued", queued);
        audit.record(actor, "transport_trip.delayed", "transport_trip", trip.getId(), details);
        publisher.publishEvent(new TransportTripDelayed(TenantContext.require(), trip.getId(), route.getId(),
                trip.getKind(), form.minutes(), reason, now));
        return view(state, now);
    }

    public TripView end(UUID tripId, Actor actor, boolean manager) {
        TenantContext.require();
        Trip trip = runnableTrip(tripId, actor.id(), manager, true);
        requireRunning(trip);
        Instant now = Instant.now();
        trip.end(actor, now);
        trips.saveAndFlush(trip);
        Route route = routes.findById(trip.getRouteId()).orElseThrow();
        TripState state = states.load(trip, route);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("route", route.getName());
        details.put("kind", trip.getKind());
        details.put("boarded", state.counts().boarded());
        details.put("dropped", state.counts().dropped());
        details.put("absent", state.counts().absent());
        details.put("positions", positions.countByTripId(trip.getId()));
        audit.record(actor, "transport_trip.ended", "transport_trip", trip.getId(), details);
        return view(state, now);
    }

    // ------------------------------------------------------------------ views

    TripView view(TripState s, Instant now) {
        Trip trip = s.trip();
        Optional<Stop> next = s.next();
        List<TripStop> stopViews = new ArrayList<>();
        for (int i = 0; i < s.stops().size(); i++) {
            Stop st = s.stops().get(i);
            List<TripStudent> students = s.studentsAt(st.getId()).stream()
                    .map(info -> {
                        TripEvent e = s.marks().get(info.id());
                        return new TripStudent(info.id(), info.name(), info.admissionNo(), info.classLabel(),
                                e == null ? null : e.getKind().boarding(), e == null ? null : e.getRecordedAt());
                    })
                    .toList();
            stopViews.add(new TripStop(st.getId(), st.getSeq(), st.getName(), st.getLandmark(),
                    st.timeFor(trip.getKind()), i <= s.reached(),
                    trip.isRunning() && next.isPresent() && next.get().getId().equals(st.getId()), students));
        }
        String vehicleNo = trip.getVehicleId() == null ? null
                : vehicles.findById(trip.getVehicleId()).map(Vehicle::getRegistrationNo).orElse(null);
        return new TripView(trip.getId(), new RouteRef(s.route().getId(), s.route().getName()), trip.getTripDate(),
                trip.getKind(), trip.getStatus(), vehicleNo, trip.getStartedAt(), trip.getStartedByName(),
                trip.getEndedAt(), trip.getEndedByName(), s.delay(), s.position(now),
                s.reachedStop().map(TripService::ref).orElse(null),
                trip.isRunning() ? next.map(TripService::ref).orElse(null) : null, s.counts(), stopViews);
    }

    static StopRef ref(Stop s) {
        return new StopRef(s.getId(), s.getName());
    }

    /** Today's trips of every active route, for the transport manager's live board. */
    @Transactional(readOnly = true)
    public LiveBoard live() {
        TenantContext.require();
        LocalDate today = TransportRules.today();
        Instant now = Instant.now();
        List<Route> active = routes.ordered().stream().filter(Route::isActive).toList();
        Map<UUID, Vehicle> vehicleById = vehicles.findAll().stream()
                .collect(Collectors.toMap(Vehicle::getId, Function.identity()));
        Map<UUID, List<Trip>> tripsByRoute = trips.findByTripDate(today).stream()
                .collect(Collectors.groupingBy(Trip::getRouteId));
        Map<UUID, List<Stop>> stopsByRoute = stops.ordered().stream().collect(Collectors.groupingBy(Stop::getRouteId));
        List<LiveRoute> result = new ArrayList<>();
        for (Route r : active) {
            Vehicle v = r.getVehicleId() == null ? null : vehicleById.get(r.getVehicleId());
            List<LiveTrip> live = new ArrayList<>();
            List<StopRef> stopRefs = stopsByRoute.getOrDefault(r.getId(), List.of()).stream().map(TripService::ref)
                    .toList();
            for (Trip t : tripsByRoute.getOrDefault(r.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(Trip::getKind)).toList()) {
                TripState s = states.load(t, r);
                live.add(new LiveTrip(t.getId(), t.getKind(), t.getStatus(), t.getStartedAt(), t.getEndedAt(),
                        s.position(now), s.reachedStop().map(TripService::ref).orElse(null),
                        t.isRunning() ? s.next().map(TripService::ref).orElse(null) : null, s.counts(), s.delay()));
            }
            result.add(new LiveRoute(new RouteRef(r.getId(), r.getName()), v == null ? null : v.getRegistrationNo(),
                    stopRefs, live));
        }
        return new LiveBoard(today, result);
    }
}
