package com.akshara.transport;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.fees.FeeHeadKind;
import com.akshara.fees.FeeInsights;
import com.akshara.fees.FeeInsights.KindDue;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffService.StaffRow;
import com.akshara.students.StudentRoster.GuardianContact;
import com.akshara.transport.TransportPeople.StudentInfo;
import com.akshara.transport.TransportViews.BoardingReport;
import com.akshara.transport.TransportViews.BoardingRow;
import com.akshara.transport.TransportViews.DocumentRow;
import com.akshara.transport.TransportViews.DocumentsReport;
import com.akshara.transport.TransportViews.FeesReport;
import com.akshara.transport.TransportViews.RosterRoute;
import com.akshara.transport.TransportViews.RosterRow;
import com.akshara.transport.TransportViews.RosterStop;
import com.akshara.transport.TransportViews.RouteFees;
import com.akshara.transport.TransportViews.RouteStudentsReport;

/**
 * The transport reports for the reports hub, as JSON and CSV: students by route and stop (printable), vehicle
 * documents and driving licences expiring, transport fee collection by route (from the fees module's dues), and the
 * day's boarding log. Phone numbers are masked.
 */
@Service
@Transactional(readOnly = true)
public class TransportReportService {

    /** The furthest ahead the documents report looks. */
    static final int MAX_WITHIN_DAYS = 365;

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(TransportRules.INDIA);

    private final RouteRepository routes;
    private final StopRepository stops;
    private final VehicleRepository vehicles;
    private final CrewMemberRepository crew;
    private final AllocationRepository allocations;
    private final TripRepository trips;
    private final TripEventRepository events;
    private final TripStates states;
    private final TransportPeople people;
    private final AcademicsDirectory academics;
    private final FeeInsights fees;

    TransportReportService(RouteRepository routes, StopRepository stops, VehicleRepository vehicles,
            CrewMemberRepository crew, AllocationRepository allocations, TripRepository trips,
            TripEventRepository events, TripStates states, TransportPeople people, AcademicsDirectory academics,
            FeeInsights fees) {
        this.routes = routes;
        this.stops = stops;
        this.vehicles = vehicles;
        this.crew = crew;
        this.allocations = allocations;
        this.trips = trips;
        this.events = events;
        this.states = states;
        this.people = people;
        this.academics = academics;
        this.fees = fees;
    }

    // ------------------------------------------------------------------ students by route

    /** The students travelling on the day (today by default) on every active route, or one route, stop by stop. */
    public RouteStudentsReport routeStudents(UUID routeId, LocalDate date) {
        TenantContext.require();
        LocalDate day = date == null ? TransportRules.today() : date;
        List<Route> list = routes.ordered().stream()
                .filter(r -> routeId == null ? r.isActive() : r.getId().equals(routeId)).toList();
        Map<UUID, Vehicle> vehicleById = vehicles.findAll().stream()
                .collect(Collectors.toMap(Vehicle::getId, Function.identity()));
        Map<UUID, CrewMember> crewById = crew.findAll().stream()
                .collect(Collectors.toMap(CrewMember::getId, Function.identity()));
        Map<UUID, StaffRow> staff = crewById.isEmpty() ? Map.of() : people.staff();
        Map<UUID, List<Stop>> stopsByRoute = stops.ordered().stream().collect(Collectors.groupingBy(Stop::getRouteId));
        Map<UUID, Map<UUID, Allocation>> ridersByRoute = new LinkedHashMap<>();
        Set<UUID> studentIds = new HashSet<>();
        for (Route r : list) {
            Map<UUID, Allocation> riders = states.riders(r.getId(), day);
            ridersByRoute.put(r.getId(), riders);
            studentIds.addAll(riders.keySet());
        }
        Map<UUID, StudentInfo> students = people.students(studentIds);
        Map<UUID, GuardianContact> guardians = people.guardians(studentIds);
        List<RosterRoute> result = new ArrayList<>();
        int total = 0;
        for (Route r : list) {
            Map<UUID, Allocation> riders = ridersByRoute.get(r.getId());
            List<RosterStop> stopRows = new ArrayList<>();
            int count = 0;
            for (Stop st : stopsByRoute.getOrDefault(r.getId(), List.of())) {
                List<RosterRow> rows = riders.values().stream().filter(a -> st.getId().equals(a.getStopId()))
                        .map(a -> {
                            StudentInfo s = students.get(a.getStudentId());
                            GuardianContact g = guardians.get(a.getStudentId());
                            return new RosterRow(a.getStudentId(), s == null ? null : s.name(),
                                    s == null ? null : s.admissionNo(), s == null ? null : s.classLabel(),
                                    g == null ? null : g.name(), g == null ? null : TransportPeople.maskPhone(g.phone()),
                                    a.getStartDate(), a.getEndDate());
                        })
                        .filter(row -> row.name() != null)
                        .sorted(Comparator.comparing(RosterRow::name, String.CASE_INSENSITIVE_ORDER))
                        .toList();
                count += rows.size();
                stopRows.add(new RosterStop(st.getId(), st.getSeq(), st.getName(), st.getPickupTime(),
                        st.getDropTime(), rows));
            }
            total += count;
            Vehicle v = r.getVehicleId() == null ? null : vehicleById.get(r.getVehicleId());
            result.add(new RosterRoute(r.getId(), r.getName(), v == null ? null : v.getRegistrationNo(),
                    v == null ? null : v.getSeats(), crewName(r.getDriverId(), crewById, staff),
                    crewName(r.getAttendantId(), crewById, staff), count, stopRows));
        }
        return new RouteStudentsReport(day, total, result);
    }

