package com.akshara.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.transport.TransportSchool.Bus;
import com.jayway.jsonpath.JsonPath;

/** Routes with their stops, and students' places on them: one at a time, by class, moved and ended. */
class TransportAllocationsIT extends IntegrationTest {

    @Test
    void routesHaveStopsInPickupOrder() throws Exception {
        TransportSchool s = new TransportSchool(api);
        String route = TestApi.read(api.post("/api/transport/routes", s.token(), """
                {"name":"Route 1"}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.runsMorning").value(true))
                .andExpect(jsonPath("$.runsAfternoon").value(true))
                .andExpect(jsonPath("$.flags", contains("NO_VEHICLE", "NO_DRIVER", "NO_ATTENDANT", "NO_STOPS"))),
                "$.id");
        api.post("/api/transport/routes", s.token(), """
                {"name":"route 1"}""").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/transport/routes", s.token(), """
                {"name":"Route 2","runsMorning":false,"runsAfternoon":false}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.runsMorning").exists());
        TransportSchool other = new TransportSchool(api);
        String theirBus = other.vehicle("TS10 AA 0001", 30, null);
        String theirZone = other.zone("Their zone", 100);
        api.post("/api/transport/routes", s.token(), """
                {"name":"Route 2","vehicleId":"%s"}""".formatted(theirBus))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.vehicleId").exists());
        String zone = s.zone("Zone A", 12_000_00);
        // Coordinates come in pairs; zones and stops must be the school's and the route's.
        api.put("/api/transport/routes/" + route + "/stops", s.token(), """
                {"stops":[{"name":"Gate","latitude":17.41}]}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['stops[0].latitude']").exists());
        api.put("/api/transport/routes/" + route + "/stops", s.token(), """
                {"stops":[{"name":"Gate"},{"name":"Market","feeZoneId":"%s"}]}""".formatted(theirZone))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['stops[1].feeZoneId']").exists());
        List<String> stops = s.stops(route, zone);
        api.get("/api/transport/routes/" + route, s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[*].name", contains("Gate Stop", "Market Stop", "Temple Stop")))
                .andExpect(jsonPath("$.stops[*].seq", contains(1, 2, 3)))
                .andExpect(jsonPath("$.stops[1].landmark").value("Near the post office"))
                .andExpect(jsonPath("$.stops[1].feeZone.annualFeePaise").value(12_000_00))
                .andExpect(jsonPath("$.firstPickup", startsWith("07:10")))
                .andExpect(jsonPath("$.flags", not(hasItem("NO_STOPS"))));
        String otherRoute = other.route("Their route", null, null, null);
        List<String> theirStops = other.stops(otherRoute, null);
        api.put("/api/transport/routes/" + route + "/stops", s.token(), """
                {"stops":[{"id":"%s","name":"Gate"}]}""".formatted(theirStops.getFirst()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['stops[0].id']").exists());
        // Reordered and renamed: kept stops keep their students and the allocation shows the new name.
        String allocation = s.allocate(s.asha, route, stops.get(1), TransportSchool.today());
        api.put("/api/transport/routes/" + route + "/stops", s.token(), """
                {"stops":[{"id":"%s","name":"Temple Stop"},{"id":"%s","name":"Market Road Stop"},
                          {"id":"%s","name":"Gate Stop"},{"name":"Lake Stop","pickupTime":"07:40"}]}"""
                .formatted(stops.get(2), stops.get(1), stops.get(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[*].name", contains("Temple Stop", "Market Road Stop", "Gate Stop",
                        "Lake Stop")))
                .andExpect(jsonPath("$.stops[1].id").value(stops.get(1)))
                .andExpect(jsonPath("$.stops[1].students").value(1))
                .andExpect(jsonPath("$.allocated").value(1));
        api.get("/api/transport/allocations/" + allocation, s.token())
                .andExpect(jsonPath("$.stop.name").value("Market Road Stop"));
        // A stop with students cannot be removed.
        api.put("/api/transport/routes/" + route + "/stops", s.token(), """
                {"stops":[{"id":"%s","name":"Temple Stop"}]}""".formatted(stops.get(2)))
                .andExpect(status().isConflict());
        // Nor can the route stop running while students travel on it.
        api.put("/api/transport/routes/" + route, s.token(), """
                {"name":"Route 1","active":false}""").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.active").exists());
        api.get("/api/transport/routes/" + route, other.token()).andExpect(status().isNotFound());
        api.put("/api/transport/routes/" + route + "/stops", other.token(), """
                {"stops":[]}""").andExpect(status().isNotFound());
        api.get("/api/transport/routes", other.token()).andExpect(jsonPath("$[*].name", contains("Their route")));
        api.get("/api/audit-events?limit=200", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("transport_route.created",
                        "transport_route.stops_saved")));
    }

    @Test
    void aStudentHasOnePlaceOnTheBusesAtATime() throws Exception {
        TransportSchool s = new TransportSchool(api);
        Bus bus = s.bus();
        LocalDate today = TransportSchool.today();
        String allocation = TestApi.read(api.post("/api/transport/allocations", s.token(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(s.asha, bus.route(), bus.stops().get(1), today))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.student.name").value("Asha Rao"))
                .andExpect(jsonPath("$.student.classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.route.name").value("Route 1"))
                .andExpect(jsonPath("$.stop.name").value("Market Stop"))
                .andExpect(jsonPath("$.feeZone.name").value("Zone A"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.createdByName").value("Mohan Manager")), "$.id");
        api.post("/api/transport/allocations", s.token(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(s.asha, bus.route(), bus.stops().get(0), today.plusDays(5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(AllocationService.ALREADY_ALLOCATED_TYPE))
                .andExpect(jsonPath("$.errors.studentId").exists());
        // Wrong stop, wrong dates, another school's student.
        TransportSchool other = new TransportSchool(api);
        String otherRoute = s.route("Route 2", null, null, null);
        List<String> otherStops = s.stops(otherRoute, null);
        api.post("/api/transport/allocations", s.token(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(s.bala, bus.route(), otherStops.getFirst(), today))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.stopId").exists());
        api.post("/api/transport/allocations", s.token(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s","endDate":"%s"}"""
                .formatted(s.bala, bus.route(), bus.stops().getFirst(), today, today.minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.endDate").exists());
        api.post("/api/transport/allocations", s.token(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(other.asha, bus.route(), bus.stops().getFirst(), today))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/transport/allocations", s.principal.accessToken(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(s.bala, bus.route(), bus.stops().getFirst(), today)).andExpect(status().isForbidden());
        // Bala travels for ten days; a later allocation closes that one.
        String first = TestApi.read(api.post("/api/transport/allocations", s.token(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s","endDate":"%s"}"""
                .formatted(s.bala, bus.route(), bus.stops().getFirst(), today, today.plusDays(9)))
                .andExpect(status().isCreated()), "$.id");
        api.post("/api/transport/allocations", s.token(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(s.bala, otherRoute, otherStops.getFirst(), today.plusDays(10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current").value(false));
        api.get("/api/transport/allocations/" + first, s.token())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endDate").value(today.plusDays(9).toString()))
                .andExpect(jsonPath("$.endReason").value("Replaced by a new allocation"));
        // Moving Asha to another stop of the route, then ending her place.
        api.put("/api/transport/allocations/" + allocation, s.token(), """
                {"stopId":"%s"}""".formatted(otherStops.getFirst()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.stopId").exists());
        api.put("/api/transport/allocations/" + allocation, s.token(), """
                {"stopId":"%s","endDate":"%s"}""".formatted(bus.stops().get(2), today.plusMonths(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stop.name").value("Temple Stop"))
                .andExpect(jsonPath("$.endDate").value(today.plusMonths(3).toString()));
        api.post("/api/transport/allocations/" + allocation + "/end", s.token(), """
                {"endDate":"%s","reason":"Moved house"}""".formatted(today.minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.endDate").exists());
        api.post("/api/transport/allocations/" + allocation + "/end", s.token(), """
                {"endDate":"%s","reason":"Moved house"}""".formatted(today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("Moved house"))
                .andExpect(jsonPath("$.endedByName").value("Mohan Manager"))
                // Her last day is today: she still rides today.
                .andExpect(jsonPath("$.current").value(true));
        api.post("/api/transport/allocations/" + allocation + "/end", s.token(), """
                {"endDate":"%s"}""".formatted(today)).andExpect(status().isConflict());
        api.put("/api/transport/allocations/" + allocation, s.token(), """
                {"stopId":"%s"}""".formatted(bus.stops().get(0))).andExpect(status().isConflict());
        // Once ended, she can be allocated again from tomorrow.
        s.allocate(s.asha, bus.route(), bus.stops().get(0), today.plusDays(1));
        // Lists: active by default, everything with ALL, by route, class and name.
        api.get("/api/transport/allocations", s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].status", contains("ACTIVE", "ACTIVE")));
        api.get("/api/transport/allocations?status=ALL", s.token()).andExpect(jsonPath("$", hasSize(4)));
        api.get("/api/transport/allocations?status=ENDED&q=asha", s.token())
                .andExpect(jsonPath("$[*].student.name", contains("Asha Rao")));
        api.get("/api/transport/allocations?routeId=" + otherRoute, s.token())
                .andExpect(jsonPath("$[*].student.name", contains("Bala Iyer")));
        api.get("/api/transport/allocations?status=ALL&classId=" + s.classId + "&q=TR-2", s.token())
                .andExpect(jsonPath("$[*].student.name", contains("Bala Iyer", "Bala Iyer")));
        api.get("/api/transport/students?q=asha", s.token()).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Asha Rao"))
                .andExpect(jsonPath("$[0].allocation.routeName").value("Route 1"))
                .andExpect(jsonPath("$[0].allocation.stopName").value("Gate Stop"));
        api.get("/api/transport/students?q=chitra", s.token())
                .andExpect(jsonPath("$[0].allocation").value(nullValue()));
        // Another school sees none of it.
        api.get("/api/transport/allocations/" + allocation, other.token()).andExpect(status().isNotFound());
        api.post("/api/transport/allocations/" + allocation + "/end", other.token(), """
                {"endDate":"%s"}""".formatted(today)).andExpect(status().isNotFound());
        api.get("/api/transport/allocations?status=ALL", other.token()).andExpect(jsonPath("$", empty()));
        api.get("/api/audit-events?limit=200", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("transport_allocation.created",
                        "transport_allocation.updated", "transport_allocation.ended")));
    }

    @Test
    void aFullBusTakesMoreStudentsOnlyWithAReason() throws Exception {
        TransportSchool s = new TransportSchool(api);
        LocalDate today = TransportSchool.today();
        String zone = s.zone("Zone A", 12_000_00);
        String van = s.vehicle("TS09 UC 1029", 2, null);
        String route = s.route("Van route", van, null, null);
        List<String> stops = s.stops(route, zone);
        s.allocate(s.asha, route, stops.getFirst(), today);
        s.allocate(s.bala, route, stops.getFirst(), today);
        api.post("/api/transport/allocations", s.token(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(s.chitra, route, stops.get(1), today))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(AllocationService.ROUTE_FULL_TYPE))
                .andExpect(jsonPath("$.errors.overCapacityReason").exists());
        api.post("/api/transport/allocations", s.token(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s",
                 "overCapacityReason":"Sibling of a rider; a second van starts next month"}"""
                .formatted(s.chitra, route, stops.get(1), today))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overCapacityReason")
                        .value("Sibling of a rider; a second van starts next month"));
        api.get("/api/transport/routes/" + route, s.token())
                .andExpect(jsonPath("$.allocated").value(3))
                .andExpect(jsonPath("$.seats").value(2))
                .andExpect(jsonPath("$.flags", hasItem("OVER_CAPACITY")));
        String audit = api.get("/api/audit-events?limit=200", s.admin.accessToken()).andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<Object>>read(audit,
                "$[?(@.action == 'transport_allocation.created')].details.overCapacityReason"))
                .containsExactly("Sibling of a rider; a second van starts next month");
    }

    @Test
    void aClassIsAllocatedTogetherSkippingStudentsAlreadyOnABus() throws Exception {
        TransportSchool s = new TransportSchool(api);
        Bus bus = s.bus();
        LocalDate today = TransportSchool.today();
        SchoolFixtures fixtures = new SchoolFixtures(api);
        String sectionB = fixtures.section(s.admin, s.classId, "B", 40);
        String dev = fixtures.student(s.admin, sectionB, "TR-4", "Devi", "Nair", "Rani Nair", "9876501004");
        s.allocate(s.bala, bus.route(), bus.stops().get(0), today);
        String body = api.post("/api/transport/allocations/bulk", s.token(), """
                {"classId":"%s","sectionId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(s.classId, s.section, bus.route(), bus.stops().get(2), today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocated").value(2))
                .andExpect(jsonPath("$.skipped[*].name", contains("Bala Iyer")))
                .andExpect(jsonPath("$.skipped[0].reason").value("Already on Route 1 (Gate Stop)"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Devi");
        api.get("/api/transport/allocations?stopId=" + bus.stops().get(2), s.token())
                .andExpect(jsonPath("$[*].student.name", containsInAnyOrder("Asha Rao", "Chitra Das")));
        // Chosen students only, and they must be in the class.
        api.post("/api/transport/allocations/bulk", s.token(), """
                {"classId":"%s","routeId":"%s","stopId":"%s","startDate":"%s","studentIds":["%s"]}"""
                .formatted(s.classId, bus.route(), bus.stops().get(2), today, bus.route()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.studentIds").exists());
        api.post("/api/transport/allocations/bulk", s.token(), """
                {"classId":"%s","routeId":"%s","stopId":"%s","startDate":"%s","studentIds":["%s"]}"""
                .formatted(s.classId, bus.route(), bus.stops().get(1), today, dev))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allocated").value(1))
                .andExpect(jsonPath("$.skipped", empty()));
        // A small bus refuses the whole class without a reason.
        String van = s.vehicle("TS09 UC 1029", 1, null);
        String vanRoute = s.route("Van route", van, null, null);
        List<String> vanStops = s.stops(vanRoute, null);
        String sectionC = fixtures.section(s.admin, s.classId, "C", 40);
        fixtures.student(s.admin, sectionC, "TR-5", "Esha", "Pillai", "Sita Pillai", "9876501005");
        fixtures.student(s.admin, sectionC, "TR-6", "Farah", "Khan", "Zoya Khan", "9876501006");
        api.post("/api/transport/allocations/bulk", s.token(), """
                {"classId":"%s","sectionId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(s.classId, sectionC, vanRoute, vanStops.getFirst(), today))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(AllocationService.ROUTE_FULL_TYPE));
        api.post("/api/transport/allocations/bulk", s.token(), """
                {"classId":"%s","sectionId":"%s","routeId":"%s","stopId":"%s","startDate":"%s",
                 "overCapacityReason":"Short trip, standing allowed"}"""
                .formatted(s.classId, sectionC, vanRoute, vanStops.getFirst(), today))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allocated").value(2));
        api.get("/api/transport/allocations?routeId=" + vanRoute, s.token())
                .andExpect(jsonPath("$[*].overCapacityReason", contains("Short trip, standing allowed",
                        "Short trip, standing allowed")));
        api.get("/api/audit-events?limit=200", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("transport_allocations.bulk_created")));
        api.post("/api/transport/allocations/bulk", s.manager.accessToken(), """
                {"classId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(s.classId, bus.route(), bus.stops().get(2), today))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allocated").value(0))
                .andExpect(jsonPath("$.skipped", hasSize(6)));
        api.post("/api/transport/allocations/bulk", s.principal.accessToken(), """
                {"classId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(s.classId, bus.route(), bus.stops().get(2), today))
                .andExpect(status().isForbidden());
    }
}
