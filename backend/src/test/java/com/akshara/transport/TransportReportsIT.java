package com.akshara.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.transport.TransportSchool.Bus;

/** The transport reports: students by route and stop, documents expiring and the boarding log, with CSV files. */
class TransportReportsIT extends IntegrationTest {

    private ResultActions download(String path, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token));
    }

    private String csv(String path, String token) throws Exception {
        byte[] body = download(path, token).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).startsWith(0xEF, 0xBB, 0xBF);
        return new String(body, StandardCharsets.UTF_8);
    }

    @Test
    void studentsByRouteAndStopWithMaskedPhones() throws Exception {
        TransportSchool s = new TransportSchool(api);
        Bus bus = s.bus();
        LocalDate today = TransportSchool.today();
        s.allocate(s.asha, bus.route(), bus.stops().get(1), today);
        s.allocate(s.bala, bus.route(), bus.stops().get(0), today);
        String chitra = s.allocate(s.chitra, bus.route(), bus.stops().get(1), today);
        api.post("/api/transport/allocations/" + chitra + "/end", s.token(), """
                {"endDate":"%s"}""".formatted(today.plusDays(5))).andExpect(status().isOk());
        String body = api.get("/api/transport/reports/route-students", s.principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students").value(3))
                .andExpect(jsonPath("$.routes[0].name").value("Route 1"))
                .andExpect(jsonPath("$.routes[0].driverName").value("Dev Driver"))
                .andExpect(jsonPath("$.routes[0].attendantName").value("Anita Attendant"))
                .andExpect(jsonPath("$.routes[0].stops[0].students[*].name", contains("Bala Iyer")))
                .andExpect(jsonPath("$.routes[0].stops[1].students[*].name", contains("Asha Rao", "Chitra Das")))
                .andExpect(jsonPath("$.routes[0].stops[1].students[0].guardianName").value("Lata Rao"))
                .andExpect(jsonPath("$.routes[0].stops[2].students", empty()))
                .andReturn().getResponse().getContentAsString();
        // Parents' numbers are masked in the list.
        assertThat(body).doesNotContain("9876501001").doesNotContain("98765 01001");
        // A week later Chitra no longer travels.
        api.get("/api/transport/reports/route-students?date=" + today.plusDays(7) + "&routeId=" + bus.route(),
                s.token()).andExpect(jsonPath("$.students").value(2))
                .andExpect(jsonPath("$.routes[0].stops[1].students[*].name", contains("Asha Rao")));
        String file = csv("/api/transport/reports/route-students.csv", s.token());
        assertThat(file).startsWith("﻿Route,Vehicle,Stop no.,Stop,Pickup,Drop,Student,Admission no.,Class,"
                + "Parent or guardian,Phone\r\n")
                .contains("Route 1,TS09 UB 4410,2,Market Stop,07:20,15:00,Asha Rao,TR-1,Class 5 A,Lata Rao,")
                .doesNotContain("9876501001");
        assertThat(file.split("\r\n")).hasSize(4);
        api.get("/api/transport/reports/route-students", s.teacher.accessToken()).andExpect(status().isForbidden());
        download("/api/transport/reports/route-students.csv", s.attendant.accessToken())
                .andExpect(status().isForbidden());
        download("/api/transport/reports/route-students.csv", s.parent.accessToken())
                .andExpect(status().isForbidden());
        // Another school's report is its own.
        TransportSchool other = new TransportSchool(api);
        api.get("/api/transport/reports/route-students", other.token()).andExpect(jsonPath("$.students").value(0))
                .andExpect(jsonPath("$.routes", empty()));
    }

    @Test
    void documentsExpiringSoonestFirst() throws Exception {
        TransportSchool s = new TransportSchool(api);
        LocalDate today = TransportSchool.today();
        s.vehicle("TS09 UB 4410", 40, today.plusDays(12));
        String expired = TestApi.read(api.post("/api/transport/vehicles", s.token(), """
                {"registrationNo":"TS09 UB 4411","type":"BUS","seats":40,"insuranceExpiry":"%s",
                 "fitnessExpiry":"%s","permitExpiry":"%s","pollutionExpiry":"%s"}"""
                .formatted(today.minusDays(4), today.plusYears(1), today.plusYears(1), today.plusDays(200)))
                .andExpect(status().isCreated()), "$.id");
        s.driverCrew(s.driverUser, today.plusDays(25));
        s.attendantCrew(s.attendantUser);
        // A retired vehicle's papers do not matter.
        api.post("/api/transport/vehicles", s.token(), """
                {"registrationNo":"TS09 ZZ 0001","type":"VAN","seats":10,"status":"RETIRED"}""")
                .andExpect(status().isCreated());
        api.get("/api/transport/reports/documents", s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.withinDays").value(30))
                .andExpect(jsonPath("$.rows[*].subject", contains("TS09 UB 4411", "TS09 UB 4410", "Dev Driver")))
                .andExpect(jsonPath("$.rows[*].document", contains("INSURANCE", "FITNESS", "LICENCE")))
                .andExpect(jsonPath("$.rows[*].state", contains("EXPIRED", "EXPIRING", "EXPIRING")))
                .andExpect(jsonPath("$.rows[*].daysLeft", contains(-4, 12, 25)))
                .andExpect(jsonPath("$.rows[0].subjectId").value(expired));
        api.get("/api/transport/reports/documents?withinDays=300", s.token())
                .andExpect(jsonPath("$.rows[*].document", contains("INSURANCE", "FITNESS", "LICENCE",
                        "POLLUTION")));
        api.get("/api/transport/reports/documents?withinDays=0", s.token())
                .andExpect(jsonPath("$.rows[*].subject", contains("TS09 UB 4411")));
        api.get("/api/transport/reports/documents?withinDays=400", s.token()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.withinDays").exists());
        String file = csv("/api/transport/reports/documents.csv", s.token());
        assertThat(file).contains("VEHICLE,TS09 UB 4411,INSURANCE," + today.minusDays(4) + ",EXPIRED,-4\r\n")
                .contains("DRIVER,Dev Driver,LICENCE," + today.plusDays(25) + ",EXPIRING,25\r\n");
        api.get("/api/transport/reports/documents", s.teacher.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void theBoardingLogOfADay() throws Exception {
        TransportSchool s = new TransportSchool(api);
        Bus bus = s.bus();
        LocalDate today = TransportSchool.today();
        s.allocate(s.asha, bus.route(), bus.stops().get(1), today);
        s.allocate(s.bala, bus.route(), bus.stops().get(0), today);
        api.get("/api/transport/reports/boarding", s.token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", empty()));
        String trip = TestApi.read(api.post("/api/transport/routes/" + bus.route() + "/trips",
                s.attendant.accessToken(), """
                {"kind":"MORNING"}""").andExpect(status().isCreated()), "$.id");
        api.put("/api/transport/trips/" + trip + "/students/" + s.bala, s.attendant.accessToken(), """
                {"status":"BOARDED"}""").andExpect(status().isOk());
        api.put("/api/transport/trips/" + trip + "/students/" + s.asha, s.attendant.accessToken(), """
                {"status":"ABSENT"}""").andExpect(status().isOk());
        api.post("/api/transport/trips/" + trip + "/delay", s.attendant.accessToken(), """
                {"minutes":20,"reason":"=Road closed, detour"}""").andExpect(status().isOk());
        api.get("/api/transport/reports/boarding?routeId=" + bus.route() + "&date=" + today, s.principal.accessToken())
                .andExpect(jsonPath("$.boarded").value(1))
                .andExpect(jsonPath("$.absent").value(1))
                .andExpect(jsonPath("$.delays").value(1))
                .andExpect(jsonPath("$.rows[*].studentName", contains("Bala Iyer", "Asha Rao", null)))
                .andExpect(jsonPath("$.rows[0].classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.rows[0].byName").value("Anita Attendant"))
                .andExpect(jsonPath("$.rows[2].minutes").value(20));
        api.get("/api/transport/reports/boarding?date=" + today.minusDays(1), s.token())
                .andExpect(jsonPath("$.rows", empty()));
        String file = csv("/api/transport/reports/boarding.csv", s.token());
        assertThat(file).startsWith("﻿Time,Route,Trip,Entry,Student,Admission no.,Class,Stop,Delay (minutes),"
                + "Note,Recorded by\r\n")
                .contains(",Route 1,MORNING,BOARDED,Bala Iyer,TR-2,Class 5 A,Gate Stop,,,Anita Attendant\r\n")
                // A note that looks like a formula is defused.
                .contains(",Route 1,MORNING,DELAYED,,,,,20,\"'=Road closed, detour\",Anita Attendant\r\n");
        TransportSchool other = new TransportSchool(api);
        api.get("/api/transport/reports/boarding?routeId=" + bus.route(), other.token())
                .andExpect(jsonPath("$.rows", empty()));
        api.get("/api/transport/reports/boarding", s.attendant.accessToken()).andExpect(status().isForbidden());
        api.get("/api/transport/reports/boarding", s.manager.accessToken())
                .andExpect(jsonPath("$.rows", not(empty())));
    }
}
