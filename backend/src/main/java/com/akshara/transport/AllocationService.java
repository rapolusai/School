package com.akshara.transport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.transport.TransportForms.AllocationForm;
import com.akshara.transport.TransportForms.AllocationUpdateForm;
import com.akshara.transport.TransportForms.BulkAllocationForm;
import com.akshara.transport.TransportForms.EndAllocationForm;
import com.akshara.transport.TransportPeople.StudentInfo;
import com.akshara.transport.TransportViews.AllocationBrief;
import com.akshara.transport.TransportViews.AllocationView;
import com.akshara.transport.TransportViews.BulkResult;
import com.akshara.transport.TransportViews.RouteRef;
import com.akshara.transport.TransportViews.Skipped;
import com.akshara.transport.TransportViews.StopRef;
import com.akshara.transport.TransportViews.StudentOption;
import com.akshara.transport.TransportViews.StudentRef;
import com.akshara.transport.TransportViews.ZoneRef;

/**
 * Students on routes: allocating one student or a class to a route and stop, changing the stop, and ending an
 * allocation when a student leaves transport. A student has at most one active allocation. A full bus (as many active
 * allocations as the vehicle has seats) refuses more students unless a reason is given, which is kept and audited.
 * Every change re-prices the student's bus fee in their dues.
 */
@Service
@Transactional
public class AllocationService {

    /** The problem type of a 409 when the bus is full; the reason goes in {@code overCapacityReason}. */
    public static final String ROUTE_FULL_TYPE = "urn:akshara:problem:route-full";
    /** The problem type of a 409 when the student already has an active allocation. */
    public static final String ALREADY_ALLOCATED_TYPE = "urn:akshara:problem:already-allocated";

    private final AllocationRepository allocations;
    private final RouteRepository routes;
    private final StopRepository stops;
    private final VehicleRepository vehicles;
    private final FeeZoneRepository zones;
    private final TransportPeople people;
    private final TransportCharges charges;
    private final AuditService audit;

