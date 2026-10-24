package com.akshara.onboarding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService.Actor;
import com.akshara.payroll.PayStaffService;
import com.akshara.payroll.PayrollForms.PayProfileForm;
import com.akshara.payroll.PayrollForms.StructureForm;
import com.akshara.payroll.SalaryComponentService;
import com.akshara.payroll.PayrollViews.ComponentView;
import com.akshara.shared.TenantContext;
import com.akshara.staff.DepartmentService;
import com.akshara.staff.EmploymentType;
import com.akshara.staff.StaffForms.CreateStaff;
import com.akshara.staff.StaffService;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;
import com.akshara.transport.AllocationService;
import com.akshara.transport.BoardingStatus;
import com.akshara.transport.CrewKind;
import com.akshara.transport.RouteService;
import com.akshara.transport.TransportForms.AllocationForm;
import com.akshara.transport.TransportForms.CrewForm;
import com.akshara.transport.TransportForms.FeeZoneForm;
import com.akshara.transport.TransportForms.PositionForm;
import com.akshara.transport.TransportForms.RouteForm;
import com.akshara.transport.TransportForms.StopForm;
import com.akshara.transport.TransportForms.StopsForm;
import com.akshara.transport.TransportForms.VehicleForm;
import com.akshara.transport.TransportRules;
import com.akshara.transport.TransportSetupService;
import com.akshara.transport.TransportViews.CrewView;
import com.akshara.transport.TransportViews.FeeZoneView;
import com.akshara.transport.TransportViews.RouteDetail;
import com.akshara.transport.TransportViews.StopView;
import com.akshara.transport.TransportViews.TripView;
import com.akshara.transport.TransportViews.VehicleView;
import com.akshara.transport.TripKind;
import com.akshara.transport.TripService;
import com.akshara.transport.VehicleStatus;
import com.akshara.transport.VehicleType;

/**
 * The demo school's buses: three fee zones, three vehicles (the mini bus's fitness certificate expires in
 * {@value #FITNESS_DAYS_LEFT} days), a driver and an attendant for each of three routes (members of staff with the Bus
 * attendant role, signing in with the demo password; {@code attendant@demo.akshara.test} runs Arjun Sharma's route),
 * stops around Hyderabad, Arjun and {@value #OTHER_RIDERS} other students on the buses from today (their bus fee goes
 * into their dues), and today's morning trip of Route 1 with Arjun marked boarded.
 */
@Component
class DemoTransportData {

    private static final Logger log = LoggerFactory.getLogger(DemoTransportData.class);

    static final String ATTENDANT_EMAIL = "attendant" + DemoDataSeeder.DEMO_DOMAIN;
    static final String ARJUN = "Arjun Sharma";
    static final int FITNESS_DAYS_LEFT = 12;
    static final int OTHER_RIDERS = 15;
    static final String ROUTE_ONE = "Route 1 - Banjara Hills";

    /** name | email mailbox | kind | employee code | mobile. One driver and one attendant per route, in route order. */
    static final List<String> CREW = List.of(
            "Sanjay Naidu|driver|DRIVER|AKS-401|9848010401",
            "Lalitha Devi|attendant|ATTENDANT|AKS-402|9848010402",
            "Imtiaz Khan|driver2|DRIVER|AKS-403|9848010403",
            "Saritha Goud|attendant2|ATTENDANT|AKS-404|9848010404",
            "Prakash Yadav|driver3|DRIVER|AKS-405|9848010405",
            "Mary Joseph|attendant3|ATTENDANT|AKS-406|9848010406");

