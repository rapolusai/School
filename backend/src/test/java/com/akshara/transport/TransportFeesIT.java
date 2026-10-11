package com.akshara.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;
import com.akshara.transport.TransportSchool.Bus;
import com.jayway.jsonpath.JsonPath;

/**
 * The bus fee in students' dues (through the fees module's per-student charges): the zone's annual fee split over the
 * structure's instalments that the allocation covers, re-priced when the allocation or the zone changes, and the
 * transport fee collection report by route.
 */
class TransportFeesIT extends IntegrationTest {

    @Test
    void theBusFeeIsPartOfTheDuesWhileTheStudentTravels() throws Exception {
        TransportSchool s = new TransportSchool(api);
        FeeFixtures fees = new FeeFixtures(api, mvc);
        Heads heads = fees.defaultHeads(s.admin);
        fees.publishedStructure(s.admin, s.yearId, s.classId, heads);
        LocalDate today = TransportSchool.today();
        Bus bus = s.bus();
        // Asha has travelled since before the first quarter: ₹12,000 a year is ₹3,000 a quarter.
        String asha = s.allocate(s.asha, bus.route(), bus.stops().get(1), today.minusDays(90));
        assertThat(transport(s.admin, s.asha)).containsExactly(3_000_00L, 3_000_00L, 3_000_00L, 3_000_00L);
        api.get("/api/fees/students/" + s.asha, s.admin.accessToken())
                .andExpect(jsonPath("$.instalments[0].grossPaise").value(14_500_00))
                .andExpect(jsonPath("$.totals.grossPaise").value(58_000_00));
        // Bala starts today: only the quarters due from today on.
        s.allocate(s.bala, bus.route(), bus.stops().get(0), today);
        assertThat(transport(s.admin, s.bala)).containsExactly(0L, 0L, 3_000_00L, 3_000_00L);
        // Chitra is not on a bus.
        assertThat(transport(s.admin, s.chitra)).containsExactly(0L, 0L, 0L, 0L);
        // Asha pays the first quarter, then leaves the bus before the second: the unpaid bus dues go, the paid stay.
        fees.cash(s.admin, s.asha, 14_500_00);
        api.post("/api/transport/allocations/" + asha + "/end", s.token(), """
                {"endDate":"%s","reason":"Walks to school now"}""".formatted(today.minusDays(30)))
                .andExpect(status().isOk());
        assertThat(transport(s.admin, s.asha)).containsExactly(3_000_00L, 0L, 0L, 0L);
        api.get("/api/fees/students/" + s.asha, s.admin.accessToken())
                .andExpect(jsonPath("$.instalments[0].heads[?(@.headName == 'Transport fee')].paidPaise",
                        contains(3_000_00)))
                .andExpect(jsonPath("$.totals.grossPaise").value(46_000_00 + 3_000_00));
        // A dearer zone re-prices the students on its stops.
        api.put("/api/transport/fee-zones/" + bus.zone(), s.token(), """
                {"name":"Zone A","minKm":0,"annualFeePaise":1600000}""").andExpect(status().isOk());
        assertThat(transport(s.admin, s.bala)).containsExactly(0L, 0L, 4_000_00L, 4_000_00L);
        // So does moving their stop to another zone.
        String far = s.zone("Zone C", 20_000_00);
        api.put("/api/transport/routes/" + bus.route() + "/stops", s.token(), """
                {"stops":[{"id":"%s","name":"Gate Stop","feeZoneId":"%s"},{"id":"%s","name":"Market Stop",
                 "feeZoneId":"%s"},{"id":"%s","name":"Temple Stop","feeZoneId":"%s"}]}""".formatted(
                bus.stops().get(0), far, bus.stops().get(1), bus.zone(), bus.stops().get(2), bus.zone()))
                .andExpect(status().isOk());
        assertThat(transport(s.admin, s.bala)).containsExactly(0L, 0L, 5_000_00L, 5_000_00L);
        // And moving the student to a stop in another zone.
        String bala = TestApi.read(api.get("/api/transport/allocations?q=bala", s.token()), "$[0].id");
        api.put("/api/transport/allocations/" + bala, s.token(), """
                {"stopId":"%s"}""".formatted(bus.stops().get(2))).andExpect(status().isOk());
        assertThat(transport(s.admin, s.bala)).containsExactly(0L, 0L, 4_000_00L, 4_000_00L);
        // A student admitted after the structure was published gets the bus fee with their first dues.
        String gita = new SchoolFixtures(api).student(s.admin, s.section, "TR-7", "Gita", "Menon", "Leela Menon",
                "9876501007");
        s.allocate(gita, bus.route(), bus.stops().get(1), today.minusDays(30));
        assertThat(transport(s.admin, gita)).containsExactly(0L, 4_000_00L, 4_000_00L, 4_000_00L);
    }