    public TransportCsv routeStudentsCsv(UUID routeId, LocalDate date) {
        RouteStudentsReport report = routeStudents(routeId, date);
        TransportCsv csv = new TransportCsv("Route", "Vehicle", "Stop no.", "Stop", "Pickup", "Drop", "Student",
                "Admission no.", "Class", "Parent or guardian", "Phone");
        for (RosterRoute r : report.routes()) {
            for (RosterStop st : r.stops()) {
                for (RosterRow row : st.students()) {
                    csv.row(r.name(), r.vehicleRegistrationNo(), st.seq(), st.name(), st.pickupTime(),
                            st.dropTime(), row.name(), row.admissionNo(), row.classLabel(), row.guardianName(),
                            row.guardianPhone());
                }
            }
        }
        return csv;
    }

    private static String crewName(UUID crewId, Map<UUID, CrewMember> crewById, Map<UUID, StaffRow> staff) {
        CrewMember m = crewId == null ? null : crewById.get(crewId);
        StaffRow row = m == null ? null : staff.get(m.getUserId());
        return row == null ? null : row.name();
    }

    // ------------------------------------------------------------------ documents

    /**
     * Vehicle documents (insurance, fitness, permit, pollution) and drivers' licences that have expired, expire within
     * {@code withinDays} days (30 by default), or have no expiry date on record. Retired vehicles are left out.
     */
    public DocumentsReport documents(Integer withinDays) {
        TenantContext.require();
        int within = withinDays == null ? TransportRules.WARN_DAYS : withinDays;
        if (within < 0 || within > MAX_WITHIN_DAYS) {
            throw ApiException.badRequest("Look between 0 and " + MAX_WITHIN_DAYS + " days ahead.", "withinDays");
        }
        LocalDate today = TransportRules.today();
        List<DocumentRow> rows = new ArrayList<>();
        for (Vehicle v : vehicles.ordered()) {
            if (v.getStatus() == VehicleStatus.RETIRED) {
                continue;
            }
            for (TransportViews.DocumentView d : TransportSetupService.documents(v, today)) {
                if (due(d.expiresOn(), d.daysLeft(), within)) {
                    rows.add(new DocumentRow("VEHICLE", v.getId(), v.getRegistrationNo(), d.kind(), d.expiresOn(),
                            d.state(), d.daysLeft()));
                }
            }
        }
        List<CrewMember> drivers = crew.findAll().stream().filter(m -> m.getKind() == CrewKind.DRIVER).toList();
        Map<UUID, StaffRow> staff = drivers.isEmpty() ? Map.of() : people.staff();
        for (CrewMember m : drivers) {
            StaffRow row = staff.get(m.getUserId());
            if (!TransportPeople.active(row)) {
                continue;
            }
            Long daysLeft = TransportRules.daysLeft(m.getLicenceExpiry(), today);
            if (due(m.getLicenceExpiry(), daysLeft, within)) {
                rows.add(new DocumentRow("DRIVER", m.getId(), row.name(), DocumentKind.LICENCE, m.getLicenceExpiry(),
                        TransportRules.documentState(m.getLicenceExpiry(), today), daysLeft));
            }
        }
        rows.sort(Comparator.comparing((DocumentRow r) -> r.daysLeft() == null ? Long.MAX_VALUE : r.daysLeft())
                .thenComparing(DocumentRow::subject, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DocumentRow::document));
        return new DocumentsReport(today, within, rows);
    }