    /** route | registration number | stops as "name;landmark;pickup;drop;lat;lng;zone" separated by "|". */
    static final List<String[]> ROUTES = List.of(
            new String[] {ROUTE_ONE, "TS09 UB 4410",
                "Road No. 12;Near Banjara Hills Park;07:05;15:55;17.412600;78.439200;C",
                "Jubilee Hills Check Post;Metro pillar 1601;07:15;15:45;17.430000;78.410500;B",
                "KBR Park;Main gate;07:25;15:35;17.421600;78.424400;B",
                "Panjagutta;Opposite the hospital;07:35;15:25;17.426500;78.451000;A"},
            new String[] {"Route 2 - Kukatpally", "TS09 UB 4411",
                "KPHB Colony;Phase 3 bus stop;06:55;16:05;17.493800;78.399300;C",
                "Kukatpally Y Junction;Metro station;07:10;15:50;17.484700;78.411500;B",
                "Moosapet;Near the temple;07:20;15:40;17.469900;78.425300;B",
                "Erragadda;Rythu bazaar;07:30;15:30;17.457300;78.434700;A"},
            new String[] {"Route 3 - Secunderabad", "TS09 UC 1029",
                "Tarnaka;Railway degree college;07:00;16:00;17.428100;78.536600;C",
                "Paradise Circle;Bus stop;07:15;15:45;17.443500;78.487000;B",
                "Begumpet;Airport road;07:30;15:30;17.444500;78.465100;A"});

    private final TransportSetupService setup;
    private final RouteService routes;
    private final AllocationService allocations;
    private final TripService trips;
    private final StaffService staff;
    private final DepartmentService departments;
    private final StudentService students;
    private final PayStaffService payStaff;
    private final SalaryComponentService components;
    private final TransactionTemplate tx;

