package com.akshara.govreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** The RTE intake: seats, applications from admissions, screening, the lottery, reruns and immutability. */
class RteIT extends IntegrationTest {

    @Test
    void anIntakeCannotCloseInThePast() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        api.post("/api/govreport/rte/intakes", s.principal.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":40,"rteSeats":10,"stateCode":"TS",
                 "incomeLimitPaise":10000000,"applicationsCloseOn":"2001-01-01"}""".formatted(s.yearId, s.classId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.applicationsCloseOn").value("The closing date cannot be in the past."));
        api.get("/api/govreport/rte/intakes", s.principal.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        String intake = TestApi.read(api.post("/api/govreport/rte/intakes", s.principal.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":40,"stateCode":"TS",
                 "applicationsCloseOn":"%s"}""".formatted(s.yearId, s.classId, today))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationsCloseOn").value(today.toString())), "$.id");
        api.put("/api/govreport/rte/intakes/" + intake, s.principal.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":40,"stateCode":"TS",
                 "applicationsCloseOn":"%s"}""".formatted(s.yearId, s.classId, today.minusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.applicationsCloseOn").exists());
        // An unchanged closing date can still be saved with other changes.
        api.put("/api/govreport/rte/intakes/" + intake, s.principal.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":30,"stateCode":"TS",
                 "applicationsCloseOn":"%s"}""".formatted(s.yearId, s.classId, today))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalSeats").value(30));
    }

    @Test
    void anIntakeTakesApplicationsScreensThemAndRunsAReproducibleLottery() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.post("/api/govreport/rte/intakes", s.office.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":40}""".formatted(s.yearId, s.classId))
                .andExpect(status().isForbidden());
        api.post("/api/govreport/rte/intakes", s.principal.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":0}""".formatted(s.yearId, s.classId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.totalSeats").exists());
        String intake = TestApi.read(api.post("/api/govreport/rte/intakes", s.principal.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":10,"stateCode":"TS"}"""
                .formatted(s.yearId, s.classId)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.rteSeats").value(3))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.documents").isNotEmpty()), "$.id");
        api.post("/api/govreport/rte/intakes", s.principal.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":10,"stateCode":"TS"}"""
                .formatted(s.yearId, s.classId)).andExpect(status().isConflict());
        api.put("/api/govreport/rte/intakes/" + intake, s.principal.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":8,"rteSeats":2,"stateCode":"TS"}"""
                .formatted(s.yearId, s.classId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rteSeats").value(2));

        String[] ids = new String[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = s.application("Child" + (char) ('A' + i), "98765020" + (10 + i));
        }
        api.get("/api/govreport/rte/intakes/" + intake + "/candidates", s.office.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(5)));
        String r0 = s.screened(intake, ids[0], "ELIGIBLE", "0.5");
        s.screened(intake, ids[1], "ELIGIBLE", "2.5");
        s.screened(intake, ids[2], "ELIGIBLE", "0.8");
        s.screened(intake, ids[3], "INELIGIBLE", "9");
        String r4 = s.screened(intake, ids[4], "ELIGIBLE", "1.2");
        api.post("/api/govreport/rte/intakes/" + intake + "/applications", s.principal.accessToken(), """
                {"applicationId":"%s"}""".formatted(ids[0])).andExpect(status().isConflict());
        api.post("/api/govreport/rte/intakes/" + intake + "/applications", s.principal.accessToken(), """
                {"applicationId":"%s"}""".formatted(UUID.randomUUID())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.applicationId").exists());
        api.put("/api/govreport/rte/applications/" + r0, s.principal.accessToken(), """
                {"incomeDeclared":false,"eligibility":"ELIGIBLE"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.incomeDeclared").exists());
        api.put("/api/govreport/rte/applications/" + r0, s.principal.accessToken(), """
                {"incomeDeclared":true,"eligibility":"INELIGIBLE"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ineligibleReason").exists());
        api.post("/api/govreport/rte/applications/" + r4 + "/withdraw", s.principal.accessToken(), "")
                .andExpect(status().isOk()).andExpect(jsonPath("$.withdrawn").value(true));
        api.get("/api/govreport/rte/intakes/" + intake, s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applications", hasSize(5)))
                .andExpect(jsonPath("$.applications[0].guardianPhone").value("XXXXXX2010"))
                .andExpect(jsonPath("$.intake.eligible").value(3));

        api.post("/api/govreport/rte/intakes/" + intake + "/lottery", s.office.accessToken(), """
                {"witnessName":"SMC member"}""").andExpect(status().isForbidden());
        api.post("/api/govreport/rte/intakes/" + intake + "/lottery", s.principal.accessToken(), """
                {"witnessName":""}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.witnessName").exists());
        String body = api.post("/api/govreport/rte/intakes/" + intake + "/lottery", s.principal.accessToken(), """
                {"witnessName":"SMC member","seed":12345}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.runNo").value(1))
                .andExpect(jsonPath("$.run.seed").value(12345))
                .andExpect(jsonPath("$.run.current").value(true))
                .andExpect(jsonPath("$.allotments", hasSize(3)))
                .andExpect(jsonPath("$.allotments[0].outcome").value("ALLOTTED"))
                .andExpect(jsonPath("$.allotments[0].distanceBand").value(1))
                .andExpect(jsonPath("$.allotments[2].outcome").value("WAITLISTED"))
                .andExpect(jsonPath("$.allotments[2].waitlistNo").value(1))
                .andReturn().getResponse().getContentAsString();
        String run1 = TestApi.read(api.get("/api/govreport/rte/intakes/" + intake, s.office.accessToken()),
                "$.runs[0].id");

        // After the lottery the intake and its applications are fixed.
        api.post("/api/govreport/rte/intakes/" + intake + "/applications", s.principal.accessToken(), """
                {"applicationId":"%s"}""".formatted(s.application("Late", "9876502099")))
                .andExpect(status().isConflict());
        api.put("/api/govreport/rte/applications/" + r0, s.principal.accessToken(), """
                {"incomeDeclared":true,"eligibility":"ELIGIBLE"}""").andExpect(status().isConflict());
        api.put("/api/govreport/rte/intakes/" + intake, s.principal.accessToken(), """
                {"academicYearId":"%s","entryClassId":"%s","totalSeats":8,"stateCode":"TS"}""".formatted(s.yearId, s.classId))
                .andExpect(status().isConflict());

        // A rerun needs a reason, is a new run, and the same seed gives the same order.
        api.post("/api/govreport/rte/intakes/" + intake + "/lottery", s.principal.accessToken(), """
                {"witnessName":"SMC member","seed":12345}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        String rerun = api.post("/api/govreport/rte/intakes/" + intake + "/lottery", s.principal.accessToken(), """
                {"witnessName":"SMC member","seed":12345,"reason":"Parents asked for a public draw"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.runNo").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(TestApi.read(api.get("/api/govreport/rte/runs/" + run1, s.office.accessToken()),
                "$.allotments[*].application.id").toString())
                .isEqualTo(com.jayway.jsonpath.JsonPath.read(rerun, "$.allotments[*].application.id").toString());
        assertThat(com.jayway.jsonpath.JsonPath.read(body, "$.allotments[*].application.id").toString())
                .isEqualTo(com.jayway.jsonpath.JsonPath.read(rerun, "$.allotments[*].application.id").toString());
        api.get("/api/govreport/rte/runs/" + run1, s.office.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.run.current").value(false));
        api.get("/api/govreport/reports/rte", s.office.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.seats").value(2))
                .andExpect(jsonPath("$.allotted").value(2))
                .andExpect(jsonPath("$.waitlisted").value(1));

        // Another school sees none of it.
        GovReportSchool other = new GovReportSchool(api);
        api.get("/api/govreport/rte/intakes/" + intake, other.admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/govreport/rte/runs/" + run1, other.admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/govreport/rte/intakes", other.admin.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        api.post("/api/govreport/rte/intakes/" + intake + "/lottery", other.admin.accessToken(), """
                {"witnessName":"X","reason":"Y"}""").andExpect(status().isNotFound());

        api.post("/api/govreport/rte/intakes/" + intake + "/close", s.principal.accessToken(), "")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        api.post("/api/govreport/rte/intakes/" + intake + "/lottery", s.principal.accessToken(), """
                {"witnessName":"X","reason":"Y"}""").andExpect(status().isConflict());
        String audit = api.get("/api/audit-events?limit=50", s.admin.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("rte_intake.created").contains("rte_application.screened")
                .contains("rte_lottery.run").contains("rte_intake.closed");
    }

    @Test
    void theLotteryNeedsAnEligibleApplication() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        String intake = s.intake(10, 2);
        api.post("/api/govreport/rte/intakes/" + intake + "/lottery", s.principal.accessToken(), """
                {"witnessName":"SMC member"}""").andExpect(status().isBadRequest());
        api.get("/api/govreport/rte/intakes/" + UUID.randomUUID(), s.office.accessToken())
                .andExpect(status().isNotFound());
    }
}
