package com.akshara.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** The infirmary log: visits, medicine from the stock, closing with an outcome, the parent's message and the event. */
@RecordApplicationEvents
class InfirmaryIT extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    private List<StudentSentHome> sentHome() {
        return events.stream(StudentSentHome.class).toList();
    }

    @Test
    void theNurseLogsVisitsGivesMedicineAndSendsAStudentHome() throws Exception {
        HealthSchool s = new HealthSchool(api);
        String nurse = s.nurse.accessToken();
        String paracetamol = s.medicine("Paracetamol", 10);
        api.post("/api/health/infirmary/medicines", nurse, """
                {"name":"paracetamol","unit":"tablet","quantity":3}""").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/me/children/" + s.asha + "/health/items", s.parent.accessToken(), """
                {"kind":"ALLERGY","name":"Peanuts","severity":"SEVERE"}""").andExpect(status().isCreated());
        // The search covers students and staff.
        api.get("/api/health/infirmary/people?q=a", nurse).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'STUDENT')]").exists())
                .andExpect(jsonPath("$[?(@.kind == 'STAFF' && @.name == 'Tara Teacher')]").exists());
        // Validation: a student visit names the student, a staff visit the staff member; a foreign id is 400.
        api.post("/api/health/infirmary/visits", nurse, """
                {"personKind":"STUDENT","complaint":"Fever"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/health/infirmary/visits", nurse, """
                {"personKind":"STUDENT","studentId":"%s","complaint":"Fever"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/health/infirmary/visits", nurse, """
                {"personKind":"STAFF","complaint":"Headache"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.staffUserId").exists());
        api.post("/api/health/infirmary/visits", nurse, """
                {"personKind":"STUDENT","studentId":"%s","complaint":"Fever","temperatureC":50}""".formatted(s.asha))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.temperatureC").exists());
        api.post("/api/health/infirmary/visits", nurse, """
                {"personKind":"STUDENT","studentId":"%s","complaint":"Fever",
                 "medicines":[{"medicineId":"%s","quantity":11}]}""".formatted(s.asha, paracetamol))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.medicines").exists());
        // A visit with a tablet given: the stock goes down and the allergy badge rides along.
        String visit = TestApi.read(api.post("/api/health/infirmary/visits", nurse, """
                {"personKind":"STUDENT","studentId":"%s","complaint":"Fever and headache","temperatureC":38.64,
                 "treatment":"Rested","medicines":[{"medicineId":"%s","quantity":2}]}""".formatted(s.asha, paracetamol))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personName").value("Asha Rao"))
                .andExpect(jsonPath("$.className").value("Class 5 A"))
                .andExpect(jsonPath("$.sectionId").value(s.section5A))
                .andExpect(jsonPath("$.temperatureC").value(38.6))
                .andExpect(jsonPath("$.timeOut").doesNotExist())
                .andExpect(jsonPath("$.medicines[0].name").value("Paracetamol"))
                .andExpect(jsonPath("$.medicines[0].quantity").value(2))
                .andExpect(jsonPath("$.allergies[0].severity").value("SEVERE"))
                .andExpect(jsonPath("$.allergies[0].label").value("Peanuts")), "$.id");
        api.get("/api/health/infirmary/medicines", nurse)
                .andExpect(jsonPath("$[0].quantity").value(8)).andExpect(jsonPath("$[0].low").value(false));
        String staffVisit = TestApi.read(api.post("/api/health/infirmary/visits", nurse, """
                {"personKind":"STAFF","staffUserId":"%s","complaint":"Headache"}""".formatted(s.teacherId))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.personName").value("Tara Teacher")), "$.id");
        api.get("/api/health/infirmary/visits", nurse).andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(2)).andExpect(jsonPath("$.visits.length()").value(2));
        // Teachers with health.read see the log but cannot work it.
        api.get("/api/health/infirmary/visits", s.teacher.accessToken()).andExpect(status().isOk());
        api.post("/api/health/infirmary/visits/" + visit + "/close", s.teacher.accessToken(), """
                {"outcome":"BACK_TO_CLASS"}""").andExpect(status().isForbidden());
        api.get("/api/health/infirmary/visits", s.parent.accessToken()).andExpect(status().isForbidden());
        // Sent home: the parent gets a simulated message, the event is published, the visit closes once.
        int before = sentHome().size();
        api.post("/api/health/infirmary/visits/" + visit + "/close", nurse, """
                {"outcome":"SENT_HOME","timeOut":"%s"}""".formatted(Instant.now().plusSeconds(3600)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.timeOut").exists());
        api.post("/api/health/infirmary/visits/" + visit + "/close", nurse, """
                {"outcome":"SENT_HOME"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SENT_HOME"))
                .andExpect(jsonPath("$.timeOut").exists())
                .andExpect(jsonPath("$.parentInformed").value(true));
        api.post("/api/health/infirmary/visits/" + visit + "/close", nurse, """
                {"outcome":"SENT_HOME"}""").andExpect(status().isConflict());
        api.put("/api/health/infirmary/visits/" + visit, nurse, """
                {"personKind":"STUDENT","studentId":"%s","complaint":"Fever"}""".formatted(s.asha))
                .andExpect(status().isConflict());
        assertThat(sentHome().size()).isEqualTo(before + 1);
        StudentSentHome event = sentHome().getLast();
        assertThat(event.studentId()).isEqualTo(UUID.fromString(s.asha));
        assertThat(event.sectionId()).isEqualTo(UUID.fromString(s.section5A));
        assertThat(event.outcome()).isEqualTo(VisitOutcome.SENT_HOME);
        String messageId = TestApi.read(api.get("/api/messages?relatedId=" + visit, s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].templateKey").value("health.sent_home"))
                .andExpect(jsonPath("$.items[0].recipient").value("98765•••01")), "$.items[0].id");
        api.get("/api/messages/" + messageId, s.admin.accessToken())
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("Asha Rao (Class 5 A)")));
        // A staff member sent to hospital: no parent, no event, just the record.
        api.post("/api/health/infirmary/visits/" + staffVisit + "/close", nurse, """
                {"outcome":"HOSPITAL","parentInformed":false}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.parentInformed").value(false));
        assertThat(sentHome().size()).isEqualTo(before + 1);
        // The parent sees the visit on the family health page; the infirmary report counts it.
        api.get("/api/me/children/" + s.asha + "/health", s.parent.accessToken())
                .andExpect(jsonPath("$.visits.length()").value(1))
                .andExpect(jsonPath("$.visits[0].outcome").value("SENT_HOME"));
        api.get("/api/health/reports/infirmary", nurse).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.byMonth[0].sentHome").value(1))
                .andExpect(jsonPath("$.byMonth[0].hospital").value(1))
                .andExpect(jsonPath("$.byMonth[0].staff").value(1));
        String csv = api.get("/api/health/reports/infirmary.csv", nurse).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("﻿Month,Visits");
        api.get("/api/health/reports/infirmary?from=2026-01-01&to=2027-12-31", nurse).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.to").exists());
        // The medicine in use cannot be removed; one never given can.
        api.delete("/api/health/infirmary/medicines/" + paracetamol, nurse).andExpect(status().isConflict());
        String ors = s.medicine("ORS", 4);
        api.get("/api/health/infirmary/medicines", nurse).andExpect(jsonPath("$[?(@.name == 'ORS')].low").value(true));
        api.delete("/api/health/infirmary/medicines/" + ors, nurse).andExpect(status().isNoContent());
        api.get("/api/dashboard", nurse).andExpect(jsonPath("$.health.visitsToday").value(2))
                .andExpect(jsonPath("$.health.inInfirmary").value(0))
                .andExpect(jsonPath("$.health.verificationsDue").doesNotExist());
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("infirmary_visit.recorded", "infirmary_visit.closed",
                        "medicine.added", "medicine.removed")));
        // Another school sees none of it.
        HealthSchool other = new HealthSchool(api);
        api.get("/api/health/infirmary/visits/" + visit, other.nurse.accessToken()).andExpect(status().isNotFound());
        api.get("/api/health/infirmary/visits", other.nurse.accessToken()).andExpect(jsonPath("$.visits.length()").value(0));
    }
}