    DemoTransportData(TransportSetupService setup, RouteService routes, AllocationService allocations,
            TripService trips, StaffService staff, DepartmentService departments, StudentService students,
            PayStaffService payStaff, SalaryComponentService components,
            PlatformTransactionManager transactionManager) {
        this.setup = setup;
        this.routes = routes;
        this.allocations = allocations;
        this.trips = trips;
        this.staff = staff;
        this.departments = departments;
        this.students = students;
        this.payStaff = payStaff;
        this.components = components;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Returns the number of students on the buses. {@code userIds} maps the demo people's emails to their ids;
     * {@code passwordHash} is the demo password, already hashed, for the crew's sign-ins.
     */
    int seed(UUID tenantId, Map<String, UUID> userIds, UUID adminId, String passwordHash) {
        Actor admin = new Actor(adminId, "Priya Nair");
        Actor accountant = new Actor(userIds.get("accounts" + DemoDataSeeder.DEMO_DOMAIN), "Meena Reddy");
        LocalDate today = TransportRules.today();
        Map<String, UUID> crewUsers;
        List<RouteDetail> made;
        try {
            crewUsers = TenantContext.runAs(tenantId, () -> tx.execute(status -> crewUsers(admin, passwordHash)));
            step(tenantId, "crew pay details", () -> pay(crewUsers, accountant));
            made = TenantContext.runAs(tenantId, () -> tx.execute(status -> buses(admin, crewUsers, today)));
        } catch (RuntimeException e) {
            // The rest of the demo school does not depend on the buses.
            log.info("Skipped the demo buses ({})", e.getMessage());
            return 0;
        }
        if (made == null || made.isEmpty()) {
            return 0;
        }
        List<StudentRow> riders = TenantContext.runAs(tenantId, () -> tx.execute(status -> riders()));
        int allocated = 0;
        for (int i = 0; i < riders.size(); i++) {
            StudentRow r = riders.get(i);
            // Arjun goes to Route 1's second stop; the others are spread over the routes and their stops.
            RouteDetail route = i == 0 ? made.getFirst() : made.get(i % made.size());
            StopView stop = i == 0 ? route.stops().get(1) : route.stops().get((i / made.size()) % route.stops().size());
            boolean done = step(tenantId, "allocating " + r.fullName(), () -> allocations.allocate(
                    new AllocationForm(r.id(), route.id(), stop.id(), today, null, null), admin));
            allocated += done ? 1 : 0;
        }
        UUID attendantId = crewUsers.get(ATTENDANT_EMAIL);
        Actor attendant = new Actor(attendantId, CREW.get(1).split("\\|")[0]);
        step(tenantId, "today's morning trip", () -> morningTrip(made.getFirst(), riders, attendant));
        return allocated;
    }

    private Map<String, UUID> crewUsers(Actor admin, String passwordHash) {
        UUID administration = departments.list().stream().filter(d -> d.name().equals("Administration"))
                .map(d -> d.id()).findFirst().orElse(null);
        Map<String, UUID> ids = new HashMap<>();
        for (String line : CREW) {
            String[] p = line.split("\\|");
            String email = p[1] + DemoDataSeeder.DEMO_DOMAIN;
            boolean driver = p[2].equals("DRIVER");
            ids.put(email, staff.create(new CreateStaff(p[0], email, null, List.of("BUS_ATTENDANT"), p[3],
                    driver ? "Bus driver" : "Bus attendant", administration, EmploymentType.CONTRACT,
                    LocalDate.of(2022, 6, 1), p[4], driver ? "Heavy vehicle licence" : "Class 10", null, null),
                    passwordHash, admin).userId());
        }
        return ids;
    }

    /** Bank details and a salary structure, like the rest of the demo staff. */
    private Object pay(Map<String, UUID> crewUsers, Actor accountant) {
        Map<String, UUID> ids = components.list().stream()
                .collect(Collectors.toMap(ComponentView::code, ComponentView::id, (a, b) -> a));
        if (!ids.containsKey("BASIC")) {
            return null;
        }
        // Last month's payroll is already approved, so their pay starts this month.
        YearMonth month = YearMonth.now(TransportRules.INDIA);
        int n = 9001;
        for (String line : CREW) {
            String[] p = line.split("\\|");
            UUID userId = crewUsers.get(p[1] + DemoDataSeeder.DEMO_DOMAIN);
            String serial = String.valueOf(n++);
            payStaff.saveProfile(userId, new PayProfileForm("00000010" + serial, "AKSH0000001", null,
                    "DEMOP" + serial + "A", "00000000" + serial, "000000" + serial, true, false), accountant);
            long gross = p[2].equals("DRIVER") ? 24_000L : 16_000L;
            payStaff.createStructure(userId, new StructureForm(month.toString(), "Demo salary",
                    DemoPayrollData.lines(ids, gross)), accountant);
        }
        return null;
    }

    private List<RouteDetail> buses(Actor admin, Map<String, UUID> crewUsers, LocalDate today) {
        FeeZoneView a = setup.createZone(new FeeZoneForm("Zone A", new BigDecimal("0.0"), new BigDecimal("5.0"),
                12_000_00L), admin);
        FeeZoneView b = setup.createZone(new FeeZoneForm("Zone B", new BigDecimal("5.0"), new BigDecimal("10.0"),
                16_000_00L), admin);
        FeeZoneView c = setup.createZone(new FeeZoneForm("Zone C", new BigDecimal("10.0"), null, 20_000_00L), admin);
        Map<String, UUID> zones = Map.of("A", a.id(), "B", b.id(), "C", c.id());

        List<VehicleView> vehicles = new ArrayList<>();
        vehicles.add(setup.createVehicle(new VehicleForm("TS09 UB 4410", VehicleType.BUS, 40, "GPS-4410",
                today.plusDays(210), today.plusDays(150), today.plusDays(300), today.plusDays(90),
                VehicleStatus.ACTIVE), admin));
        vehicles.add(setup.createVehicle(new VehicleForm("TS09 UB 4411", VehicleType.BUS, 40, null,
                today.plusDays(180), today.plusDays(200), today.plusDays(320), today.plusDays(75),
                VehicleStatus.ACTIVE), admin));
        // The mini bus's fitness certificate is due for renewal soon: the vehicles page and report flag it.
        vehicles.add(setup.createVehicle(new VehicleForm("TS09 UC 1029", VehicleType.MINI_BUS, 26, null,
                today.plusDays(120), today.plusDays(FITNESS_DAYS_LEFT), today.plusDays(240), today.plusDays(60),
                VehicleStatus.ACTIVE), admin));

        List<UUID> crewIds = new ArrayList<>();
        for (int i = 0; i < CREW.size(); i++) {
            String[] p = CREW.get(i).split("\\|");
            boolean driver = p[2].equals("DRIVER");
            CrewView member = setup.addCrew(new CrewForm(crewUsers.get(p[1] + DemoDataSeeder.DEMO_DOMAIN),
                    driver ? CrewKind.DRIVER : CrewKind.ATTENDANT,
                    driver ? "TS09 2015000" + (4410 + i) : null, driver ? today.plusYears(3) : null,
                    LocalDate.of(2025, 5, 12).plusDays(i * 9L)), admin);
            crewIds.add(member.id());
        }

        List<RouteDetail> result = new ArrayList<>();
        for (int r = 0; r < ROUTES.size(); r++) {
            String[] route = ROUTES.get(r);
            RouteDetail made = routes.create(new RouteForm(route[0], vehicles.get(r).id(), crewIds.get(r * 2),
                    crewIds.get(r * 2 + 1), true, true, true), admin);
            List<StopForm> stops = new ArrayList<>();
            for (int s = 2; s < route.length; s++) {
                String[] p = route[s].split(";");
                stops.add(new StopForm(null, p[0], p[1], LocalTime.parse(p[2]), LocalTime.parse(p[3]),
                        new BigDecimal(p[4]), new BigDecimal(p[5]), zones.get(p[6])));
            }
            result.add(routes.saveStops(made.id(), new StopsForm(stops), admin));
        }
        return result;
    }

    /**
     * Arjun first, then other active students of the current year, a few from each class; never the student the demo
     * later gives a transfer certificate, whose seat would end when they leave.
     */
    private List<StudentRow> riders() {
        List<StudentRow> all = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, null, 0,
                StudentService.MAX_PAGE_SIZE)).items().stream()
                .filter(r -> !r.fullName().equals(DemoCertificatesData.LEFT_STUDENT))
                .toList();
        List<StudentRow> result = new ArrayList<>();
        all.stream().filter(r -> r.fullName().equals(ARJUN)).findFirst().ifPresent(result::add);
        int every = Math.max(1, all.size() / (OTHER_RIDERS + 2));
        for (int i = 0; i < all.size() && result.size() < OTHER_RIDERS + 1; i += every) {
            StudentRow r = all.get(i);
            if (!r.fullName().equals(ARJUN)) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * Route 1's morning trip today, run by its attendant: a position at Arjun's stop, Arjun and the other students
     * at the first two stops boarded, one of them absent, and the trip ended.
     */
    private Object morningTrip(RouteDetail route, List<StudentRow> riders, Actor attendant) {
        TripView trip = trips.start(route.id(), TripKind.MORNING, attendant, false);
        StopView arjunsStop = route.stops().get(1);
        trips.position(trip.id(), new PositionForm(arjunsStop.latitude().doubleValue(),
                arjunsStop.longitude().doubleValue(), 25, 18.0, null), attendant, false);
        TripView now = trips.trip(trip.id(), attendant.id(), false);
        boolean first = true;
        for (var stop : now.stops()) {
            for (var student : stop.students()) {
                if (stop.seq() > 2) {
                    continue;
                }
                boolean arjun = student.name().equals(ARJUN);
                BoardingStatus status = !arjun && first ? BoardingStatus.ABSENT : BoardingStatus.BOARDED;
                if (!arjun) {
                    first = false;
                }
                trips.mark(trip.id(), student.id(), status, attendant, false);
            }
        }
        trips.end(trip.id(), attendant, false);
        return riders.size();
    }

    private boolean step(UUID tenantId, String what, Supplier<Object> work) {
        try {
            TenantContext.runAs(tenantId, () -> tx.execute(status -> work.get()));
            return true;
        } catch (RuntimeException e) {
            log.info("Skipped demo transport step '{}' ({})", what, e.getMessage());
            return false;
        }
    }
}