    private static boolean due(LocalDate expiresOn, Long daysLeft, int within) {
        return expiresOn == null || daysLeft <= within;
    }

    public TransportCsv documentsCsv(Integer withinDays) {
        DocumentsReport report = documents(withinDays);
        TransportCsv csv = new TransportCsv("Type", "Vehicle or driver", "Document", "Expires on", "State",
                "Days left");
        for (DocumentRow r : report.rows()) {
            csv.row(r.subjectType(), r.subject(), r.document().name(), r.expiresOn(), r.state().name(),
                    r.daysLeft());
        }
        return csv;
    }

    // ------------------------------------------------------------------ fee collection

    /**
     * Transport dues of an academic year (the current one by default) by route: what was billed (net of concessions),
     * collected, still outstanding and overdue. A student counts under the route of their latest allocation in the
     * year; students with transport dues and no allocation are shown without a route.
     */
    public FeesReport fees(UUID yearId, UUID routeId) {
        TenantContext.require();
        YearInfo year = (yearId == null ? academics.currentYear() : academics.year(yearId))
                .orElseThrow(() -> yearId == null ? ApiException.conflict("Set the current academic year first.",
                        "yearId") : ApiException.badRequest("Pick an academic year of this school.", "yearId"));
        LocalDate today = TransportRules.today();
        List<KindDue> dues = fees.duesOfKind(year.id(), FeeHeadKind.TRANSPORT);
        Map<UUID, List<Allocation>> byStudent = allocations.overlapping(year.startsOn(), year.endsOn()).stream()
                .collect(Collectors.groupingBy(Allocation::getStudentId));
        Map<UUID, long[]> totals = new LinkedHashMap<>();
        Map<UUID, Set<UUID>> studentsPerRoute = new HashMap<>();
        UUID none = new UUID(0, 0);
        for (KindDue d : dues) {
            UUID route = byStudent.getOrDefault(d.studentId(), List.of()).stream()
                    .max(Comparator.comparing(Allocation::getStartDate)).map(Allocation::getRouteId).orElse(none);
            long[] t = totals.computeIfAbsent(route, k -> new long[4]);
            t[0] += d.netPaise();
            t[1] += d.paidPaise();
            t[2] += Math.max(0, d.balancePaise());
            if (d.dueDate().isBefore(today)) {
                t[3] += Math.max(0, d.balancePaise());
            }
            studentsPerRoute.computeIfAbsent(route, k -> new HashSet<>()).add(d.studentId());
        }
        List<RouteFees> rows = new ArrayList<>();
        for (Route r : routes.ordered()) {
            if (routeId != null && !routeId.equals(r.getId())) {
                continue;
            }
            if (!totals.containsKey(r.getId()) && !r.isActive()) {
                continue;
            }
            rows.add(row(r.getId(), r.getName(), totals.get(r.getId()), studentsPerRoute.get(r.getId())));
        }
        if (routeId == null && totals.containsKey(none)) {
            rows.add(row(null, null, totals.get(none), studentsPerRoute.get(none)));
        }
        long[] sum = new long[4];
        int students = 0;
        for (RouteFees r : rows) {
            sum[0] += r.billedPaise();
            sum[1] += r.collectedPaise();
            sum[2] += r.outstandingPaise();
            sum[3] += r.overduePaise();
            students += r.students();
        }
        return new FeesReport(year.id(), year.name(), today, rows,
                new RouteFees(null, null, students, sum[0], sum[1], sum[2], sum[3]));
    }

