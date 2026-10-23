package com.akshara.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** Drills, audits, incidents and kits; the POCSO committee and its register; staff checks; the reports. */
class SafetyIT extends IntegrationTest {

    @Test
    void theSafetyRegisters() throws Exception {
        HealthSchool s = new HealthSchool(api);
        String admin = s.admin.accessToken();
        LocalDate today = HealthText.today();
        // Drills: not in the future; the next one defaults to 90 days later.
        api.post("/api/health/safety/drills", admin, """
                {"drillDate":"%s","kind":"FIRE","block":"Main","timeToClearSeconds":200,"attendance":300}"""
                .formatted(today.plusDays(1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.drillDate").exists());
        String drill = TestApi.read(api.post("/api/health/safety/drills", admin, """
                {"drillDate":"%s","kind":"FIRE","block":"Main block","timeToClearSeconds":212,"attendance":612,
                 "issues":"Slow on the first floor"}""".formatted(today.minusDays(100)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nextDue").value(today.minusDays(10).toString()))
                .andExpect(jsonPath("$.due").value("OVERDUE")), "$.id");
        api.put("/api/health/safety/drills/" + drill, admin, """
                {"drillDate":"%s","kind":"EVACUATION","block":"Main block","timeToClearSeconds":190,"attendance":600,
                 "nextDue":"%s"}""".formatted(today.minusDays(100), today.plusDays(20)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.due").value("DUE_SOON"));
        api.get("/api/health/reports/drills", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextDue").value(today.plusDays(20).toString()))
                .andExpect(jsonPath("$.due").value("DUE_SOON"))
                .andExpect(jsonPath("$.rows.length()").value(1));
        // The principal holds safety.manage; the nurse and teachers do not.
        api.get("/api/health/safety", s.principal.accessToken()).andExpect(status().isOk());
        api.get("/api/health/safety", s.nurse.accessToken()).andExpect(status().isForbidden());
        api.post("/api/health/safety/drills", s.teacher.accessToken(), """
                {"drillDate":"%s","kind":"FIRE","block":"Main","timeToClearSeconds":200,"attendance":300}""".formatted(today))
                .andExpect(status().isForbidden());
        // Audits: open until closed, overdue after the due date, closed once.
        String finding = TestApi.read(api.post("/api/health/safety/audits", admin, """
                {"area":"ELECTRICAL","auditDate":"%s","finding":"Loose wire","severity":"HIGH","dueOn":"%s"}"""
                .formatted(today.minusDays(20), today.minusDays(2)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.overdue").value(true)), "$.id");
        api.post("/api/health/safety/audits", admin, """
                {"area":"WATER","auditDate":"%s","finding":"Filter","severity":"LOW","dueOn":"%s"}"""
                .formatted(today, today.minusDays(1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dueOn").exists());
        api.post("/api/health/safety/audits/" + finding + "/close", admin, """
                {"closedOn":"%s","closureNote":"Fixed by the electrician"}""".formatted(today))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.overdue").value(false));
        api.post("/api/health/safety/audits/" + finding + "/close", admin, null).andExpect(status().isConflict());
        api.post("/api/health/safety/audits/" + finding + "/reopen", admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        // Incidents: a student from the roll (foreign id is 400) or a name; never deleted.
        api.post("/api/health/safety/incidents", admin, """
                {"kind":"INJURY","severity":"MINOR","studentId":"%s","description":"Fell"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.studentId").exists());
        String incident = TestApi.read(api.post("/api/health/safety/incidents", admin, """
                {"kind":"BULLYING","severity":"MODERATE","studentId":"%s","place":"Playground",
                 "description":"Name-calling at lunch","actions":"Both families called","parentInformed":true}"""
                .formatted(s.asha)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.personName").value("Asha Rao"))
                .andExpect(jsonPath("$.className").value("Class 5 A")), "$.id");
        api.put("/api/health/safety/incidents/" + incident, admin, """
                {"kind":"BULLYING","severity":"SERIOUS","personName":"A visitor","description":"Updated"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.severity").value("SERIOUS"))
                .andExpect(jsonPath("$.studentId").doesNotExist());
        // Kits: unique by location; a check records completeness and the earliest expiry.
        String kit = TestApi.read(api.post("/api/health/safety/kits", admin, """
                {"location":"Front office"}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkDue").value(true)), "$.id");
        api.post("/api/health/safety/kits", admin, """
                {"location":"front office"}""").andExpect(status().isConflict());
        api.post("/api/health/safety/kits/" + kit + "/check", admin, """
                {"checkedOn":"%s","complete":false,"earliestExpiry":"%s","missingItems":"Burn gel"}"""
                .formatted(today, today.plusDays(10))).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkDue").value(false)).andExpect(jsonPath("$.expiry").value("DUE_SOON"))
                .andExpect(jsonPath("$.checkedByName").value("Asha Admin"));
        api.get("/api/health/safety", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.drills.length()").value(1))
                .andExpect(jsonPath("$.audits.length()").value(1))
                .andExpect(jsonPath("$.incidents.length()").value(1))
                .andExpect(jsonPath("$.kits.length()").value(1));
        api.delete("/api/health/safety/kits/" + kit, admin).andExpect(status().isNoContent());
        api.get("/api/dashboard", admin).andExpect(jsonPath("$.health.drillDue").value("DUE_SOON"))
                .andExpect(jsonPath("$.health.openFindings").value(1));
        String csv = api.get("/api/health/reports/drills.csv", admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("﻿Date,Kind").contains("Main block");
        api.get("/api/audit-events?limit=50", admin)
                .andExpect(jsonPath("$[*].action", hasItems("fire_drill.recorded", "safety_audit.closed",
                        "incident.recorded", "first_aid_kit.checked", "first_aid_kit.removed")));
        HealthSchool other = new HealthSchool(api);
        api.put("/api/health/safety/drills/" + drill, other.admin.accessToken(), """
                {"drillDate":"%s","kind":"FIRE","block":"X","timeToClearSeconds":1,"attendance":1}"""
                .formatted(today)).andExpect(status().isNotFound());
    }

    @Test
    void thePocsoCommitteeAloneReadsTheComplaintsRegisterAndEveryReadIsAudited() throws Exception {
        HealthSchool s = new HealthSchool(api);
        String admin = s.admin.accessToken();
        // Every staff member sees the committee; nobody but the committee reads complaints.
        api.get("/api/health/pocso/committee", s.teacher.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(0))
                .andExpect(jsonPath("$.canReadComplaints").value(false))
                .andExpect(jsonPath("$.canManage").value(false));
        api.get("/api/health/pocso/complaints", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/health/pocso/complaints", admin).andExpect(status().isForbidden());
        api.get("/api/health/pocso/complaints", s.parent.accessToken()).andExpect(status().isForbidden());
        // The principal has pocso.committee from the catalogue.
        api.get("/api/health/pocso/complaints", s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // The admin forms the committee with Tara as a member; she may then read the register.
        api.put("/api/health/pocso/committee", admin, """
                {"formedOn":"2026-06-01"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.formedOn").value("2026-06-01")).andExpect(jsonPath("$.canManage").value(true));
        api.post("/api/health/pocso/committee/members", admin, """
                {"userId":"%s","role":"MEMBER"}""".formatted(UUID.randomUUID())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.userId").exists());
        String member = TestApi.read(api.post("/api/health/pocso/committee/members", admin, """
                {"userId":"%s","role":"MEMBER","appointedOn":"2026-06-01"}""".formatted(s.teacherId))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Tara Teacher")), "$.id");
        api.post("/api/health/pocso/committee/members", admin, """
                {"userId":"%s","role":"CHAIRPERSON"}""".formatted(s.teacherId)).andExpect(status().isConflict());
        api.post("/api/health/pocso/committee/meetings", admin, """
                {"meetingDate":"2026-06-15","minutes":"Committee formed; notice put up."}""")
                .andExpect(status().isCreated());
        api.post("/api/health/pocso/committee/meetings", s.teacher.accessToken(), """
                {"meetingDate":"2026-06-15","minutes":"x"}""").andExpect(status().isForbidden());
        api.get("/api/health/pocso/committee", s.teacher.accessToken())
                .andExpect(jsonPath("$.canReadComplaints").value(true))
                .andExpect(jsonPath("$.meetings[0].minutes").value("Committee formed; notice put up."));
        // Tara records a complaint; the list hides the complainant note, the detail shows it, both audited.
        String complaint = TestApi.read(api.post("/api/health/pocso/complaints", s.teacher.accessToken(), """
                {"complaintDate":"2026-09-01","category":"HARASSMENT","description":"A concern raised by a parent.",
                 "complainantNote":"Mother of a Class 5 student, by phone"}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN")), "$.id");
        String list = api.get("/api/health/pocso/complaints", s.teacher.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value("A concern raised by a parent."))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("Mother of a Class 5");
        api.get("/api/health/pocso/complaints/" + complaint, s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.complainantNote").value("Mother of a Class 5 student, by phone"));
        api.put("/api/health/pocso/complaints/" + complaint, s.principal.accessToken(), """
                {"complaintDate":"2026-09-01","category":"HARASSMENT","description":"A concern raised by a parent.",
                 "status":"UNDER_ENQUIRY","actions":"Spoke to both children"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_ENQUIRY"));
        api.get("/api/health/pocso/complaints/" + complaint, admin).andExpect(status().isForbidden());
        api.get("/api/health/pocso/complaints/" + complaint, s.otherTeacher.accessToken()).andExpect(status().isForbidden());
        // The audit trail records the reads but never the description or complainant.
        String audit = api.get("/api/audit-events?limit=50", admin)
                .andExpect(jsonPath("$[*].action", hasItems("pocso_complaint.recorded", "pocso_complaint.list_viewed",
                        "pocso_complaint.viewed", "pocso_complaint.updated", "pocso_member.added",
                        "pocso_meeting.recorded")))
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).doesNotContain("Mother of a Class 5").doesNotContain("A concern raised");
        // Removed from the committee, Tara loses the register.
        api.delete("/api/health/pocso/committee/members/" + member, admin).andExpect(status().isNoContent());
        api.get("/api/health/pocso/complaints", s.teacher.accessToken()).andExpect(status().isForbidden());
        HealthSchool other = new HealthSchool(api);
        api.get("/api/health/pocso/complaints/" + complaint, other.principal.accessToken())
                .andExpect(status().isNotFound());
    }

    @Test
    void staffVerificationsAndTrainingsWithWhatIsDue() throws Exception {
        HealthSchool s = new HealthSchool(api);
        String admin = s.admin.accessToken();
        LocalDate today = HealthText.today();
        // Nobody has a row yet: everyone is missing.
        api.get("/api/health/staff/checks", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationsMissing").value(5))
                .andExpect(jsonPath("$.rows[0].due").value("MISSING"))
                .andExpect(jsonPath("$.rows[0].pocsoTrainingDue").value(true));
        api.get("/api/health/staff/checks", s.nurse.accessToken()).andExpect(status().isForbidden());
        // Cleared needs the date; the reference is masked everywhere.
        api.put("/api/health/staff/" + s.teacherId + "/verification", admin, """
                {"status":"CLEARED","referenceNo":"PV/HYD/2025/4100"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.clearedOn").exists());
        api.put("/api/health/staff/" + s.teacherId + "/verification", admin, """
                {"status":"CLEARED","requestedOn":"%s","clearedOn":"%s","referenceNo":"PV/HYD/2025/4100",
                 "expiresOn":"%s"}""".formatted(today.minusDays(60), today.minusDays(30), today.plusYears(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNoMasked").value("••••4100"))
                .andExpect(jsonPath("$.referenceNo").doesNotExist())
                .andExpect(jsonPath("$.due").value("OK"));
        api.put("/api/health/staff/" + s.nurseId + "/verification", admin, """
                {"status":"REQUESTED","requestedOn":"%s"}""".formatted(today.minusDays(5)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.due").value("OVERDUE"));
        api.put("/api/health/staff/" + s.principalId + "/verification", admin, """
                {"status":"CLEARED","clearedOn":"%s","expiresOn":"%s"}"""
                .formatted(today.minusYears(2), today.plusDays(15)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.due").value("DUE_SOON"));
        api.put("/api/health/staff/" + UUID.randomUUID() + "/verification", admin, """
                {"status":"PENDING"}""").andExpect(status().isNotFound());
        // Trainings: not in the future, expiry after the training; renewal within 30 days is flagged.
        api.post("/api/health/staff/trainings", admin, """
                {"userId":"%s","kind":"POCSO","trainedOn":"%s"}""".formatted(s.teacherId, today.plusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.trainedOn").exists());
        String training = TestApi.read(api.post("/api/health/staff/trainings", admin, """
                {"userId":"%s","kind":"POCSO","trainedOn":"%s","expiresOn":"%s","provider":"Childline"}"""
                .formatted(s.teacherId, today.minusMonths(11), today.plusDays(20)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.needsRenewal").value(true)), "$.id");
        api.post("/api/health/staff/trainings", admin, """
                {"userId":"%s","kind":"FIRST_AID","trainedOn":"%s","expiresOn":"%s"}"""
                .formatted(s.teacherId, today.minusMonths(1), today.plusYears(2)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.needsRenewal").value(false));
        api.get("/api/health/staff/checks", admin)
                .andExpect(jsonPath("$.verificationsMissing").value(2))
                .andExpect(jsonPath("$.verificationsDue").value(2))
                .andExpect(jsonPath("$.rows[?(@.userId == '%s')].pocsoTrainingDue".formatted(s.teacherId)).value(true))
                .andExpect(jsonPath("$.rows[?(@.userId == '%s')].firstAidTrainingDue".formatted(s.teacherId)).value(false))
                .andExpect(jsonPath("$.rows[?(@.userId == '%s')].trainings.length()".formatted(s.teacherId)).value(2));
        // The report lists only those needing attention; the CSV opens in Excel.
        api.get("/api/health/reports/verifications", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.missing").value(2)).andExpect(jsonPath("$.due").value(2))
                .andExpect(jsonPath("$.rows.length()").value(5))
                .andExpect(jsonPath("$.rows[*].name", hasItem("Tara Teacher")));
        String csv = api.get("/api/health/reports/verifications.csv", admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("﻿Name,Designation").doesNotContain("PV/HYD/2025/4100");
        api.get("/api/dashboard", admin).andExpect(jsonPath("$.health.verificationsMissing").value(2))
                .andExpect(jsonPath("$.health.verificationsDue").value(2))
                .andExpect(jsonPath("$.health.trainingsDue").value(5));
        api.delete("/api/health/staff/trainings/" + training, admin).andExpect(status().isNoContent());
        api.delete("/api/health/staff/trainings/" + training, admin).andExpect(status().isNotFound());
        // Allergies by class counts, never names.
        api.post("/api/me/children/" + s.asha + "/health/items", s.parent.accessToken(), """
                {"kind":"ALLERGY","name":"Peanuts","severity":"SEVERE"}""").andExpect(status().isCreated());
        String allergies = api.get("/api/health/reports/allergies", s.teacher.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.students").value(3)).andExpect(jsonPath("$.withAllergies").value(1))
                .andExpect(jsonPath("$.severe").value(1))
                .andExpect(jsonPath("$.byClass[?(@.label == 'Class 5 A')].withAllergies").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(allergies).doesNotContain("Asha").doesNotContain("Peanuts");
        api.get("/api/health/reports/allergies.csv", s.teacher.accessToken()).andExpect(status().isOk());
        api.get("/api/health/reports/allergies", s.parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/audit-events?limit=50", admin)
                .andExpect(jsonPath("$[*].action", hasItems("staff_verification.updated", "staff_training.recorded",
                        "staff_training.removed")))
                .andExpect(jsonPath("$[*].details.referenceNo", not(hasItem("PV/HYD/2025/4100"))));
    }
}