    @Test
    void theFeeReportShowsWhatEachRouteBilledAndCollected() throws Exception {
        TransportSchool s = new TransportSchool(api);
        FeeFixtures fees = new FeeFixtures(api, mvc);
        Heads heads = fees.defaultHeads(s.admin);
        fees.publishedStructure(s.admin, s.yearId, s.classId, heads);
        LocalDate today = TransportSchool.today();
        Bus bus = s.bus();
        String route2 = s.route("Route 2", null, null, null);
        List<String> stops2 = s.stops(route2, bus.zone());
        s.allocate(s.asha, bus.route(), bus.stops().get(1), today.minusDays(90));
        s.allocate(s.bala, route2, stops2.getFirst(), today);
        fees.cash(s.admin, s.asha, 14_500_00);
        // Route 1: Asha's ₹12,000 with ₹3,000 paid, ₹3,000 of it overdue (the second quarter).
        api.get("/api/transport/reports/fees", s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.academicYearName").value("2026-27"))
                .andExpect(jsonPath("$.routes[0].routeName").value("Route 1"))
                .andExpect(jsonPath("$.routes[0].students").value(1))
                .andExpect(jsonPath("$.routes[0].billedPaise").value(12_000_00))
                .andExpect(jsonPath("$.routes[0].collectedPaise").value(3_000_00))
                .andExpect(jsonPath("$.routes[0].outstandingPaise").value(9_000_00))
                .andExpect(jsonPath("$.routes[0].overduePaise").value(3_000_00))
                .andExpect(jsonPath("$.routes[1].routeName").value("Route 2"))
                .andExpect(jsonPath("$.routes[1].billedPaise").value(6_000_00))
                .andExpect(jsonPath("$.routes[1].overduePaise").value(0))
                .andExpect(jsonPath("$.totals.students").value(2))
                .andExpect(jsonPath("$.totals.billedPaise").value(18_000_00))
                .andExpect(jsonPath("$.totals.collectedPaise").value(3_000_00));
        api.get("/api/transport/reports/fees?routeId=" + route2, s.token())
                .andExpect(jsonPath("$.routes[*].routeName", contains("Route 2")))
                .andExpect(jsonPath("$.totals.billedPaise").value(6_000_00));
        String csv = csv("/api/transport/reports/fees.csv", s.token());
        assertThat(csv).startsWith("﻿Route,Students,Billed (Rs),Collected (Rs),Outstanding (Rs),Overdue (Rs)\r\n")
                .contains("Route 1,1,12000.00,3000.00,9000.00,3000.00\r\n")
                .contains("Route 2,1,6000.00,0.00,6000.00,0.00\r\n")
                .endsWith("Total,2,18000.00,3000.00,15000.00,3000.00\r\n");
        // Another school's year is not one of ours.
        TransportSchool other = new TransportSchool(api);
        api.get("/api/transport/reports/fees?yearId=" + s.yearId, other.token()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.yearId").exists());
        api.get("/api/transport/reports/fees", other.token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.billedPaise").value(0));
        api.get("/api/transport/reports/fees", s.teacher.accessToken()).andExpect(status().isForbidden());
    }

    /** The Transport fee of each of the student's instalments, oldest first (0 where there is none). */
    private List<Long> transport(Session session, String studentId) throws Exception {
        String body = api.get("/api/fees/students/" + studentId, session.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int count = JsonPath.<List<Object>>read(body, "$.instalments[*]").size();
        Long[] result = new Long[count];
        for (int i = 0; i < count; i++) {
            List<Number> gross = JsonPath.read(body, "$.instalments[" + i
                    + "].heads[?(@.headName == 'Transport fee')].grossPaise");
            result[i] = gross.isEmpty() ? 0L : gross.getFirst().longValue();
        }
        return List.of(result);
    }

    private String csv(String path, String token) throws Exception {
        byte[] body = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new String(body, StandardCharsets.UTF_8);
    }
}
