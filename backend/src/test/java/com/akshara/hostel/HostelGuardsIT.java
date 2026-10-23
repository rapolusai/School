package com.akshara.hostel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** Guards found by negative testing: a hostel without a warden, capacity against beds, races and closed states. */
class HostelGuardsIT extends IntegrationTest {

    @Test
    void aHostelWithoutAWardenIsCreatedAndListed() throws Exception {
        HostelSchool s = new HostelSchool(api);
        String plain = TestApi.read(api.post("/api/hostel/hostels", s.admin.accessToken(), """
                {"name":"Plain Hostel","type":"BOYS"}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.wardenUserId").doesNotExist())
                .andExpect(jsonPath("$.wardenName").doesNotExist()), "$.id");
        api.get("/api/hostel/hostels/" + plain, s.admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.hostel.name").value("Plain Hostel"));
        api.get("/api/hostel/hostels", s.admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        // Taking the warden off an existing hostel works too.
        api.put("/api/hostel/hostels/" + s.girls, s.admin.accessToken(), """
                {"name":"Girls Hostel","type":"GIRLS"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.wardenName").doesNotExist());
    }

    @Test
    void theCapacityCannotFallBelowTheBedsSoARoomIsNeverOverAllotted() throws Exception {
        HostelSchool s = new HostelSchool(api);
        // Room 101 has capacity 2 and beds A and B, both empty.
        api.put("/api/hostel/hostels/" + s.boys + "/rooms/" + s.room101, s.admin.accessToken(), """
                {"floor":1,"number":"101","capacity":1,"type":"SINGLE"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.capacity").exists());
        api.put("/api/hostel/hostels/" + s.boys + "/rooms/" + s.room101, s.admin.accessToken(), """
                {"floor":1,"number":"101","capacity":3,"type":"DOUBLE"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(3));
        s.allot(s.asha, s.bedA);
        s.allot(s.bala, s.bedB);
        api.get("/api/hostel/hostels/" + s.boys + "/occupancy", s.admin.accessToken())
                .andExpect(jsonPath("$.rooms[0].capacity").value(3))
                .andExpect(jsonPath("$.occupied").value(2));
    }

    @Test
    void parallelIdenticalPassesMakeOnePass() throws Exception {
        HostelSchool s = new HostelSchool(api);
        s.allot(s.asha, s.bedA);
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                Callable<Integer> raise = () -> {
                    start.await();
                    return api.post("/api/hostel/passes", s.warden.accessToken(), """
                            {"studentId":"%s","kind":"OUTING","fromDate":"%s","reason":"Market"}"""
                            .formatted(s.asha, HostelSchool.TODAY.plusDays(3))).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(raise));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(c -> c == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        api.get("/api/hostel/passes", s.warden.accessToken()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void twoStudentsAllottedToOneBedAtOnceGetOneBedAndASpecificConflict() throws Exception {
        HostelSchool s = new HostelSchool(api);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 4; round++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<org.springframework.mock.web.MockHttpServletResponse>> results = new ArrayList<>();
                for (String student : List.of(s.asha, s.bala)) {
                    Callable<org.springframework.mock.web.MockHttpServletResponse> allot = () -> {
                        start.await();
                        return api.post("/api/hostel/allotments", s.admin.accessToken(), """
                                {"studentId":"%s","bedId":"%s","fromDate":"%s","guardianConsent":true}"""
                                .formatted(student, s.bedA, HostelSchool.TODAY)).andReturn().getResponse();
                    };
                    results.add(pool.submit(allot));
                }
                start.countDown();
                String winner = null;
                int conflicts = 0;
                for (Future<org.springframework.mock.web.MockHttpServletResponse> f : results) {
                    org.springframework.mock.web.MockHttpServletResponse response = f.get();
                    String body = response.getContentAsString();
                    if (response.getStatus() == 201) {
                        winner = com.jayway.jsonpath.JsonPath.read(body, "$.id");
                    } else {
                        assertThat(response.getStatus()).isEqualTo(409);
                        assertThat((String) com.jayway.jsonpath.JsonPath.read(body, "$.title")).isEqualTo("Bed taken");
                        assertThat((String) com.jayway.jsonpath.JsonPath.read(body, "$.detail")).startsWith("Bed ");
                        conflicts++;
                    }
                }
                assertThat(winner).isNotNull();
                assertThat(conflicts).isEqualTo(1);
                api.post("/api/hostel/allotments/" + winner + "/vacate", s.admin.accessToken(), """
                        {"reason":"Next round"}""").andExpect(status().isOk());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anApprovedPassCannotBeRefusedAndAResolvedComplaintStaysResolved() throws Exception {
        HostelSchool s = new HostelSchool(api);
        s.allot(s.asha, s.bedA);
        String pass = s.requestOuting();
        api.post("/api/hostel/passes/" + pass + "/approve", s.warden.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDecide").value(false))
                .andExpect(jsonPath("$.canMarkOut").value(true));
        api.post("/api/hostel/passes/" + pass + "/reject", s.warden.accessToken(), """
                {"note":"Changed my mind"}""").andExpect(status().isConflict());
        api.get("/api/hostel/passes/" + pass, s.warden.accessToken())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        String complaint = TestApi.read(api.post("/api/hostel/complaints", s.warden.accessToken(), """
                {"roomId":"%s","category":"PLUMBING","description":"Tap leaks"}""".formatted(s.room101))
                .andExpect(status().isCreated()), "$.id");
        api.post("/api/hostel/complaints/" + complaint + "/status", s.warden.accessToken(), """
                {"status":"RESOLVED","note":"Washer replaced"}""").andExpect(status().isOk());
        for (String next : List.of("OPEN", "IN_PROGRESS")) {
            api.post("/api/hostel/complaints/" + complaint + "/status", s.warden.accessToken(), """
                    {"status":"%s","note":"Again"}""".formatted(next)).andExpect(status().isConflict());
        }
        api.get("/api/hostel/complaints?status=RESOLVED", s.warden.accessToken())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
