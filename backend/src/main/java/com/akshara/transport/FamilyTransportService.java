package com.akshara.transport;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService.ChildView;
import com.akshara.transport.TransportViews.ChildBus;
import com.akshara.transport.TransportViews.FamilyAllocation;
import com.akshara.transport.TransportViews.FamilyEvent;
import com.akshara.transport.TransportViews.FamilyPosition;
import com.akshara.transport.TransportViews.FamilyStop;
import com.akshara.transport.TransportViews.FamilyTrip;
import com.akshara.transport.TransportViews.MyTransport;
import com.akshara.transport.TransportViews.PositionView;
import com.akshara.transport.TripStates.TripState;

/**
 * The bus as a family sees it: for a parent, each child's route and stop with pickup and drop times, today's trips
 * (started or not, where the bus is and how many stops away), and today's entries for the child in the boarding log;
 * for a student, their own. Nobody else's children are reachable from here, and the bus's coordinates are not shown,
 * only the stop it is nearest.
 */
@Service
@Transactional(readOnly = true)
public class FamilyTransportService {

    private final AllocationRepository allocations;
    private final RouteRepository routes;
    private final StopRepository stops;
    private final VehicleRepository vehicles;
    private final TripRepository trips;
    private final TripStates states;
    private final TransportPeople people;

    FamilyTransportService(AllocationRepository allocations, RouteRepository routes, StopRepository stops,
            VehicleRepository vehicles, TripRepository trips, TripStates states, TransportPeople people) {
        this.allocations = allocations;
        this.routes = routes;
        this.stops = stops;
        this.vehicles = vehicles;
        this.trips = trips;
        this.states = states;
        this.people = people;
    }

    public MyTransport mine(UUID userId) {
        TenantContext.require();
        LocalDate today = TransportRules.today();
        Instant now = Instant.now();
        List<ChildView> family = people.familyOf(userId);
        if (family.isEmpty()) {
            return new MyTransport(today, List.of());
        }
        Map<UUID, List<Allocation>> byStudent = allocations.ofStudents(family.stream().map(ChildView::id).toList())
                .stream().collect(Collectors.groupingBy(Allocation::getStudentId));
        List<ChildBus> result = new ArrayList<>();
        for (ChildView child : family) {
            String classLabel = TransportPeople.label(child.className(), child.sectionName());
            List<Allocation> list = byStudent.getOrDefault(child.id(), List.of());
            // Today's allocation, or else the next one to start (so a family sees the bus before the first day).
            Optional<Allocation> current = TransportRules.covering(list, Allocation::period, today)
                    .or(() -> list.stream().filter(a -> a.isActive() && a.getStartDate().isAfter(today))
                            .min((a, b) -> a.getStartDate().compareTo(b.getStartDate())));
            if (current.isEmpty() || current.get().getStopId() == null) {
                result.add(new ChildBus(child.id(), child.fullName(), classLabel, null, List.of()));
                continue;
            }
            Allocation a = current.get();
            Route route = routes.findById(a.getRouteId()).orElseThrow();
            Stop stop = stops.findById(a.getStopId()).orElseThrow();
            Vehicle vehicle = route.getVehicleId() == null ? null : vehicles.findById(route.getVehicleId())
                    .orElse(null);
            FamilyAllocation allocation = new FamilyAllocation(route.getName(), stop.getName(), stop.getLandmark(),
                    stop.getPickupTime(), stop.getDropTime(), a.getStartDate(), a.getEndDate(),
                    vehicle == null ? null : vehicle.getRegistrationNo(),
                    vehicle == null ? null : vehicle.getVehicleType());
            List<FamilyTrip> todays = new ArrayList<>();
            if (a.period().covers(today)) {
                for (TripKind kind : TripKind.values()) {
                    if (!route.runs(kind)) {
                        continue;
                    }
                    Optional<Trip> trip = trips.findByRouteIdAndTripDateAndKind(route.getId(), today, kind);
                    todays.add(trip.isPresent() ? trip(states.load(trip.get(), route), child.id(), stop, now)
                            : notStarted(route, kind, stop));
                }
            }
            result.add(new ChildBus(child.id(), child.fullName(), classLabel, allocation, todays));
        }
        return new MyTransport(today, result);
    }

    private FamilyTrip notStarted(Route route, TripKind kind, Stop mine) {
        List<Stop> ordered = RouteMath.tripOrder(stops.findByRouteIdOrderBySeq(route.getId()), kind);
        List<FamilyStop> stopViews = ordered.stream()
                .map(s -> new FamilyStop(s.getId(), s.getSeq(), s.getName(), s.timeFor(kind), false, false,
                        s.getId().equals(mine.getId())))
                .toList();
        return new FamilyTrip(kind, "NOT_STARTED", null, null, null, null, null, null, null, null, stopViews,
                List.of());
    }

    private FamilyTrip trip(TripState s, UUID studentId, Stop mine, Instant now) {
        Trip trip = s.trip();
        Optional<Stop> next = trip.isRunning() ? s.next() : Optional.empty();
        List<FamilyStop> stopViews = new ArrayList<>();
        for (int i = 0; i < s.stops().size(); i++) {
            Stop st = s.stops().get(i);
            stopViews.add(new FamilyStop(st.getId(), st.getSeq(), st.getName(), st.timeFor(trip.getKind()),
                    i <= s.reached(), next.isPresent() && next.get().getId().equals(st.getId()),
                    st.getId().equals(mine.getId())));
        }
        int myIndex = s.indexOf(mine.getId());
        // Stops still to reach up to and including the child's: 1 when the bus is at the stop before, 0 once there.
        Integer stopsAway = myIndex < 0 || !trip.isRunning() ? null : Math.max(0, myIndex - s.reached());
        PositionView p = s.position(now);
        FamilyPosition position = p == null ? null
                : new FamilyPosition(p.at(), p.minutesAgo(), p.stale(), p.nearestStop());
        List<FamilyEvent> log = new ArrayList<>();
        for (TripEvent e : s.events()) {
            if (studentId.equals(e.getStudentId()) || e.getKind() == TripEventKind.DELAYED) {
                log.add(new FamilyEvent(e.getKind(), e.getRecordedAt(), e.getStopName(), e.getMinutes(),
                        e.getNote()));
            }
        }
        TripEvent mark = s.marks().get(studentId);
        return new FamilyTrip(trip.getKind(), trip.getStatus().name(), trip.getStartedAt(), trip.getEndedAt(),
                mark == null ? null : mark.getKind().boarding(), mark == null ? null : mark.getRecordedAt(), position,
                next.map(TripService::ref).orElse(null), stopsAway, s.delay(), stopViews, log);
    }
}
