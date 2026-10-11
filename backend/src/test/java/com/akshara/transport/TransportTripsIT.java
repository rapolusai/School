package com.akshara.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;
import com.akshara.transport.TransportSchool.Bus;
import com.jayway.jsonpath.JsonPath;

/**
 * Trips run from the attendant's phone: who may run them, marking students, positions moving the bus along its stops,
 * delays, the parents' messages, the live board and what families see.
 */
@RecordApplicationEvents
class TransportTripsIT extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    /** Route 1 with Bala at the first stop, Asha at the second and Chitra at the third, from today. */
    private Bus riders(TransportSchool s) throws Exception {
        Bus bus = s.bus();
        s.allocate(s.bala, bus.route(), bus.stops().get(0), TransportSchool.today());
        s.allocate(s.asha, bus.route(), bus.stops().get(1), TransportSchool.today());
        s.allocate(s.chitra, bus.route(), bus.stops().get(2), TransportSchool.today());
        return bus;
    }

    private String start(Session session, String route, String kind) throws Exception {
        return TestApi.read(api.post("/api/transport/routes/" + route + "/trips", session.accessToken(), """
                {"kind":"%s"}""".formatted(kind)).andExpect(status().isCreated()), "$.id");
    }

    private String position(int stop) {
        return """
                {"latitude":%s,"longitude":%s,"accuracyM":15}""".formatted(TransportSchool.POINTS[stop][0],
                TransportSchool.POINTS[stop][1]);
    }

    /** The student's transport messages as "template status", oldest first. */
    private List<String> messages(TransportSchool s, String studentId) throws Exception {
        String body = api.get("/api/messages?size=100&relatedId=" + studentId, s.admin.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> items = JsonPath.read(body, "$.items[?(@.templateKey =~ /transport\\..*/)]");
        List<String> result = new ArrayList<>();
        for (Map<String, Object> m : items.reversed()) {
            result.add(m.get("templateKey") + " " + m.get("status"));
        }
        return result;
    }

    @Test
    void onlyTheRoutesCrewAndManagersRunItsTrips() throws Exception {
        TransportSchool s = new TransportSchool(api);
        Bus bus = riders(s);
        String other = s.attendantCrew(s.otherAttendantUser);
        String route2 = TestApi.read(api.post("/api/transport/routes", s.token(), """
                {"name":"Route 2","attendantId":"%s","runsAfternoon":false}""".formatted(other))
                .andExpect(status().isCreated()), "$.id");
        api.get("/api/transport/my-trips", s.attendant.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[*].name", contains("Route 1")))
                .andExpect(jsonPath("$.routes[0].role").value("ATTENDANT"))
                .andExpect(jsonPath("$.routes[0].vehicleRegistrationNo").value("TS09 UB 4410"))
                .andExpect(jsonPath("$.routes[0].students").value(3))
                .andExpect(jsonPath("$.routes[0].morning").value(nullValue()));
        api.get("/api/transport/my-trips", s.driver.accessToken())
                .andExpect(jsonPath("$.routes[*].role", contains("DRIVER")));
        api.get("/api/transport/my-trips", s.otherAttendant.accessToken())
                .andExpect(jsonPath("$.routes[*].name", contains("Route 2")));
        api.get("/api/transport/my-trips", s.manager.accessToken())
                .andExpect(jsonPath("$.routes[*].name", contains("Route 1", "Route 2")))
                .andExpect(jsonPath("$.routes[*].role", contains("MANAGER", "MANAGER")));
        // Another route's attendant does not see this route at all.
        api.get("/api/transport/routes/" + bus.route() + "/trips/today?kind=MORNING",
                s.otherAttendant.accessToken()).andExpect(status().isNotFound());
        api.post("/api/transport/routes/" + bus.route() + "/trips", s.otherAttendant.accessToken(), """
                {"kind":"MORNING"}""").andExpect(status().isNotFound());
        api.get("/api/transport/routes/" + bus.route() + "/trips/today?kind=MORNING", s.attendant.accessToken())
                .andExpect(status().isNoContent());
        String trip = TestApi.read(api.post("/api/transport/routes/" + bus.route() + "/trips",
                s.attendant.accessToken(), """
                {"kind":"MORNING"}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.kind").value("MORNING"))
                .andExpect(jsonPath("$.startedByName").value("Anita Attendant"))
                .andExpect(jsonPath("$.vehicleRegistrationNo").value("TS09 UB 4410"))
                .andExpect(jsonPath("$.counts.total").value(3))
                .andExpect(jsonPath("$.counts.pending").value(3))
                .andExpect(jsonPath("$.stops[*].name", contains("Gate Stop", "Market Stop", "Temple Stop")))
                .andExpect(jsonPath("$.stops[1].students[*].name", contains("Asha Rao")))
                .andExpect(jsonPath("$.nextStop.name").value("Gate Stop"))
                .andExpect(jsonPath("$.position").value(nullValue())), "$.id");
        api.post("/api/transport/routes/" + bus.route() + "/trips", s.driver.accessToken(), """
                {"kind":"MORNING"}""").andExpect(status().isConflict());
        api.get("/api/transport/routes/" + bus.route() + "/trips/today?kind=MORNING", s.driver.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(trip));
        api.get("/api/transport/my-trips", s.attendant.accessToken())
                .andExpect(jsonPath("$.routes[0].morning.id").value(trip))
                .andExpect(jsonPath("$.routes[0].morning.status").value("RUNNING"));
        api.get("/api/transport/trips/" + trip, s.otherAttendant.accessToken()).andExpect(status().isNotFound());
        api.put("/api/transport/trips/" + trip + "/students/" + s.asha, s.otherAttendant.accessToken(), """
                {"status":"BOARDED"}""").andExpect(status().isNotFound());
        api.post("/api/transport/trips/" + trip + "/positions", s.otherAttendant.accessToken(), position(0))
                .andExpect(status().isNotFound());
        api.get("/api/transport/trips/" + trip, s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/transport/trips/" + trip, s.parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/transport/trips/" + trip, s.manager.accessToken()).andExpect(status().isOk());
        TransportSchool elsewhere = new TransportSchool(api);
        api.get("/api/transport/trips/" + trip, elsewhere.manager.accessToken()).andExpect(status().isNotFound());
        api.post("/api/transport/trips/" + trip + "/end", elsewhere.admin.accessToken(), null)
                .andExpect(status().isNotFound());
        // Route 2 has no afternoon trip; an inactive route runs none.
        api.post("/api/transport/routes/" + route2 + "/trips", s.otherAttendant.accessToken(), """
                {"kind":"AFTERNOON"}""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.kind").exists());
        api.put("/api/transport/routes/" + route2, s.token(), """
                {"name":"Route 2","attendantId":"%s","runsAfternoon":false,"active":false}""".formatted(other))
                .andExpect(status().isOk());
        api.post("/api/transport/routes/" + route2 + "/trips", s.otherAttendant.accessToken(), """
                {"kind":"MORNING"}""").andExpect(status().isConflict());
        api.post("/api/transport/routes/" + route2 + "/trips", s.manager.accessToken(), """
                {"kind":"MORNING"}""").andExpect(status().isConflict());
        api.get("/api/audit-events?limit=200", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("transport_trip.started")));
    }

    @Test
    void theMorningTripMarksStudentsAndTellsTheirParents() throws Exception {
        TransportSchool s = new TransportSchool(api);
        Bus bus = riders(s);
        String trip = start(s.attendant, bus.route(), "MORNING");
        String marks = "/api/transport/trips/" + trip + "/students/";
        api.put(marks + s.bala, s.attendant.accessToken(), """
                {"status":"BOARDED"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.boarded").value(1))
                .andExpect(jsonPath("$.stops[0].students[0].status").value("BOARDED"))
                // A pickup at the first stop means the bus is there.
                .andExpect(jsonPath("$.reachedStop.name").value("Gate Stop"))
                .andExpect(jsonPath("$.nextStop.name").value("Market Stop"));
        assertThat(messages(s, s.bala)).containsExactly("transport.boarded QUEUED");
        // Approaching alerts are off by default.
        assertThat(messages(s, s.asha)).isEmpty();
        // The same mark again changes nothing; a correction cancels the earlier message.
        api.put(marks + s.bala, s.attendant.accessToken(), """
                {"status":"BOARDED"}""").andExpect(status().isOk());
        assertThat(messages(s, s.bala)).containsExactly("transport.boarded QUEUED");
        api.put(marks + s.bala, s.driver.accessToken(), """
                {"status":"ABSENT"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.boarded").value(0))
                .andExpect(jsonPath("$.counts.absent").value(1));
        assertThat(messages(s, s.bala)).containsExactly("transport.boarded SKIPPED");
        api.put(marks + s.asha, s.attendant.accessToken(), """
                {"status":"DROPPED"}""").andExpect(status().isBadRequest());
        api.put(marks + UUID.randomUUID(), s.attendant.accessToken(), """
                {"status":"BOARDED"}""").andExpect(status().isNotFound());
        api.put(marks + s.asha, s.attendant.accessToken(), """
                {"status":"LATE"}""").andExpect(status().isBadRequest());
        // With approaching alerts on, reaching a stop tells the families at the next one.
        api.put("/api/transport/settings", s.token(), """
                {"boardedAlerts":true,"droppedAlerts":true,"approachingAlerts":true,"delayAlerts":true}""")
                .andExpect(status().isOk());
        api.post("/api/transport/trips/" + trip + "/positions", s.attendant.accessToken(), position(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachedStop.name").value("Market Stop"))
                .andExpect(jsonPath("$.nextStop.name").value("Temple Stop"))
                .andExpect(jsonPath("$.position.source").value("PHONE"))
                .andExpect(jsonPath("$.position.stale").value(false))
                .andExpect(jsonPath("$.position.minutesAgo").value(0))
                .andExpect(jsonPath("$.position.nearestStop.name").value("Market Stop"))
                .andExpect(jsonPath("$.position.nearestMeters").value(0));
        assertThat(messages(s, s.chitra)).containsExactly("transport.approaching QUEUED");
        // The same stop again does not repeat it.
        api.post("/api/transport/trips/" + trip + "/positions", s.attendant.accessToken(), position(1))
                .andExpect(status().isOk());
        assertThat(messages(s, s.chitra)).containsExactly("transport.approaching QUEUED");
        // A delay goes to the families still waiting: Asha and Chitra, not Bala (absent).
        api.post("/api/transport/trips/" + trip + "/delay", s.attendant.accessToken(), """
                {"minutes":15,"reason":"Traffic at the junction"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.delay.minutes").value(15))
                .andExpect(jsonPath("$.delay.reason").value("Traffic at the junction"));
        api.post("/api/transport/trips/" + trip + "/delay", s.attendant.accessToken(), """
                {"minutes":0,"reason":""}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.minutes").exists())
                .andExpect(jsonPath("$.errors.reason").exists());
        assertThat(messages(s, s.asha)).containsExactly("transport.delayed QUEUED");
        assertThat(messages(s, s.chitra)).containsExactly("transport.approaching QUEUED", "transport.delayed QUEUED");
        assertThat(messages(s, s.bala)).containsExactly("transport.boarded SKIPPED");
        String delayed = TestApi.read(api.get("/api/messages?relatedId=" + s.asha, s.admin.accessToken()),
                "$.items[0].id");
        api.get("/api/messages/" + delayed, s.admin.accessToken())
                .andExpect(jsonPath("$.recipientName").value("Lata Rao"))
                .andExpect(jsonPath("$.body").value("Dear Lata Rao, the Test School " + s.school.code()
                        + " bus (Route 1) for Asha Rao is running about 15 minutes late: Traffic at the junction."));
        api.put(marks + s.asha, s.attendant.accessToken(), """
                {"status":"BOARDED"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.boarded").value(1))
                .andExpect(jsonPath("$.reachedStop.name").value("Market Stop"));
        assertThat(messages(s, s.asha)).containsExactly("transport.delayed QUEUED", "transport.boarded QUEUED");
        // The live board shows the trip to the principal.
        api.get("/api/transport/live", s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].route.name").value("Route 1"))
                .andExpect(jsonPath("$.routes[0].stops[*].name", contains("Gate Stop", "Market Stop",
                        "Temple Stop")))
                .andExpect(jsonPath("$.routes[0].trips[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.routes[0].trips[0].counts.boarded").value(1))
                .andExpect(jsonPath("$.routes[0].trips[0].counts.absent").value(1))
                .andExpect(jsonPath("$.routes[0].trips[0].delay.minutes").value(15))
                .andExpect(jsonPath("$.routes[0].trips[0].position.nearestStop.name").value("Market Stop"));
        api.post("/api/transport/trips/" + trip + "/end", s.attendant.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.endedByName").value("Anita Attendant"))
                .andExpect(jsonPath("$.nextStop").value(nullValue()));
        api.put(marks + s.chitra, s.attendant.accessToken(), """
                {"status":"BOARDED"}""").andExpect(status().isConflict());
        api.post("/api/transport/trips/" + trip + "/positions", s.attendant.accessToken(), position(2))
                .andExpect(status().isConflict());
        api.post("/api/transport/trips/" + trip + "/end", s.attendant.accessToken(), null)
                .andExpect(status().isConflict());
        // The boarding log keeps every entry, corrections included.
        api.get("/api/transport/reports/boarding", s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.boarded").value(2))
                .andExpect(jsonPath("$.absent").value(1))
                .andExpect(jsonPath("$.delays").value(1))
                .andExpect(jsonPath("$.rows[*].kind", contains("BOARDED", "ABSENT", "DELAYED", "BOARDED")))
                .andExpect(jsonPath("$.rows[0].studentName").value("Bala Iyer"))
                .andExpect(jsonPath("$.rows[0].stopName").value("Gate Stop"))
                .andExpect(jsonPath("$.rows[1].byName").value("Dev Driver"))
                .andExpect(jsonPath("$.rows[2].note").value("Traffic at the junction"));
        api.get("/api/audit-events?limit=200", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("transport_trip.started",
                        "transport_trip.student_marked", "transport_trip.delayed", "transport_trip.ended")));
        // The marks and the delay are published for other modules.
        UUID tripId = UUID.fromString(trip);
        assertThat(events.stream(TransportBoardingRecorded.class).filter(e -> e.tripId().equals(tripId))
                .map(e -> e.status() + " " + e.studentId()))
                .containsExactly("BOARDED " + s.bala, "ABSENT " + s.bala, "BOARDED " + s.asha);
        assertThat(events.stream(TransportTripDelayed.class).filter(e -> e.tripId().equals(tripId)))
                .singleElement().satisfies(e -> {
                    assertThat(e.minutes()).isEqualTo(15);
                    assertThat(e.tenantId()).isEqualTo(s.school.tenantId());
                    assertThat(e.tripKind()).isEqualTo(TripKind.MORNING);
                });
    }

    @Test
    void theAfternoonTripRunsTheStopsInReverse() throws Exception {
        TransportSchool s = new TransportSchool(api);
        Bus bus = riders(s);
        api.put("/api/transport/settings", s.token(), """
                {"boardedAlerts":false,"droppedAlerts":true,"approachingAlerts":true,"delayAlerts":false}""")
                .andExpect(status().isOk());
        String trip = TestApi.read(api.post("/api/transport/routes/" + bus.route() + "/trips",
                s.driver.accessToken(), """
                {"kind":"AFTERNOON"}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.stops[*].name", contains("Temple Stop", "Market Stop", "Gate Stop")))
                .andExpect(jsonPath("$.stops[0].time", startsWith("14:50"))), "$.id");
        String marks = "/api/transport/trips/" + trip + "/students/";
        // Boarding happens at school: no stop is reached and (with boarded alerts off) nobody is told.
        for (String student : List.of(s.asha, s.bala, s.chitra)) {
            api.put(marks + student, s.driver.accessToken(), """
                    {"status":"BOARDED"}""").andExpect(status().isOk())
                    .andExpect(jsonPath("$.reachedStop").value(nullValue()));
        }
        assertThat(messages(s, s.asha)).isEmpty();
        // Dropping Asha at Market Stop (passing Temple Stop) tells Bala's family at Gate Stop the bus is coming.
        api.put(marks + s.asha, s.driver.accessToken(), """
                {"status":"DROPPED"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.dropped").value(1))
                .andExpect(jsonPath("$.reachedStop.name").value("Market Stop"))
                .andExpect(jsonPath("$.nextStop.name").value("Gate Stop"));
        assertThat(messages(s, s.asha)).containsExactly("transport.dropped QUEUED");
        assertThat(messages(s, s.bala)).containsExactly("transport.approaching QUEUED");
        assertThat(messages(s, s.chitra)).isEmpty();
        // Delay alerts are off: the delay is logged, nobody is told.
        api.post("/api/transport/trips/" + trip + "/delay", s.driver.accessToken(), """
                {"minutes":10,"reason":"Rain"}""").andExpect(status().isOk());
        assertThat(messages(s, s.bala)).containsExactly("transport.approaching QUEUED");
        // A drop corrected to absent cancels the dropped message.
        api.put(marks + s.asha, s.driver.accessToken(), """
                {"status":"ABSENT"}""").andExpect(status().isOk());
        assertThat(messages(s, s.asha)).containsExactly("transport.dropped SKIPPED");
        api.get("/api/transport/routes/" + bus.route() + "/trips/today?kind=AFTERNOON", s.attendant.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(trip));
        api.get("/api/transport/routes/" + bus.route() + "/trips/today?kind=MORNING", s.attendant.accessToken())
                .andExpect(status().isNoContent());
    }

    @Test
    void positionsMustBeRecentAndNotTooFrequent() throws Exception {
        TransportSchool s = new TransportSchool(api);
        Bus bus = riders(s);
        String trip = start(s.attendant, bus.route(), "MORNING");
        String path = "/api/transport/trips/" + trip + "/positions";
        api.post(path, s.attendant.accessToken(), """
                {"latitude":17.41,"longitude":78.44,"recordedAt":"%s"}"""
                .formatted(Instant.now().minus(2, ChronoUnit.HOURS)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.recordedAt").exists());
        api.post(path, s.attendant.accessToken(), """
                {"latitude":95,"longitude":78.44}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.latitude").exists());
        // Ten minutes old: kept, and shown as no recent position.
        api.post(path, s.attendant.accessToken(), """
                {"latitude":17.4155,"longitude":78.44,"recordedAt":"%s"}"""
                .formatted(Instant.now().minus(10, ChronoUnit.MINUTES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position.stale").value(true))
                .andExpect(jsonPath("$.position.minutesAgo", greaterThanOrEqualTo(10)))
                .andExpect(jsonPath("$.reachedStop").value(nullValue()));
        // An older position than the latest does not move the bus back in time.
        api.post(path, s.attendant.accessToken(), """
                {"latitude":17.41,"longitude":78.44,"recordedAt":"%s"}"""
                .formatted(Instant.now().minus(20, ChronoUnit.MINUTES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position.latitude").value(17.4155));
        api.post(path, s.attendant.accessToken(), position(0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.position.stale").value(false))
                .andExpect(jsonPath("$.reachedStop.name").value("Gate Stop"));
        // The vehicle keeps the phone's latest position too.
        api.get("/api/transport/vehicles/" + bus.vehicle(), s.token())
                .andExpect(jsonPath("$.lastPositionAt").exists());
        // At most 12 positions a minute from one trip.
        int accepted = 4;
        while (accepted < 12) {
            api.post(path, s.attendant.accessToken(), position(0)).andExpect(status().isOk());
            accepted++;
        }
        api.post(path, s.attendant.accessToken(), position(0)).andExpect(status().isTooManyRequests());
    }

    @Test
    void familiesFollowTheirOwnChildsBus() throws Exception {
        TransportSchool s = new TransportSchool(api);
        api.get("/api/me/transport", s.parent.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.children[*].name", contains("Asha Rao")))
                .andExpect(jsonPath("$.children[0].allocation").value(nullValue()))
                .andExpect(jsonPath("$.children[0].trips", empty()));
        Bus bus = riders(s);
        api.get("/api/me/transport", s.parent.accessToken())
                .andExpect(jsonPath("$.children[0].classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.children[0].allocation.routeName").value("Route 1"))
                .andExpect(jsonPath("$.children[0].allocation.stopName").value("Market Stop"))
                .andExpect(jsonPath("$.children[0].allocation.landmark").value("Near the post office"))
                .andExpect(jsonPath("$.children[0].allocation.pickupTime", startsWith("07:20")))
                .andExpect(jsonPath("$.children[0].allocation.vehicleRegistrationNo").value("TS09 UB 4410"))
                .andExpect(jsonPath("$.children[0].trips[*].kind", contains("MORNING", "AFTERNOON")))
                .andExpect(jsonPath("$.children[0].trips[*].status", contains("NOT_STARTED", "NOT_STARTED")))
                .andExpect(jsonPath("$.children[0].trips[0].stops[*].mine", contains(false, true, false)))
                .andExpect(jsonPath("$.children[0].trips[1].stops[*].name", contains("Temple Stop", "Market Stop",
                        "Gate Stop")));
        String trip = start(s.attendant, bus.route(), "MORNING");
        api.get("/api/me/transport", s.parent.accessToken())
                .andExpect(jsonPath("$.children[0].trips[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.children[0].trips[0].stopsAway").value(2))
                .andExpect(jsonPath("$.children[0].trips[0].position").value(nullValue()));
        api.post("/api/transport/trips/" + trip + "/positions", s.attendant.accessToken(), position(0))
                .andExpect(status().isOk());
        api.put("/api/transport/trips/" + trip + "/students/" + s.bala, s.attendant.accessToken(), """
                {"status":"BOARDED"}""").andExpect(status().isOk());
        String body = api.get("/api/me/transport", s.parent.accessToken())
                .andExpect(jsonPath("$.children[0].trips[0].stopsAway").value(1))
                .andExpect(jsonPath("$.children[0].trips[0].nextStop.name").value("Market Stop"))
                .andExpect(jsonPath("$.children[0].trips[0].position.nearestStop.name").value("Gate Stop"))
                .andExpect(jsonPath("$.children[0].trips[0].position.stale").value(false))
                .andExpect(jsonPath("$.children[0].trips[0].childStatus").value(nullValue()))
                // Other children's marks are not shown.
                .andExpect(jsonPath("$.children[0].trips[0].events", empty()))
                .andReturn().getResponse().getContentAsString();
        // Families never get the bus's coordinates.
        assertThat(body).doesNotContain("latitude").doesNotContain("17.41");
        api.post("/api/transport/trips/" + trip + "/delay", s.attendant.accessToken(), """
                {"minutes":5,"reason":"Flat tyre"}""").andExpect(status().isOk());
        api.put("/api/transport/trips/" + trip + "/students/" + s.asha, s.attendant.accessToken(), """
                {"status":"BOARDED"}""").andExpect(status().isOk());
        api.get("/api/me/transport", s.parent.accessToken())
                .andExpect(jsonPath("$.children[0].trips[0].childStatus").value("BOARDED"))
                .andExpect(jsonPath("$.children[0].trips[0].stopsAway").value(0))
                .andExpect(jsonPath("$.children[0].trips[0].delay.minutes").value(5))
                .andExpect(jsonPath("$.children[0].trips[0].events[*].kind", contains("DELAYED", "BOARDED")))
                .andExpect(jsonPath("$.children[0].trips[0].events[1].stopName").value("Market Stop"));
        // Parents cannot open the trip itself, and another family sees only their own children.
        api.get("/api/transport/trips/" + trip, s.parent.accessToken()).andExpect(status().isForbidden());
        TransportSchool elsewhere = new TransportSchool(api);
        api.get("/api/me/transport", elsewhere.parent.accessToken())
                .andExpect(jsonPath("$.children[*].name", contains("Asha Rao")))
                .andExpect(jsonPath("$.children[0].allocation").value(nullValue()));
        // A student's place starting later is shown before the first day, with no trips yet.
        String later = TestApi.read(api.get("/api/transport/allocations?q=asha", s.token()), "$[0].id");
        api.post("/api/transport/allocations/" + later + "/end", s.token(), """
                {"endDate":"%s"}""".formatted(TransportSchool.today())).andExpect(status().isOk());
        s.allocate(s.asha, bus.route(), bus.stops().get(2), TransportSchool.today().plusDays(7));
        api.get("/api/me/transport", s.parent.accessToken())
                .andExpect(jsonPath("$.children[0].allocation.stopName").value("Market Stop"));
        // (Today is still covered by the ended allocation, which ends today.)
        api.get("/api/transport/allocations?status=ACTIVE&q=asha", s.token())
                .andExpect(jsonPath("$[*].stop.name", containsInAnyOrder("Temple Stop")));
    }
}
