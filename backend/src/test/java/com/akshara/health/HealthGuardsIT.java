package com.akshara.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;

/** Guards found by negative testing: parallel visits sharing a medicine, and impossible dates. */
class HealthGuardsIT extends IntegrationTest {

    /** Fires the visits at once, each giving {@code each} tablets of the medicine; returns the status codes. */
    private List<Integer> parallelVisits(HealthSchool s, String medicine, int visits, int each) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(visits);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < visits; i++) {
                Callable<Integer> visit = () -> {
                    start.await();
                    return api.post("/api/health/infirmary/visits", s.nurse.accessToken(), """
                            {"personKind":"STUDENT","studentId":"%s","complaint":"Headache",
                             "medicines":[{"medicineId":"%s","quantity":%d}]}""".formatted(s.asha, medicine, each))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(visit));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelVisitsSharingAMedicineAllSucceedWhileTheStockLasts() throws Exception {
        HealthSchool s = new HealthSchool(api);
        String plenty = s.medicine("Paracetamol", 10);
        assertThat(parallelVisits(s, plenty, 5, 2)).containsOnly(201);
        api.get("/api/health/infirmary/medicines", s.nurse.accessToken())
                .andExpect(jsonPath("$[0].name").value("Paracetamol"))
                .andExpect(jsonPath("$[0].quantity").value(0));
        // Short stock: the visits that fit succeed, the rest are told it is short (never a server error).
        String scarce = s.medicine("Zinc", 5);
        List<Integer> statuses = parallelVisits(s, scarce, 5, 2);
        assertThat(statuses).containsOnly(201, 400);
        assertThat(statuses).filteredOn(c -> c == 201).hasSize(2);
        api.get("/api/health/infirmary/medicines", s.nurse.accessToken())
                .andExpect(jsonPath("$[1].name").value("Zinc"))
                .andExpect(jsonPath("$[1].quantity").value(1));
    }

    @Test
    void healthDatesBelongToTheStudentsLifeAndThePast() throws Exception {
        HealthSchool s = new HealthSchool(api);
        String nurse = s.nurse.accessToken();
        String admin = s.admin.accessToken();
        LocalDate today = HealthText.today();
        // Asha was born on 2016-04-12.
        for (String date : List.of("2099-01-01", "1900-01-01", "2016-04-11")) {
            api.post("/api/health/students/" + s.asha + "/record/items", nurse, """
                    {"kind":"VACCINATION","name":"MMR","itemDate":"%s"}""".formatted(date))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.itemDate").exists());
        }
        api.post("/api/health/students/" + s.asha + "/record/items", nurse, """
                {"kind":"VACCINATION","name":"MMR","itemDate":"2017-05-01"}""").andExpect(status().isCreated());
        for (String date : List.of("1950-01-01", "1899-12-31", "2016-01-01")) {
            api.post("/api/health/students/" + s.asha + "/record/growth", nurse, """
                    {"measuredOn":"%s","heightCm":120}""".formatted(date))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.measuredOn").exists());
        }
        api.post("/api/health/students/" + s.asha + "/record/growth", nurse, """
                {"measuredOn":"%s","heightCm":120}""".formatted(today)).andExpect(status().isCreated());
        // Safety audits: not in the future, not before 1900.
        for (String date : List.of("2099-01-01", "1850-01-01")) {
            api.post("/api/health/safety/audits", admin, """
                    {"area":"WATER","auditDate":"%s","finding":"Filter","severity":"LOW"}""".formatted(date))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.auditDate").exists());
        }
        api.post("/api/health/safety/audits", admin, """
                {"area":"WATER","auditDate":"%s","finding":"Filter","severity":"LOW"}""".formatted(today))
                .andExpect(status().isCreated());
        // Staff verification: cleared and requested on real past days.
        api.put("/api/health/staff/" + s.teacherId + "/verification", admin, """
                {"status":"CLEARED","clearedOn":"2099-01-01"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.clearedOn").exists());
        api.put("/api/health/staff/" + s.teacherId + "/verification", admin, """
                {"status":"REQUESTED","requestedOn":"%s"}""".formatted(today.plusDays(3)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.requestedOn").exists());
        api.put("/api/health/staff/" + s.teacherId + "/verification", admin, """
                {"status":"CLEARED","clearedOn":"%s"}""".formatted(today)).andExpect(status().isOk());
    }
}