    AllocationService(AllocationRepository allocations, RouteRepository routes, StopRepository stops,
            VehicleRepository vehicles, FeeZoneRepository zones, TransportPeople people, TransportCharges charges,
            AuditService audit) {
        this.allocations = allocations;
        this.routes = routes;
        this.stops = stops;
        this.vehicles = vehicles;
        this.zones = zones;
        this.people = people;
        this.charges = charges;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    /** Filters for the allocation list; null means any. {@code status} ALL shows ended ones too (ACTIVE default). */
    public record AllocationQuery(UUID routeId, UUID stopId, UUID classId, String status, String search) {
    }

    @Transactional(readOnly = true)
    public List<AllocationView> list(AllocationQuery q) {
        TenantContext.require();
        String status = q.status() == null ? "ACTIVE" : q.status();
        List<Allocation> list = allocations.newestFirst().stream()
                .filter(a -> q.routeId() == null || q.routeId().equals(a.getRouteId()))
                .filter(a -> q.stopId() == null || q.stopId().equals(a.getStopId()))
                .filter(a -> "ALL".equals(status) || a.getStatus().name().equals(status))
                .toList();
        if (q.classId() != null) {
            Set<UUID> inClass = people.classStudents(q.classId(), null).stream().map(StudentRow::id)
                    .collect(Collectors.toSet());
            list = list.stream().filter(a -> inClass.contains(a.getStudentId())).toList();
        }
        List<AllocationView> views = views(list);
        if (q.search() != null && !q.search().isBlank()) {
            String needle = q.search().trim().toLowerCase(Locale.ROOT);
            views = views.stream().filter(v -> v.student().name() != null
                    && (v.student().name().toLowerCase(Locale.ROOT).contains(needle)
                            || v.student().admissionNo().toLowerCase(Locale.ROOT).contains(needle)))
                    .toList();
        }
        return views;
    }

    @Transactional(readOnly = true)
    public AllocationView allocation(UUID id) {
        TenantContext.require();
        return views(List.of(allocations.findById(id).orElseThrow(() -> ApiException.notFound("Allocation"))))
                .getFirst();
    }

    /** Active students for the allocation picker, with the bus they are on now (if any). */
    @Transactional(readOnly = true)
    public List<StudentOption> studentOptions(String query, UUID classId, UUID sectionId) {
        TenantContext.require();
        List<StudentRow> rows = people.search(query, classId, sectionId, 50);
        Map<UUID, Allocation> active = allocations.activeOfStudents(rows.stream().map(StudentRow::id).toList())
                .stream().collect(Collectors.toMap(Allocation::getStudentId, Function.identity()));
        Map<UUID, Route> routeById = routes.findAll().stream()
                .collect(Collectors.toMap(Route::getId, Function.identity()));
        return rows.stream().map(r -> {
            Allocation a = active.get(r.id());
            Route route = a == null ? null : routeById.get(a.getRouteId());
            return new StudentOption(r.id(), r.fullName(), r.admissionNo(),
                    TransportPeople.label(r.className(), r.sectionName()),
                    a == null ? null : new AllocationBrief(a.getId(), a.getRouteId(),
                            route == null ? null : route.getName(), a.getStopName(), a.getStartDate(),
                            a.getEndDate()));
        }).toList();
    }

    List<AllocationView> views(Collection<Allocation> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        LocalDate today = TransportRules.today();
        Map<UUID, StudentInfo> students = people.students(list.stream().map(Allocation::getStudentId)
                .collect(Collectors.toSet()));
        Map<UUID, Route> routeById = routes.findAll().stream()
                .collect(Collectors.toMap(Route::getId, Function.identity()));
        Map<UUID, Stop> stopById = stops.findAll().stream().collect(Collectors.toMap(Stop::getId,
                Function.identity()));
        Map<UUID, FeeZone> zoneById = zones.findAll().stream()
                .collect(Collectors.toMap(FeeZone::getId, Function.identity()));
        List<AllocationView> result = new ArrayList<>();
        for (Allocation a : list) {
            StudentInfo s = students.get(a.getStudentId());
            Route r = routeById.get(a.getRouteId());
            Stop st = a.getStopId() == null ? null : stopById.get(a.getStopId());
            FeeZone z = st == null || st.getFeeZoneId() == null ? null : zoneById.get(st.getFeeZoneId());
            result.add(new AllocationView(a.getId(),
                    s == null ? new StudentRef(a.getStudentId(), null, null, null) : s.ref(),
                    new RouteRef(a.getRouteId(), r == null ? null : r.getName()),
                    new StopRef(a.getStopId(), a.getStopName()),
                    z == null ? null : new ZoneRef(z.getId(), z.getName(), z.getAnnualFeePaise()),
                    a.getStartDate(), a.getEndDate(), a.getStatus(), a.period().covers(today),
                    a.getOverCapacityReason(), a.getEndReason(), a.getCreatedByName(), a.getCreatedAt(),
                    a.getEndedByName(), a.getEndedAt()));
        }
        return result;
    }

    // ------------------------------------------------------------------ allocating

    public AllocationView allocate(AllocationForm form, Actor actor) {
        TenantContext.require();
        StudentInfo student = people.student(form.studentId())
                .orElseThrow(() -> ApiException.badRequest("Pick a student of this school.", "studentId"));
        if (!student.active()) {
            throw ApiException.badRequest(student.name() + " is not an active student.", "studentId");
        }
        Route route = activeRoute(form.routeId());
        Stop stop = stopOf(route, form.stopId(), "stopId");
        checkDates(form.startDate(), form.endDate());
        String reason = TransportSetupService.blankToNull(form.overCapacityReason());
        Optional<Allocation> ended = makeRoom(student, form.startDate(), actor);
        boolean over = checkCapacity(route, 1, form.startDate(), reason);
        Allocation a = allocations.saveAndFlush(new Allocation(student.id(), route.getId(), stop, form.startDate(),
                form.endDate(), over ? reason : null, actor));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("student", student.name());
        details.put("studentId", student.id());
        details.put("route", route.getName());
        details.put("stop", stop.getName());
        details.put("startDate", form.startDate());
        details.put("endDate", form.endDate());
        details.put("overCapacity", over);
        if (over) {
            details.put("overCapacityReason", reason);
        }
        ended.ifPresent(e -> details.put("endedAllocationId", e.getId()));
        audit.record(actor, "transport_allocation.created", "transport_allocation", a.getId(), details);
        charges.refresh(List.of(student.id()));
        return views(List.of(a)).getFirst();
    }

    /**
     * Allocates the active students of a class (or one section, or the chosen ones of it) to the route and stop.
     * Students already on a bus during the period are skipped. The capacity check covers all of them at once.
     */
    public BulkResult allocateClass(BulkAllocationForm form, Actor actor) {
        TenantContext.require();
        Route route = activeRoute(form.routeId());
        Stop stop = stopOf(route, form.stopId(), "stopId");
        checkDates(form.startDate(), form.endDate());
        List<StudentRow> classStudents = people.classStudents(form.classId(), form.sectionId());
        Set<UUID> chosen = form.studentIds() == null ? null : new HashSet<>(form.studentIds());
        if (chosen != null) {
            Set<UUID> inClass = classStudents.stream().map(StudentRow::id).collect(Collectors.toSet());
            if (!inClass.containsAll(chosen)) {
                throw ApiException.badRequest("Pick active students of this class.", "studentIds");
            }
        }
        List<StudentRow> wanted = classStudents.stream().filter(r -> chosen == null || chosen.contains(r.id()))
                .toList();
        Map<UUID, Allocation> active = allocations.activeOfStudents(wanted.stream().map(StudentRow::id).toList())
                .stream().collect(Collectors.toMap(Allocation::getStudentId, Function.identity()));
        Map<UUID, Route> routeById = routes.findAll().stream()
                .collect(Collectors.toMap(Route::getId, Function.identity()));
        List<Skipped> skipped = new ArrayList<>();
        List<StudentRow> toAllocate = new ArrayList<>();
        TransportRules.Period period = new TransportRules.Period(form.startDate(), form.endDate());
        for (StudentRow r : wanted) {
            Allocation a = active.get(r.id());
            if (a != null && a.period().overlaps(period)) {
                Route on = routeById.get(a.getRouteId());
                skipped.add(new Skipped(r.id(), r.fullName(), "Already on " + (on == null ? "a route" : on.getName())
                        + " (" + a.getStopName() + ")"));
            } else {
                toAllocate.add(r);
            }
        }
        if (toAllocate.isEmpty()) {
            return new BulkResult(0, skipped);
        }
        String reason = TransportSetupService.blankToNull(form.overCapacityReason());
        boolean over = checkCapacity(route, toAllocate.size(), form.startDate(), reason);
        // Earlier allocations that end before this one starts are closed first (one active allocation per student).
        for (StudentRow r : toAllocate) {
            Allocation previous = active.get(r.id());
            if (previous != null) {
                previous.end(previous.getEndDate(), "Replaced by a new allocation", actor);
            }
        }
        allocations.flush();
        List<UUID> ids = new ArrayList<>();
        for (StudentRow r : toAllocate) {
            allocations.save(new Allocation(r.id(), route.getId(), stop, form.startDate(), form.endDate(),
                    over ? reason : null, actor));
            ids.add(r.id());
        }
        allocations.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("route", route.getName());
        details.put("stop", stop.getName());
        details.put("classId", form.classId());
        details.put("sectionId", form.sectionId());
        details.put("startDate", form.startDate());
        details.put("endDate", form.endDate());
        details.put("allocated", ids.size());
        details.put("skipped", skipped.size());
        details.put("overCapacity", over);
        if (over) {
            details.put("overCapacityReason", reason);
        }
        audit.record(actor, "transport_allocations.bulk_created", "transport_route", route.getId(), details);
        charges.refresh(ids);
        return new BulkResult(ids.size(), skipped);
    }

    /** Moves the student to another stop of the same route, or sets a planned end date. */
    public AllocationView update(UUID id, AllocationUpdateForm form, Actor actor) {
        TenantContext.require();
        Allocation a = allocations.lockById(id).orElseThrow(() -> ApiException.notFound("Allocation"));
        if (!a.isActive()) {
            throw ApiException.conflict("This allocation has ended.", "id");
        }
        Route route = routes.findById(a.getRouteId()).orElseThrow();
        Stop stop = stopOf(route, form.stopId(), "stopId");
        checkDates(a.getStartDate(), form.endDate());
        String before = a.getStopName();
        LocalDate beforeEnd = a.getEndDate();
        a.change(stop, form.endDate());
        allocations.saveAndFlush(a);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentId", a.getStudentId());
        details.put("route", route.getName());
        details.put("fromStop", before);
        details.put("toStop", stop.getName());
        details.put("fromEndDate", beforeEnd);
        details.put("endDate", form.endDate());
        audit.record(actor, "transport_allocation.updated", "transport_allocation", a.getId(), details);
        charges.refresh(List.of(a.getStudentId()));
        return views(List.of(a)).getFirst();
    }

    /** The student leaves transport: the allocation ends on the given day (their last day on the bus). */
    public AllocationView end(UUID id, EndAllocationForm form, Actor actor) {
        TenantContext.require();
        Allocation a = allocations.lockById(id).orElseThrow(() -> ApiException.notFound("Allocation"));
        if (!a.isActive()) {
            throw ApiException.conflict("This allocation has already ended.", "id");
        }
        if (form.endDate().isBefore(a.getStartDate())) {
            throw ApiException.badRequest("The last day cannot be before the first day on the bus ("
                    + a.getStartDate() + ").", "endDate");
        }
        String reason = TransportSetupService.blankToNull(form.reason());
        a.end(form.endDate(), reason, actor);
        allocations.saveAndFlush(a);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentId", a.getStudentId());
        details.put("endDate", form.endDate());
        details.put("reason", reason);
        audit.record(actor, "transport_allocation.ended", "transport_allocation", a.getId(), details);
        charges.refresh(List.of(a.getStudentId()));
        return views(List.of(a)).getFirst();
    }

    // ------------------------------------------------------------------ checks

    private Route activeRoute(UUID routeId) {
        Route route = routes.findById(routeId)
                .orElseThrow(() -> ApiException.badRequest("Pick a route of this school.", "routeId"));
        if (!route.isActive()) {
            throw ApiException.badRequest("This route is not running.", "routeId");
        }
        return route;
    }

    private Stop stopOf(Route route, UUID stopId, String field) {
        return stops.findById(stopId).filter(s -> s.getRouteId().equals(route.getId()))
                .orElseThrow(() -> ApiException.badRequest("Pick a stop of this route.", field));
    }

    private static void checkDates(LocalDate start, LocalDate end) {
        if (end != null && end.isBefore(start)) {
            throw ApiException.badRequest("The last day cannot be before the first day.", "endDate");
        }
    }

    /**
     * A student has at most one active allocation. One that ends before the new one starts is closed now; any other
     * is a 409.
     */
    private Optional<Allocation> makeRoom(StudentInfo student, LocalDate start, Actor actor) {
        Optional<Allocation> current = allocations.activeOf(student.id());
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Allocation a = current.get();
        if (a.getEndDate() != null && a.getEndDate().isBefore(start)) {
            a.end(a.getEndDate(), "Replaced by a new allocation", actor);
            allocations.saveAndFlush(a);
            return Optional.of(a);
        }
        String routeName = routes.findById(a.getRouteId()).map(Route::getName).orElse("a route");
        throw new ApiException(HttpStatus.CONFLICT, "Already on a bus", student.name() + " already travels on "
                + routeName + " (" + a.getStopName() + "). End or change that allocation first.",
                Map.of("studentId", "Already on " + routeName + "."), ALREADY_ALLOCATED_TYPE);
    }

    /**
     * Refuses more students than the vehicle has seats (409 route-full) unless a reason is given. Returns whether the
     * allocation goes over capacity.
     */
    private boolean checkCapacity(Route route, int adding, LocalDate start, String reason) {
        if (route.getVehicleId() == null) {
            return false;
        }
        Vehicle v = vehicles.findById(route.getVehicleId()).orElseThrow();
        long taken = allocations.seatsTaken(route.getId(), start);
        if (!TransportRules.overCapacity(taken, adding, v.getSeats())) {
            return false;
        }
        if (reason == null) {
            String detail = "The bus on " + route.getName() + " is full: " + taken + " of " + v.getSeats()
                    + " seats are taken" + (adding > 1 ? " and " + adding + " more students would not fit" : "")
                    + ". Give a reason to allocate anyway.";
            throw new ApiException(HttpStatus.CONFLICT, "The bus is full", detail,
                    Map.of("overCapacityReason", "Give a reason to allocate beyond the " + v.getSeats() + " seats."),
                    ROUTE_FULL_TYPE);
        }
        return true;
    }
}
