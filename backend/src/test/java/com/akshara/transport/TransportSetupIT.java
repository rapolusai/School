package com.akshara.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/** Who may see and change the transport setup, and the setup itself: alerts, fee zones, vehicles and crew. */
class TransportSetupIT extends IntegrationTest {

    @Test
    void transportNeedsItsPermissions() throws Exception {
        TransportSchool s = new TransportSchool(api);
        String zone = """
                {"name":"Zone A","minKm":0,"maxKm":5,"annualFeePaise":1200000}""";
        api.get("/api/transport/vehicles", null).andExpect(status().isUnauthorized());
        for (Session reader : List.of(s.teacher, s.attendant, s.parent)) {
            api.get("/api/transport/vehicles", reader.accessToken()).andExpect(status().isForbidden());
            api.get("/api/transport/routes", reader.accessToken()).andExpect(status().isForbidden());
            api.get("/api/transport/reports/route-students", reader.accessToken()).andExpect(status().isForbidden());
            api.get("/api/transport/live", reader.accessToken()).andExpect(status().isForbidden());
            api.post("/api/transport/fee-zones", reader.accessToken(), zone).andExpect(status().isForbidden());
        }
        // The principal reads everything and changes nothing.
        api.get("/api/transport/vehicles", s.principal.accessToken()).andExpect(status().isOk());
        api.get("/api/transport/settings", s.principal.accessToken()).andExpect(status().isOk());
        api.get("/api/transport/live", s.principal.accessToken()).andExpect(status().isOk());
        api.post("/api/transport/fee-zones", s.principal.accessToken(), zone).andExpect(status().isForbidden());
        api.get("/api/transport/my-trips", s.principal.accessToken()).andExpect(status().isForbidden());
        // Bus staff run trips and see nothing else; teachers and parents do not run trips.
        api.get("/api/transport/my-trips", s.attendant.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.routes", empty()));
        api.get("/api/transport/my-trips", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/transport/my-trips", s.parent.accessToken()).andExpect(status().isForbidden());
        // The transport manager and the school admin manage.
        api.post("/api/transport/fee-zones", s.manager.accessToken(), zone).andExpect(status().isCreated());
        api.post("/api/transport/fee-zones", s.admin.accessToken(), zone.replace("Zone A", "Zone B"))
                .andExpect(status().isCreated());
        // Everyone signed in sees their own family's bus (nothing for staff).
        api.get("/api/me/transport", s.teacher.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.children", empty()));
        // The roles and their permissions come with every school.
        String roles = api.get("/api/roles", s.admin.accessToken()).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(roles, "$[?(@.code == 'TRANSPORT_MANAGER')].permissions[*]"))
                .contains("transport.read", "transport.manage", "transport.trip");
        assertThat(JsonPath.<List<String>>read(roles, "$[?(@.code == 'BUS_ATTENDANT')].permissions[*]"))
                .contains("transport.trip").doesNotContain("transport.read", "students.read");
        assertThat(JsonPath.<List<String>>read(roles, "$[?(@.code == 'PRINCIPAL')].permissions[*]"))
                .contains("transport.read").doesNotContain("transport.manage");
        assertThat(JsonPath.<List<String>>read(roles, "$[?(@.code == 'SCHOOL_ADMIN')].permissions[*]"))
                .contains("transport.read", "transport.manage", "transport.trip");
    }

    @Test
    void alertSettingsStartWithBoardedAndDroppedOn() throws Exception {
        TransportSchool s = new TransportSchool(api);
        api.get("/api/transport/settings", s.token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardedAlerts").value(true))
                .andExpect(jsonPath("$.droppedAlerts").value(true))
                .andExpect(jsonPath("$.approachingAlerts").value(false))
                .andExpect(jsonPath("$.delayAlerts").value(true));
        api.put("/api/transport/settings", s.token(), """
                {"boardedAlerts":false,"droppedAlerts":true,"approachingAlerts":true,"delayAlerts":true}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.approachingAlerts").value(true));
        api.get("/api/transport/settings", s.principal.accessToken())
                .andExpect(jsonPath("$.boardedAlerts").value(false))
                .andExpect(jsonPath("$.approachingAlerts").value(true));
        api.put("/api/transport/settings", s.token(), """
                {"boardedAlerts":true}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.droppedAlerts").exists());
        api.get("/api/audit-events?limit=200", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("transport_settings.updated")));
    }

    @Test
    void feeZonesAreNamedSlabsThatCannotBeDeletedWhileStopsUseThem() throws Exception {
        TransportSchool s = new TransportSchool(api);
        String a = TestApi.read(api.post("/api/transport/fee-zones", s.token(), """
                {"name":"Zone A","minKm":0,"maxKm":5,"annualFeePaise":1200000}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.annualFeePaise").value(1200000))
                .andExpect(jsonPath("$.stops").value(0)), "$.id");
        api.post("/api/transport/fee-zones", s.token(), """
                {"name":"zone a","minKm":5,"maxKm":10,"annualFeePaise":1600000}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/transport/fee-zones", s.token(), """
                {"name":"Zone B","minKm":5,"maxKm":5,"annualFeePaise":1600000}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.maxKm").exists());
        api.post("/api/transport/fee-zones", s.token(), """
                {"name":"Zone B","minKm":5,"annualFeePaise":-1}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.annualFeePaise").exists());
        String b = TestApi.read(api.post("/api/transport/fee-zones", s.token(), """
                {"name":"Zone B","minKm":5,"annualFeePaise":1600000}""").andExpect(status().isCreated()), "$.id");
        api.put("/api/transport/fee-zones/" + b, s.token(), """
                {"name":"Zone B (far)","minKm":5,"maxKm":null,"annualFeePaise":1800000}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Zone B (far)"));
        String route = s.route("Route 9", null, null, null);
        s.stops(route, a);
        api.get("/api/transport/fee-zones", s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Zone A", "Zone B (far)")))
                .andExpect(jsonPath("$[0].stops").value(3));
        api.delete("/api/transport/fee-zones/" + a, s.token()).andExpect(status().isConflict());
        api.delete("/api/transport/fee-zones/" + b, s.token()).andExpect(status().isNoContent());
        // Another school's zone does not exist here.
        TransportSchool other = new TransportSchool(api);
        api.put("/api/transport/fee-zones/" + a, other.token(), """
                {"name":"Mine","minKm":0,"annualFeePaise":1}""").andExpect(status().isNotFound());
        api.delete("/api/transport/fee-zones/" + a, other.token()).andExpect(status().isNotFound());
        api.get("/api/audit-events?limit=200", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("transport_zone.created", "transport_zone.updated",
                        "transport_zone.deleted")));
    }

    @Test
    void vehiclesWarnAboutTheirDocuments() throws Exception {
        TransportSchool s = new TransportSchool(api);
        LocalDate today = TransportSchool.today();
        String bus = TestApi.read(api.post("/api/transport/vehicles", s.token(), """
                {"registrationNo":"ts09  ub 4410","type":"BUS","seats":40,"insuranceExpiry":"%s",
                 "fitnessExpiry":"%s","permitExpiry":"%s","pollutionExpiry":"%s"}"""
                .formatted(today.plusYears(1), today.plusDays(12), today.plusYears(1), today.minusDays(1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationNo").value("TS09 UB 4410"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.documents[1].kind").value("FITNESS"))
                .andExpect(jsonPath("$.documents[1].state").value("EXPIRING"))
                .andExpect(jsonPath("$.documents[1].daysLeft").value(12))
                .andExpect(jsonPath("$.documents[3].state").value("EXPIRED"))
                .andExpect(jsonPath("$.flags", containsInAnyOrder("DOCUMENT_EXPIRED", "DOCUMENT_EXPIRING")))
                .andExpect(jsonPath("$.deviceToken").value(nullValue())), "$.id");
        api.post("/api/transport/vehicles", s.token(), """
                {"registrationNo":"TS09 UB 4410","type":"VAN","seats":12}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.registrationNo").exists());
        api.post("/api/transport/vehicles", s.token(), """
                {"registrationNo":"TS09/UB","type":"VAN","seats":0}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.registrationNo").exists())
                .andExpect(jsonPath("$.errors.seats").exists());
        // No fitness certificate on record: the vehicle may not run.
        String van = TestApi.read(api.post("/api/transport/vehicles", s.token(), """
                {"registrationNo":"TS09 UC 1029","type":"VAN","seats":12}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flags", hasItem("FITNESS_INVALID"))), "$.id");
        api.put("/api/transport/vehicles/" + van, s.token(), """
                {"registrationNo":"TS09 UC 1029","type":"VAN","seats":12,"fitnessExpiry":"%s",
                 "insuranceExpiry":"%s","permitExpiry":"%s","pollutionExpiry":"%s","status":"MAINTENANCE"}"""
                .formatted(today.minusDays(3), today.plusYears(1), today.plusYears(1), today.plusYears(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"))
                .andExpect(jsonPath("$.flags", containsInAnyOrder("FITNESS_INVALID", "DOCUMENT_EXPIRED")));
        // A route on the bus without a driver; then with a driver whose licence has expired.
        String route = s.route("Route 1", bus, null, null);
        api.get("/api/transport/vehicles/" + bus, s.principal.accessToken())
                .andExpect(jsonPath("$.flags", hasItem("NO_DRIVER")))
                .andExpect(jsonPath("$.routes[0].name").value("Route 1"));
        String driver = s.driverCrew(s.driverUser, today.minusDays(1));
        api.put("/api/transport/routes/" + route, s.token(), """
                {"name":"Route 1","vehicleId":"%s","driverId":"%s"}""".formatted(bus, driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags", hasItems("DRIVER_LICENCE_INVALID", "NO_ATTENDANT", "NO_STOPS")));
        api.get("/api/transport/vehicles", s.token())
                .andExpect(jsonPath("$[?(@.registrationNo == 'TS09 UB 4410')].flags[*]",
                        hasItem("DRIVER_LICENCE_INVALID")));
        // A vehicle on an active route cannot be retired.
        api.put("/api/transport/vehicles/" + bus, s.token(), """
                {"registrationNo":"TS09 UB 4410","type":"BUS","seats":40,"status":"RETIRED"}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.status").exists());
        // Another school's vehicle does not exist here.
        TransportSchool other = new TransportSchool(api);
        api.get("/api/transport/vehicles/" + bus, other.token()).andExpect(status().isNotFound());
        api.post("/api/transport/vehicles/" + bus + "/device-token", other.token(), null)
                .andExpect(status().isNotFound());
        api.get("/api/transport/vehicles", other.token()).andExpect(jsonPath("$", empty()));
    }

    @Test
    void aDeviceTokenIsShownOnceAndCanBeRevoked() throws Exception {
        TransportSchool s = new TransportSchool(api);
        String bus = s.vehicle("TS09 UB 4410", 40, null);
        String body = api.post("/api/transport/vehicles/" + bus + "/device-token", s.token(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", startsWith("akt_")))
                .andExpect(jsonPath("$.header").value("X-Device-Token"))
                .andExpect(jsonPath("$.endpoint").value("/api/public/transport/vehicles/" + bus + "/positions"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        String hint = JsonPath.read(body, "$.hint");
        assertThat(token).endsWith(hint).hasSizeGreaterThan(40);
        api.get("/api/transport/vehicles/" + bus, s.token())
                .andExpect(jsonPath("$.deviceToken.hint").value(hint))
                .andExpect(jsonPath("$.deviceToken.issuedAt").exists());
        // Only its hash is stored.
        assertThat(api.get("/api/transport/vehicles/" + bus, s.token()).andReturn().getResponse()
                .getContentAsString()).doesNotContain(token);
        api.delete("/api/transport/vehicles/" + bus + "/device-token", s.principal.accessToken())
                .andExpect(status().isForbidden());
        api.delete("/api/transport/vehicles/" + bus + "/device-token", s.token()).andExpect(status().isNoContent());
        api.get("/api/transport/vehicles/" + bus, s.token()).andExpect(jsonPath("$.deviceToken").value(nullValue()));
        api.get("/api/audit-events?limit=200", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("transport_vehicle.created",
                        "transport_vehicle.device_token_issued", "transport_vehicle.device_token_revoked")))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain(token));
    }

    @Test
    void driversAndAttendantsAreMembersOfStaff() throws Exception {
        TransportSchool s = new TransportSchool(api);
        LocalDate today = TransportSchool.today();
        api.get("/api/transport/staff?q=dev", s.token()).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Dev Driver"))
                .andExpect(jsonPath("$[0].crew").value(false));
        // A parent is not staff; nor is someone of another school.
        String lata = TestApi.read(api.get("/api/me", s.parent.accessToken()), "$.id");
        api.post("/api/transport/crew", s.token(), """
                {"userId":"%s","kind":"ATTENDANT"}""".formatted(lata))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.userId").exists());
        TransportSchool other = new TransportSchool(api);
        api.post("/api/transport/crew", s.token(), """
                {"userId":"%s","kind":"ATTENDANT"}""".formatted(other.attendantUser))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.userId").exists());
        // A driver needs a licence and its expiry date.
        api.post("/api/transport/crew", s.token(), """
                {"userId":"%s","kind":"DRIVER"}""".formatted(s.driverUser))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.licenceNo").exists());
        api.post("/api/transport/crew", s.token(), """
                {"userId":"%s","kind":"DRIVER","licenceNo":"TS09 2015"}""".formatted(s.driverUser))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.licenceExpiry").exists());
        String driver = TestApi.read(api.post("/api/transport/crew", s.token(), """
                {"userId":"%s","kind":"DRIVER","licenceNo":"ts09 20150001234","licenceExpiry":"%s"}"""
                .formatted(s.driverUser, today.plusDays(20)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Dev Driver"))
                .andExpect(jsonPath("$.licenceNo").value("TS09 20150001234"))
                .andExpect(jsonPath("$.licenceState").value("EXPIRING"))
                .andExpect(jsonPath("$.flags", containsInAnyOrder("LICENCE_EXPIRING",
                        "POLICE_VERIFICATION_MISSING"))), "$.id");
        api.post("/api/transport/crew", s.token(), """
                {"userId":"%s","kind":"ATTENDANT"}""".formatted(s.driverUser)).andExpect(status().isConflict());
        String attendant = s.attendantCrew(s.attendantUser);
        api.get("/api/transport/staff?q=dev", s.token()).andExpect(jsonPath("$[0].crew").value(true));
        // An expired licence makes the driver invalid.
        api.put("/api/transport/crew/" + driver, s.token(), """
                {"kind":"DRIVER","licenceNo":"TS09 20150001234","licenceExpiry":"%s","policeVerifiedOn":"2026-01-10"}"""
                .formatted(today.minusDays(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licenceState").value("EXPIRED"))
                .andExpect(jsonPath("$.flags", contains("LICENCE_INVALID")));
        // The attendant cannot be the driver; a driver must be a driver.
        String bus = s.vehicle("TS09 UB 4410", 40, null);
        api.post("/api/transport/routes", s.token(), """
                {"name":"Route 1","vehicleId":"%s","driverId":"%s"}""".formatted(bus, attendant))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.driverId").exists());
        api.post("/api/transport/routes", s.token(), """
                {"name":"Route 1","driverId":"%s","attendantId":"%s"}""".formatted(driver, driver))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.attendantId").exists());
        String route = s.route("Route 1", bus, driver, attendant);
        api.get("/api/transport/crew", s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Anita Attendant", "Dev Driver")))
                .andExpect(jsonPath("$[1].routes[0].name").value("Route 1"))
                .andExpect(jsonPath("$[0].licenceState").value(nullValue()));
        // Crew on an active route stay until the route has someone else.
        api.delete("/api/transport/crew/" + attendant, s.token()).andExpect(status().isConflict());
        api.put("/api/transport/crew/" + driver, s.token(), """
                {"kind":"ATTENDANT"}""").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.kind").exists());
        api.put("/api/transport/routes/" + route, s.token(), """
                {"name":"Route 1","vehicleId":"%s","driverId":"%s"}""".formatted(bus, driver))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attendant").value(nullValue()));
        api.delete("/api/transport/crew/" + attendant, s.token()).andExpect(status().isNoContent());
        api.get("/api/transport/crew", s.token()).andExpect(jsonPath("$[*].name", not(hasItem("Anita Attendant"))));
        api.delete("/api/transport/crew/" + driver, other.token()).andExpect(status().isNotFound());
        api.get("/api/audit-events?limit=200", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("transport_crew.added", "transport_crew.updated",
                        "transport_crew.removed", "transport_route.created", "transport_route.updated")));
    }
}