    private static RouteFees row(UUID routeId, String name, long[] t, Set<UUID> students) {
        long[] v = t == null ? new long[4] : t;
        return new RouteFees(routeId, name, students == null ? 0 : students.size(), v[0], v[1], v[2], v[3]);
    }

    public TransportCsv feesCsv(UUID yearId, UUID routeId) {
        FeesReport report = fees(yearId, routeId);
        TransportCsv csv = new TransportCsv("Route", "Students", "Billed (Rs)", "Collected (Rs)", "Outstanding (Rs)",
                "Overdue (Rs)");
        for (RouteFees r : report.routes()) {
            csv.row(r.routeName() == null ? "No route" : r.routeName(), r.students(),
                    new TransportCsv.Rupees(r.billedPaise()), new TransportCsv.Rupees(r.collectedPaise()),
                    new TransportCsv.Rupees(r.outstandingPaise()), new TransportCsv.Rupees(r.overduePaise()));
        }
        RouteFees t = report.totals();
        csv.row("Total", t.students(), new TransportCsv.Rupees(t.billedPaise()),
                new TransportCsv.Rupees(t.collectedPaise()), new TransportCsv.Rupees(t.outstandingPaise()),
                new TransportCsv.Rupees(t.overduePaise()));
        return csv;
    }

    // ------------------------------------------------------------------ boarding log

    /** Every mark and delay on the day's trips (today by default), in time order; one route when given. */
    public BoardingReport boarding(LocalDate date, UUID routeId) {
        TenantContext.require();
        LocalDate day = date == null ? TransportRules.today() : date;
        List<Trip> dayTrips = trips.findByTripDate(day).stream()
                .filter(t -> routeId == null || routeId.equals(t.getRouteId())).toList();
        if (dayTrips.isEmpty()) {
            return new BoardingReport(day, 0, 0, 0, 0, List.of());
        }
        Map<UUID, Trip> tripById = dayTrips.stream().collect(Collectors.toMap(Trip::getId, Function.identity()));
        Map<UUID, Route> routeById = routes.findAll().stream()
                .collect(Collectors.toMap(Route::getId, Function.identity()));
        List<TripEvent> log = events.ofTrips(tripById.keySet());
        Map<UUID, StudentInfo> students = people.students(log.stream().map(TripEvent::getStudentId)
                .filter(id -> id != null).collect(Collectors.toSet()));
        List<BoardingRow> rows = new ArrayList<>();
        int boarded = 0;
        int dropped = 0;
        int absent = 0;
        int delays = 0;
        for (TripEvent e : log) {
            Trip t = tripById.get(e.getTripId());
            Route r = routeById.get(t.getRouteId());
            StudentInfo s = e.getStudentId() == null ? null : students.get(e.getStudentId());
            rows.add(new BoardingRow(e.getRecordedAt(), t.getRouteId(), r == null ? null : r.getName(), t.getKind(),
                    e.getKind(), e.getStudentId(), s == null ? null : s.name(), s == null ? null : s.admissionNo(),
                    s == null ? null : s.classLabel(), e.getStopName(), e.getMinutes(), e.getNote(), e.getByName()));
            switch (e.getKind()) {
                case BOARDED -> boarded++;
                case DROPPED -> dropped++;
                case ABSENT -> absent++;
                case DELAYED -> delays++;
                default -> {
                }
            }
        }
        return new BoardingReport(day, boarded, dropped, absent, delays, rows);
    }

    public TransportCsv boardingCsv(LocalDate date, UUID routeId) {
        BoardingReport report = boarding(date, routeId);
        TransportCsv csv = new TransportCsv("Time", "Route", "Trip", "Entry", "Student", "Admission no.", "Class",
                "Stop", "Delay (minutes)", "Note", "Recorded by");
        for (BoardingRow r : report.rows()) {
            csv.row(time(r.at()), r.routeName(), r.tripKind().name(), r.kind().name(), r.studentName(),
                    r.admissionNo(), r.classLabel(), r.stopName(), r.minutes(), r.note(), r.byName());
        }
        return csv;
    }

    private static String time(Instant at) {
        return LOG_TIME.format(at);
    }
}
