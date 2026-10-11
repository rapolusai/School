package com.akshara.transport;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * A school set up for the transport tests through the API: the current year, Class 5 A with three students (Asha's
 * mother Lata has a sign-in), a transport manager, a principal, a teacher, and three bus staff (a driver, an attendant
 * and a second attendant who works another route), each with the Bus attendant role.
 */
final class TransportSchool {

    /** Three stops about 1.1 km apart going north, in Hyderabad. */
    static final double[][] POINTS = {{17.4100, 78.4400}, {17.4200, 78.4400}, {17.4300, 78.4400}};

    final TestApi api;
    final School school;
    final Session admin;
    final String yearId;
    final String classId;
    final String section;
    /** Asha Rao, whose mother Lata has a sign-in. */
    final String asha;
    final String bala;
    final String chitra;
    final Session parent;
    final Session manager;
    final Session principal;
    final Session teacher;
    final String teacherId;
    final String driverUser;
    final Session driver;
    final String attendantUser;
    final Session attendant;
    final String otherAttendantUser;
    final Session otherAttendant;
    final String domain;

    /** A route ready to run: its vehicle, crew, zone and three stops (in pickup order). */
    record Bus(String vehicle, String driver, String attendant, String zone, String route, List<String> stops) {
    }

    TransportSchool(TestApi api) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        yearId = fixtures.currentYear(admin);
        classId = fixtures.schoolClass(admin, "Class 5");
        section = fixtures.section(admin, classId, "A", 40);
        asha = fixtures.student(admin, section, "TR-1", "Asha", "Rao", "Lata Rao", "9876501001");
        bala = fixtures.student(admin, section, "TR-2", "Bala", "Iyer", "Uma Iyer", "9876501002");
        chitra = fixtures.student(admin, section, "TR-3", "Chitra", "Das", "Mala Das", "9876501003");
        domain = "@" + school.code() + ".akshara.test";
        manager = user("Mohan Manager", "mohan", "TRANSPORT_MANAGER");
        principal = user("Padma Principal", "padma", "PRINCIPAL");
        teacherId = api.createUser(admin, "Tara Teacher", "tara" + domain, List.of("TEACHER"));
        teacher = api.login(school.code(), "tara" + domain, TestApi.PASSWORD);
        driverUser = api.createUser(admin, "Dev Driver", "dev" + domain, List.of("BUS_ATTENDANT"));
        driver = api.login(school.code(), "dev" + domain, TestApi.PASSWORD);
        attendantUser = api.createUser(admin, "Anita Attendant", "anita" + domain, List.of("BUS_ATTENDANT"));
        attendant = api.login(school.code(), "anita" + domain, TestApi.PASSWORD);
        otherAttendantUser = api.createUser(admin, "Omana Attendant", "omana" + domain, List.of("BUS_ATTENDANT"));
        otherAttendant = api.login(school.code(), "omana" + domain, TestApi.PASSWORD);
        String guardian = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted("lata" + domain, TestApi.PASSWORD))
                .andExpect(status().isOk());
        parent = api.login(school.code(), "lata" + domain, TestApi.PASSWORD);
    }

    private Session user(String name, String mailbox, String role) throws Exception {
        api.createUser(admin, name, mailbox + domain, List.of(role));
        return api.login(school.code(), mailbox + domain, TestApi.PASSWORD);
    }

    static LocalDate today() {
        return TransportRules.today();
    }

    String token() {
        return manager.accessToken();
    }

    String zone(String name, long annualFeePaise) throws Exception {
        return TestApi.read(api.post("/api/transport/fee-zones", token(), """
                {"name":"%s","minKm":0,"maxKm":null,"annualFeePaise":%d}""".formatted(name, annualFeePaise))
                .andExpect(status().isCreated()), "$.id");
    }

    /** A vehicle with every document valid for two years, except the fitness certificate when given. */
    String vehicle(String registrationNo, int seats, LocalDate fitnessExpiry) throws Exception {
        LocalDate later = today().plusYears(2);
        return TestApi.read(api.post("/api/transport/vehicles", token(), """
                {"registrationNo":"%s","type":"BUS","seats":%d,"insuranceExpiry":"%s","fitnessExpiry":"%s",
                 "permitExpiry":"%s","pollutionExpiry":"%s"}""".formatted(registrationNo, seats, later,
                fitnessExpiry == null ? later : fitnessExpiry, later, later))
                .andExpect(status().isCreated()), "$.id");
    }

    String driverCrew(String userId, LocalDate licenceExpiry) throws Exception {
        return TestApi.read(api.post("/api/transport/crew", token(), """
                {"userId":"%s","kind":"DRIVER","licenceNo":"TS09 20150001234","licenceExpiry":"%s",
                 "policeVerifiedOn":"2026-01-10"}""".formatted(userId, licenceExpiry))
                .andExpect(status().isCreated()), "$.id");
    }

    String attendantCrew(String userId) throws Exception {
        return TestApi.read(api.post("/api/transport/crew", token(), """
                {"userId":"%s","kind":"ATTENDANT","policeVerifiedOn":"2026-01-10"}""".formatted(userId))
                .andExpect(status().isCreated()), "$.id");
    }

    String route(String name, String vehicleId, String driverId, String attendantId) throws Exception {
        return TestApi.read(api.post("/api/transport/routes", token(), """
                {"name":"%s","vehicleId":%s,"driverId":%s,"attendantId":%s}""".formatted(name, json(vehicleId),
                json(driverId), json(attendantId))).andExpect(status().isCreated()), "$.id");
    }

    /** Gives the route three stops (Gate, Market, Temple) at {@link #POINTS}, all in the zone; returns their ids. */
    List<String> stops(String routeId, String zoneId) throws Exception {
        String body = api.put("/api/transport/routes/" + routeId + "/stops", token(), """
                {"stops":[
                 {"name":"Gate Stop","pickupTime":"07:10","dropTime":"15:10","latitude":%s,"longitude":%s,"feeZoneId":%s},
                 {"name":"Market Stop","landmark":"Near the post office","pickupTime":"07:20","dropTime":"15:00",
                  "latitude":%s,"longitude":%s,"feeZoneId":%s},
                 {"name":"Temple Stop","pickupTime":"07:30","dropTime":"14:50","latitude":%s,"longitude":%s,"feeZoneId":%s}
                ]}""".formatted(POINTS[0][0], POINTS[0][1], json(zoneId), POINTS[1][0], POINTS[1][1], json(zoneId),
                POINTS[2][0], POINTS[2][1], json(zoneId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.stops[*].id");
    }

    /** Route 1 with a 40-seat bus, the driver, the attendant and a ₹12,000 zone on all three stops. */
    Bus bus() throws Exception {
        String zone = zone("Zone A", 12_000_00);
        String vehicle = vehicle("TS09 UB 4410", 40, null);
        String driverId = driverCrew(driverUser, today().plusYears(3));
        String attendantId = attendantCrew(attendantUser);
        String route = route("Route 1", vehicle, driverId, attendantId);
        return new Bus(vehicle, driverId, attendantId, zone, route, stops(route, zone));
    }

    /** Allocates the student to the route's stop from the day; returns the allocation id. */
    String allocate(String studentId, String routeId, String stopId, LocalDate startDate) throws Exception {
        return TestApi.read(api.post("/api/transport/allocations", token(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}""".formatted(studentId, routeId,
                stopId, startDate)).andExpect(status().isCreated()), "$.id");
    }

    static String json(String id) {
        return id == null ? "null" : "\"" + id + "\"";
    }
}
