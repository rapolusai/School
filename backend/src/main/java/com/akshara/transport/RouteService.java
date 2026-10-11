package com.akshara.transport;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.transport.TransportForms.RouteForm;
import com.akshara.transport.TransportForms.StopForm;
import com.akshara.transport.TransportForms.StopsForm;
import com.akshara.transport.TransportViews.CrewRef;
import com.akshara.transport.TransportViews.RouteDetail;
import com.akshara.transport.TransportViews.RouteSummary;
import com.akshara.transport.TransportViews.StopView;
import com.akshara.transport.TransportViews.VehicleRef;
import com.akshara.transport.TransportViews.ZoneRef;

/** Routes and their stops: the vehicle, driver and attendant, the trips each runs, and stops in pickup order. */
@Service
@Transactional
public class RouteService {

    private final RouteRepository routes;
    private final StopRepository stops;
    private final VehicleRepository vehicles;
    private final CrewMemberRepository crew;
    private final FeeZoneRepository zones;
    private final AllocationRepository allocations;
    private final TransportSetupService setup;
    private final TransportCharges charges;
    private final AuditService audit;

    RouteService(RouteRepository routes, StopRepository stops, VehicleRepository vehicles, CrewMemberRepository crew,
            FeeZoneRepository zones, AllocationRepository allocations, TransportSetupService setup,
            TransportCharges charges, AuditService audit) {
        this.routes = routes;
        this.stops = stops;
        this.vehicles = vehicles;
        this.crew = crew;
        this.zones = zones;
        this.allocations = allocations;
        this.setup = setup;
        this.charges = charges;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    /** What the route views need about every route at once. */
    private record Lookup(Map<UUID, Vehicle> vehicleById, Map<UUID, CrewMember> crewById, Map<UUID, String> crewNames,
            Map<UUID, List<Stop>> stopsByRoute, Map<UUID, Long> seatsTaken, LocalDate today) {
    }

    private Lookup lookup() {
        LocalDate today = TransportRules.today();
        Map<UUID, CrewMember> crewById = crew.findAll().stream()
                .collect(Collectors.toMap(CrewMember::getId, Function.identity()));
        Map<UUID, Long> taken = new HashMap<>();
        for (Object[] row : allocations.seatsTakenPerRoute(today)) {
            taken.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return new Lookup(vehicles.findAll().stream().collect(Collectors.toMap(Vehicle::getId, Function.identity())),
                crewById, setup.crewNames(crewById),
                stops.ordered().stream().collect(Collectors.groupingBy(Stop::getRouteId, LinkedHashMap::new,
                        Collectors.toList())),
                taken, today);
    }

    @Transactional(readOnly = true)
    public List<RouteSummary> routes() {
        TenantContext.require();
        Lookup l = lookup();
        return routes.ordered().stream().map(r -> summary(r, l)).toList();
    }

    @Transactional(readOnly = true)
    public RouteDetail route(UUID id) {
        TenantContext.require();
        Route route = routes.findById(id).orElseThrow(() -> ApiException.notFound("Route"));
        return detail(route, lookup());
    }

    private RouteSummary summary(Route r, Lookup l) {
        Vehicle v = r.getVehicleId() == null ? null : l.vehicleById().get(r.getVehicleId());
        CrewMember driver = r.getDriverId() == null ? null : l.crewById().get(r.getDriverId());
        CrewMember attendant = r.getAttendantId() == null ? null : l.crewById().get(r.getAttendantId());
        List<Stop> routeStops = l.stopsByRoute().getOrDefault(r.getId(), List.of());
        long allocated = l.seatsTaken().getOrDefault(r.getId(), 0L);
        List<String> flags = new ArrayList<>();
        if (v == null) {
            flags.add("NO_VEHICLE");
        } else {
            if (v.getStatus() != VehicleStatus.ACTIVE) {
                flags.add("VEHICLE_UNAVAILABLE");
            }
            if (TransportRules.invalid(TransportRules.documentState(v.getFitnessExpiry(), l.today()))) {
                flags.add("FITNESS_INVALID");
            }
            if (allocated > v.getSeats()) {
                flags.add("OVER_CAPACITY");
            }
        }
        if (driver == null) {
            flags.add("NO_DRIVER");
        } else if (TransportSetupService.licenceInvalid(driver, l.today())) {
            flags.add("DRIVER_LICENCE_INVALID");
        }
        if (attendant == null) {
            flags.add("NO_ATTENDANT");
        }
        if (routeStops.isEmpty()) {
            flags.add("NO_STOPS");
        }
        LocalTime firstPickup = routeStops.stream().map(Stop::getPickupTime).filter(Objects::nonNull).min(
                LocalTime::compareTo).orElse(null);
        return new RouteSummary(r.getId(), r.getName(), r.isActive(), r.isRunsMorning(), r.isRunsAfternoon(),
                v == null ? null : vehicleRef(v), crewRef(driver, l.crewNames()), crewRef(attendant, l.crewNames()),
                routeStops.size(), allocated, v == null ? null : v.getSeats(), firstPickup, flags);
    }

    private RouteDetail detail(Route r, Lookup l) {
        RouteSummary s = summary(r, l);
        Map<UUID, Long> perStop = new HashMap<>();
        for (Object[] row : allocations.perStop(r.getId(), l.today())) {
            if (row[0] != null) {
                perStop.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        Map<UUID, FeeZone> zoneById = zones.findAll().stream()
                .collect(Collectors.toMap(FeeZone::getId, Function.identity()));
        List<StopView> stopViews = l.stopsByRoute().getOrDefault(r.getId(), List.of()).stream()
                .map(st -> stopView(st, zoneById, perStop.getOrDefault(st.getId(), 0L)))
                .toList();
        return new RouteDetail(s.id(), s.name(), s.active(), s.runsMorning(), s.runsAfternoon(), s.vehicle(),
                s.driver(), s.attendant(), s.stopCount(), s.allocated(), s.seats(), s.firstPickup(), s.flags(),
                stopViews);
    }

    static StopView stopView(Stop st, Map<UUID, FeeZone> zoneById, long students) {
        FeeZone z = st.getFeeZoneId() == null ? null : zoneById.get(st.getFeeZoneId());
        return new StopView(st.getId(), st.getSeq(), st.getName(), st.getLandmark(), st.getPickupTime(),
                st.getDropTime(), st.getLatitude(), st.getLongitude(),
                z == null ? null : new ZoneRef(z.getId(), z.getName(), z.getAnnualFeePaise()), students);
    }

    static VehicleRef vehicleRef(Vehicle v) {
        return new VehicleRef(v.getId(), v.getRegistrationNo(), v.getVehicleType(), v.getSeats(), v.getStatus());
    }

    private static CrewRef crewRef(CrewMember m, Map<UUID, String> names) {
        return m == null ? null : new CrewRef(m.getId(), m.getUserId(), names.get(m.getId()), m.getKind());
    }

    // ------------------------------------------------------------------ changes

    public RouteDetail create(RouteForm form, Actor actor) {
        TenantContext.require();
        Route.Details details = details(form, null);
        Route route = routes.saveAndFlush(new Route(details));
        audit.record(actor, "transport_route.created", "transport_route", route.getId(), routeDetails(route));
        return detail(route, lookup());
    }

    public RouteDetail update(UUID id, RouteForm form, Actor actor) {
        TenantContext.require();
        Route route = routes.findById(id).orElseThrow(() -> ApiException.notFound("Route"));
        Route.Details details = details(form, id);
        if (!details.active() && route.isActive() && allocations.anyActiveOnRoute(id)) {
            throw ApiException.conflict("Students travel on this route. End or move their allocations before "
                    + "making it inactive.", "active");
        }
        route.update(details);
        routes.saveAndFlush(route);
        audit.record(actor, "transport_route.updated", "transport_route", route.getId(), routeDetails(route));
        return detail(route, lookup());
    }

    private Route.Details details(RouteForm f, UUID id) {
        String name = f.name().trim();
        if (routes.withName(name).stream().anyMatch(r -> !r.getId().equals(id))) {
            throw ApiException.conflict("A route with this name already exists.", "name");
        }
        boolean morning = f.runsMorning() == null || f.runsMorning();
        boolean afternoon = f.runsAfternoon() == null || f.runsAfternoon();
        if (!morning && !afternoon) {
            throw ApiException.badRequest("A route runs a morning trip, an afternoon trip or both.", "runsMorning");
        }
        if (f.vehicleId() != null) {
            Vehicle v = vehicles.findById(f.vehicleId())
                    .orElseThrow(() -> ApiException.badRequest("Pick a vehicle of this school.", "vehicleId"));
            if (v.getStatus() == VehicleStatus.RETIRED) {
                throw ApiException.badRequest("This vehicle is retired.", "vehicleId");
            }
        }
        if (f.driverId() != null) {
            CrewMember d = crew.findById(f.driverId())
                    .orElseThrow(() -> ApiException.badRequest("Pick one of the school's drivers.", "driverId"));
            if (d.getKind() != CrewKind.DRIVER) {
                throw ApiException.badRequest("Pick one of the school's drivers.", "driverId");
            }
        }
        if (f.attendantId() != null) {
            crew.findById(f.attendantId())
                    .orElseThrow(() -> ApiException.badRequest("Pick one of the school's attendants.", "attendantId"));
            if (f.attendantId().equals(f.driverId())) {
                throw ApiException.badRequest("The attendant cannot also be the driver.", "attendantId");
            }
        }
        return new Route.Details(name, f.vehicleId(), f.driverId(), f.attendantId(), morning, afternoon,
                f.active() == null || f.active());
    }

    private static Map<String, Object> routeDetails(Route r) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", r.getName());
        details.put("vehicleId", r.getVehicleId());
        details.put("driverId", r.getDriverId());
        details.put("attendantId", r.getAttendantId());
        details.put("runsMorning", r.isRunsMorning());
        details.put("runsAfternoon", r.isRunsAfternoon());
        details.put("active", r.isActive());
        return details;
    }

    /**
     * Replaces the route's stops with the list, in pickup order. Stops with an id are kept (their students stay);
     * others are added; stops left out are removed, which is refused while students are allocated to them. A changed
     * fee zone re-prices the unpaid bus dues of the stop's students.
     */
    public RouteDetail saveStops(UUID routeId, StopsForm form, Actor actor) {
        TenantContext.require();
        Route route = routes.findById(routeId).orElseThrow(() -> ApiException.notFound("Route"));
        List<Stop> current = stops.findByRouteIdOrderBySeq(routeId);
        Map<UUID, Stop> currentById = current.stream().collect(Collectors.toMap(Stop::getId, Function.identity()));
        Set<UUID> zoneIds = zones.findAll().stream().map(FeeZone::getId).collect(Collectors.toSet());
        Set<UUID> kept = new HashSet<>();
        for (int i = 0; i < form.stops().size(); i++) {
            StopForm s = form.stops().get(i);
            if (s.id() != null && (!currentById.containsKey(s.id()) || !kept.add(s.id()))) {
                throw ApiException.badRequest("This stop is not one of the route's stops.", "stops[" + i + "].id");
            }
            if ((s.latitude() == null) != (s.longitude() == null)) {
                throw ApiException.badRequest("Give both latitude and longitude, or neither.",
                        "stops[" + i + "].latitude");
            }
            if (s.feeZoneId() != null && !zoneIds.contains(s.feeZoneId())) {
                throw ApiException.badRequest("Pick one of the school's fee zones.", "stops[" + i + "].feeZoneId");
            }
        }
        List<Stop> removed = current.stream().filter(st -> !kept.contains(st.getId())).toList();
        if (!removed.isEmpty()) {
            List<Allocation> onRemoved = allocations.activeAtStops(removed.stream().map(Stop::getId).toList());
            if (!onRemoved.isEmpty()) {
                String name = currentById.get(onRemoved.getFirst().getStopId()).getName();
                throw ApiException.conflict("Students are allocated to the stop " + name
                        + ". Move them to another stop before removing it.", "stops");
            }
        }
        Set<UUID> repriceStops = new HashSet<>();
        Map<UUID, String> renamed = new HashMap<>();
        List<Stop> added = new ArrayList<>();
        for (int i = 0; i < form.stops().size(); i++) {
            StopForm s = form.stops().get(i);
            Stop.Details d = new Stop.Details(i + 1, s.name().trim(), TransportSetupService.blankToNull(s.landmark()),
                    s.pickupTime(), s.dropTime(), s.latitude(), s.longitude(), s.feeZoneId());
            if (s.id() == null) {
                added.add(new Stop(routeId, d));
                continue;
            }
            Stop st = currentById.get(s.id());
            if (!Objects.equals(st.getFeeZoneId(), d.feeZoneId())) {
                repriceStops.add(st.getId());
            }
            if (!st.getName().equals(d.name())) {
                renamed.put(st.getId(), d.name());
            }
            st.update(d);
        }
        // Removed stops go first; the unique stop numbers are checked when the transaction commits.
        stops.deleteAll(removed);
        stops.flush();
        stops.saveAll(current.stream().filter(st -> kept.contains(st.getId())).toList());
        stops.saveAll(added);
        stops.flush();
        if (!renamed.isEmpty()) {
            for (Allocation a : allocations.activeAtStops(renamed.keySet())) {
                a.renameStop(renamed.get(a.getStopId()));
            }
            allocations.flush();
        }
        if (!repriceStops.isEmpty()) {
            charges.refresh(allocations.activeAtStops(repriceStops).stream().map(Allocation::getStudentId)
                    .collect(Collectors.toSet()));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", route.getName());
        details.put("stops", form.stops().size());
        details.put("added", added.size());
        details.put("removed", removed.size());
        audit.record(actor, "transport_route.stops_saved", "transport_route", routeId, details);
        return detail(route, lookup());
    }
}
