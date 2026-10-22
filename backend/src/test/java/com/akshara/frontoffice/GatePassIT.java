package com.akshara.frontoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/** Early pickup gate passes with the parent's OTP, and the routine pickup persons. */
class GatePassIT extends IntegrationTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Test
    void parentRequestsOfficeApprovesAndTheGateReleasesWithTheOtp() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        api.get("/api/me/children/" + s.asha + "/pickup", s.parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.className").value("Class 5 A"))
                .andExpect(jsonPath("$.canRequest").value(true))
                .andExpect(jsonPath("$.passes.length()").value(0));
        String pass = s.requestPickup();
        api.get("/api/me/gate-passes", s.parent.accessToken())
                .andExpect(jsonPath("$[0].id").value(pass))
                .andExpect(jsonPath("$[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$[0].source").value("PARENT_APP"))
                .andExpect(jsonPath("$[0].canCancel").value(true));
        api.get("/api/me/children/" + s.asha + "/pickup", s.parent.accessToken())
                .andExpect(jsonPath("$.canRequest").value(false));
        // A second open pass for the same child is refused.
        api.post("/api/me/children/" + s.asha + "/gate-passes", s.parent.accessToken(), """
                {"pickupAt":"%s","reason":"Again","collectorKind":"SELF"}""".formatted(FrontOfficeSchool.soon()))
                .andExpect(status().isConflict());
        // The office sees it waiting, with the guardian's phone masked; the gate cannot release it yet.
        api.get("/api/frontoffice/gate-passes", s.office.accessToken())
                .andExpect(jsonPath("$[0].id").value(pass))
                .andExpect(jsonPath("$[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$[0].guardianName").value("Lata Rao"))
                .andExpect(jsonPath("$[0].guardianPhoneMasked").value("98765•••01"))
                .andExpect(jsonPath("$[0].canDecide").value(true))
                .andExpect(jsonPath("$[0].canRelease").value(false));
        api.get("/api/dashboard", s.office.accessToken())
                .andExpect(jsonPath("$.frontOffice.passesToApprove").value(1));
        api.post("/api/frontoffice/gate-passes/" + pass + "/release", s.security.accessToken(),
                FrontOfficeSchool.releaseJson("123456")).andExpect(status().isConflict());
        s.approve(pass);
        api.post("/api/frontoffice/gate-passes/" + pass + "/approve", s.office.accessToken(), null)
                .andExpect(status().isConflict());
        // No staff response carries the OTP; the message log (simulated SMS to the mother) does.
        String otp = s.otpOf(pass);
        for (Session who : new Session[] {s.office, s.security, s.admin}) {
            String json = api.get("/api/frontoffice/gate-passes/" + pass, who.accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.attemptsLeft").value(5))
                    .andExpect(jsonPath("$.otpExpiresAt").exists())
                    .andReturn().getResponse().getContentAsString();
            assertThat(json).doesNotContain(otp).doesNotContain("otpHash").doesNotContain("9876501001");
        }
        assertThat(api.get("/api/frontoffice/gate", s.security.accessToken())
                .andExpect(jsonPath("$.passes[0].id").value(pass))
                .andExpect(jsonPath("$.passes[0].canRelease").value(true))
                .andReturn().getResponse().getContentAsString()).doesNotContain(otp);
        assertThat(api.get("/api/me/gate-passes", s.parent.accessToken())
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString()).doesNotContain(otp);
        api.get("/api/dashboard", s.office.accessToken())
                .andExpect(jsonPath("$.frontOffice.passesToApprove").value(0))
                .andExpect(jsonPath("$.frontOffice.passesAtGate").value(1));
        // The class teacher is told in-app on her dashboard.
        api.get("/api/dashboard", s.teacher.accessToken())
                .andExpect(jsonPath("$.frontOffice.classPickups[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.frontOffice.classPickups[0].status").value("APPROVED"));
        // A wrong OTP counts; the right one releases the child and records who collected.
        api.post("/api/frontoffice/gate-passes/" + pass + "/release", s.security.accessToken(),
                FrontOfficeSchool.releaseJson("000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(GatePassService.WRONG_OTP_TYPE))
                .andExpect(jsonPath("$.detail", containsString("4 tries left")));
        api.post("/api/frontoffice/gate-passes/" + pass + "/release", s.security.accessToken(), """
                {"otp":"","collectedByName":"Lata"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.otp").exists());
        api.post("/api/frontoffice/gate-passes/" + pass + "/release", s.security.accessToken(),
                FrontOfficeSchool.releaseJson(otp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.collectedByName").value("Lata Rao"))
                .andExpect(jsonPath("$.releasedByName").value("Sam Security"));
        api.post("/api/frontoffice/gate-passes/" + pass + "/release", s.security.accessToken(),
                FrontOfficeSchool.releaseJson(otp)).andExpect(status().isConflict());
        api.get("/api/me/gate-passes", s.parent.accessToken())
                .andExpect(jsonPath("$[0].status").value("RELEASED"))
                .andExpect(jsonPath("$[0].canCancel").value(false));
        api.get("/api/me/children/" + s.asha + "/pickup", s.parent.accessToken())
                .andExpect(jsonPath("$.canRequest").value(true));
        api.get("/api/frontoffice/gate", s.security.accessToken()).andExpect(jsonPath("$.passes.length()").value(0));
        // Everything is audited, and the audit never holds the OTP.
        String audit = api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("gate_pass.requested", "gate_pass.approved",
                        "gate_pass.wrong_otp", "gate_pass.released")))
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).doesNotContain(otp).contains("98765•••01");
        // The message was queued once, to the mother, about the pass.
        api.get("/api/messages?relatedId=" + pass, s.admin.accessToken())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void fiveWrongOtpsLockThePassAndAnExpiredOtpIsRefused() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        String pass = s.requestPickup();
        s.approve(pass);
        String otp = s.otpOf(pass);
        for (int i = 1; i <= 4; i++) {
            api.post("/api/frontoffice/gate-passes/" + pass + "/release", s.security.accessToken(),
                    FrontOfficeSchool.releaseJson("999999"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value(GatePassService.WRONG_OTP_TYPE));
        }
        api.get("/api/frontoffice/gate-passes/" + pass, s.security.accessToken())
                .andExpect(jsonPath("$.attemptsLeft").value(1))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        api.post("/api/frontoffice/gate-passes/" + pass + "/release", s.security.accessToken(),
                FrontOfficeSchool.releaseJson("999999"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(GatePassService.LOCKED_TYPE));
        // Even the right OTP no longer works.
        api.post("/api/frontoffice/gate-passes/" + pass + "/release", s.security.accessToken(),
                FrontOfficeSchool.releaseJson(otp))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(GatePassService.LOCKED_TYPE));
        api.get("/api/frontoffice/gate-passes/" + pass, s.office.accessToken())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.attemptsLeft").value(0));
        api.get("/api/me/gate-passes", s.parent.accessToken()).andExpect(jsonPath("$[0].status").value("LOCKED"));
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("gate_pass.locked")));
        // The office raises a fresh pass for the same child; its OTP expires four hours after approval.
        String fresh = TestApi.read(api.post("/api/frontoffice/gate-passes", s.office.accessToken(), """
                {"studentId":"%s","pickupAt":"%s","reason":"Mother called","collectorKind":"OTHER",
                 "collectorName":"Ravi Rao","collectorPhone":"9876501009"}""".formatted(s.asha,
                FrontOfficeSchool.soon())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("OFFICE"))
                .andExpect(jsonPath("$.collectorPhoneMasked").value("98765•••09"))
                .andExpect(jsonPath("$.status").value("REQUESTED")), "$.id");
        s.approve(fresh);
        String freshOtp = s.otpOf(fresh);
        assertThat(freshOtp).isNotEqualTo(otp);
        try (Connection owner = ownerConnection();
                PreparedStatement update = owner.prepareStatement(
                        "update frontoffice.gate_pass set otp_expires_at = ? where id = ?")) {
            update.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))));
            update.setObject(2, UUID.fromString(fresh));
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
        api.post("/api/frontoffice/gate-passes/" + fresh + "/release", s.security.accessToken(),
                FrontOfficeSchool.releaseJson(freshOtp))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("expired")));
        api.get("/api/frontoffice/gate-passes/" + fresh, s.office.accessToken())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("gate_pass.expired")));
    }

    @Test
    void passesAreValidatedDecidedAndCancelled() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        String token = s.parent.accessToken();
        api.post("/api/me/children/" + s.asha + "/gate-passes", token, """
                {"pickupAt":"%s","reason":"Late","collectorKind":"SELF"}"""
                .formatted(Instant.now().minus(Duration.ofHours(2))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.pickupAt").exists());
        api.post("/api/me/children/" + s.asha + "/gate-passes", token, """
                {"pickupAt":"%s","reason":"Tomorrow","collectorKind":"SELF"}"""
                .formatted(Instant.now().plus(Duration.ofDays(2))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.pickupAt").exists());
        api.post("/api/me/children/" + s.asha + "/gate-passes", token, """
                {"pickupAt":"%s","reason":"Uncle","collectorKind":"OTHER"}""".formatted(FrontOfficeSchool.soon()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.collectorName").exists());
        api.post("/api/me/children/" + s.asha + "/gate-passes", token, """
                {"pickupAt":"%s","reason":"Uncle","collectorKind":"OTHER","collectorName":"Ravi"}"""
                .formatted(FrontOfficeSchool.soon()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.collectorPhone").exists());
        api.post("/api/me/children/" + s.asha + "/gate-passes", token, """
                {"pickupAt":"%s","reason":"","collectorKind":"SELF"}""".formatted(FrontOfficeSchool.soon()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reason").exists());
        // Lata's child is Asha, not Bala.
        api.post("/api/me/children/" + s.bala + "/gate-passes", token, """
                {"pickupAt":"%s","reason":"x","collectorKind":"SELF"}""".formatted(FrontOfficeSchool.soon()))
                .andExpect(status().isNotFound());
        api.get("/api/me/children/" + s.bala + "/pickup", token).andExpect(status().isNotFound());
        // The parent cancels a requested pass; the office rejects another with a reason.
        String pass = s.requestPickup();
        api.post("/api/me/children/" + s.bala + "/gate-passes/" + pass + "/cancel", token, null)
                .andExpect(status().isNotFound());
        api.post("/api/me/children/" + s.asha + "/gate-passes/" + pass + "/cancel", token, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        api.post("/api/me/children/" + s.asha + "/gate-passes/" + pass + "/cancel", token, null)
                .andExpect(status().isConflict());
        String second = s.requestPickup();
        api.post("/api/frontoffice/gate-passes/" + second + "/reject", s.office.accessToken(), "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.note").exists());
        api.post("/api/frontoffice/gate-passes/" + second + "/reject", s.office.accessToken(), """
                {"note":"Exam in progress"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionNote").value("Exam in progress"));
        api.get("/api/me/gate-passes", token)
                .andExpect(jsonPath("$[*].status", contains("REJECTED", "CANCELLED")));
        api.get("/api/messages?relatedId=" + second, s.admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        // Foreign ids in bodies are field errors; the office finds students by name.
        api.post("/api/frontoffice/gate-passes", s.office.accessToken(), """
                {"studentId":"%s","pickupAt":"%s","reason":"x","collectorKind":"SELF"}"""
                .formatted(UUID.randomUUID(), FrontOfficeSchool.soon()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.studentId").exists());
        api.get("/api/frontoffice/students?q=bal", s.office.accessToken())
                .andExpect(jsonPath("$[0].fullName").value("Bala Iyer"))
                .andExpect(jsonPath("$[0].className").value("Class 5 A"));
        api.get("/api/frontoffice/gate-passes/" + UUID.randomUUID(), s.office.accessToken())
                .andExpect(status().isNotFound());
        // The reports hub counts them by day; the CSV starts with a BOM.
        api.get("/api/frontoffice/reports/gate-passes", s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.byDay[0].rejected").value(1))
                .andExpect(jsonPath("$.byDay[0].cancelled").value(1));
        byte[] csv = api.get("/api/frontoffice/reports/gate-passes.csv", s.admin.accessToken())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(csv[0] & 0xFF).isEqualTo(0xEF);
        assertThat(new String(csv, java.nio.charset.StandardCharsets.UTF_8)).contains("Asha Rao")
                .contains("REJECTED");
    }

    @Test
    void parentsKeepTheirChildsPickupPersonsWhichTheGateSees() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        String token = s.parent.accessToken();
        String person = TestApi.read(api.post("/api/me/children/" + s.asha + "/pickup-persons", token, """
                {"name":"Ravi Rao","relation":"Father","phone":"9876501009"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phoneMasked").value("98765•••09"))
                .andExpect(jsonPath("$.phone").doesNotExist()), "$.id");
        api.post("/api/me/children/" + s.asha + "/pickup-persons", token, """
                {"name":"Nobody","relation":"Friend","phone":"12"}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.phone").exists());
        api.post("/api/me/children/" + s.bala + "/pickup-persons", token, """
                {"name":"Ravi Rao","relation":"Father","phone":"9876501009"}""").andExpect(status().isNotFound());
        api.put("/api/me/children/" + s.asha + "/pickup-persons/" + person, token, """
                {"name":"Ravi Rao","relation":"Father","phone":"9876501010"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.phoneMasked").value("98765•••10"));
        // A photo from the phone camera, stored through the files module and visible to the gate.
        String photo = TestApi.read(mvc.perform(MockMvcRequestBuilders
                .multipart("/api/me/children/" + s.asha + "/pickup-persons/" + person + "/photo")
                .file(new MockMultipartFile("file", "ravi.png", "image/png", PNG))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoFileId").exists()), "$.photoFileId");
        api.get("/api/files/" + photo, s.security.accessToken()).andExpect(status().isOk());
        api.get("/api/files/" + photo, token).andExpect(status().isOk());
        api.get("/api/files/" + photo, s.teacher.accessToken()).andExpect(status().isNotFound());
        api.get("/api/frontoffice/students/" + s.asha + "/pickup-persons", s.security.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.persons[0].name").value("Ravi Rao"))
                .andExpect(jsonPath("$.persons[0].photoFileId").value(photo))
                .andExpect(jsonPath("$.persons[0].phoneMasked").value("98765•••10"));
        api.get("/api/frontoffice/students/" + s.asha + "/pickup-persons", s.teacher.accessToken())
                .andExpect(status().isForbidden());
        api.get("/api/me/children/" + s.asha + "/pickup", token)
                .andExpect(jsonPath("$.persons.length()").value(1));
        for (int i = 2; i <= 5; i++) {
            api.post("/api/me/children/" + s.asha + "/pickup-persons", token, """
                    {"name":"Person %d","relation":"Aunt","phone":"987650100%d"}""".formatted(i, i))
                    .andExpect(status().isCreated());
        }
        api.post("/api/me/children/" + s.asha + "/pickup-persons", token, """
                {"name":"One too many","relation":"Aunt","phone":"9876501099"}""").andExpect(status().isConflict());
        api.delete("/api/me/children/" + s.asha + "/pickup-persons/" + person, token)
                .andExpect(status().isNoContent());
        api.delete("/api/me/children/" + s.asha + "/pickup-persons/" + person, token)
                .andExpect(status().isNotFound());
        api.get("/api/files/" + photo, s.security.accessToken()).andExpect(status().isNotFound());
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("pickup_person.added", "pickup_person.updated",
                        "pickup_person.photo_added", "pickup_person.removed")));
    }

    @Test
    void onlyTheRightRolesTouchPassesAndAnotherSchoolSeesNothing() throws Exception {
        FrontOfficeSchool s = new FrontOfficeSchool(api);
        String pass = s.requestPickup();
        String teacher = s.teacher.accessToken();
        api.get("/api/frontoffice/gate-passes", teacher).andExpect(status().isForbidden());
        api.get("/api/frontoffice/gate-passes/" + pass, teacher).andExpect(status().isForbidden());
        api.get("/api/frontoffice/gate", teacher).andExpect(status().isForbidden());
        api.get("/api/frontoffice/students?q=a", teacher).andExpect(status().isForbidden());
        api.post("/api/frontoffice/gate-passes", teacher, """
                {"studentId":"%s","pickupAt":"%s","reason":"x","collectorKind":"SELF"}"""
                .formatted(s.asha, FrontOfficeSchool.soon())).andExpect(status().isForbidden());
        api.post("/api/frontoffice/gate-passes/" + pass + "/approve", teacher, null)
                .andExpect(status().isForbidden());
        api.post("/api/frontoffice/gate-passes/" + pass + "/reject", teacher, null).andExpect(status().isForbidden());
        api.post("/api/frontoffice/gate-passes/" + pass + "/release", teacher,
                FrontOfficeSchool.releaseJson("123456")).andExpect(status().isForbidden());
        api.get("/api/me/gate-passes", teacher).andExpect(status().isForbidden());
        // Security works the gate but does not decide, and reads no office lists.
        String security = s.security.accessToken();
        api.get("/api/frontoffice/gate", security).andExpect(status().isOk());
        api.get("/api/frontoffice/gate-passes", security).andExpect(status().isForbidden());
        api.get("/api/frontoffice/students?q=a", security).andExpect(status().isForbidden());
        api.post("/api/frontoffice/gate-passes", security, """
                {"studentId":"%s","pickupAt":"%s","reason":"x","collectorKind":"SELF"}"""
                .formatted(s.asha, FrontOfficeSchool.soon())).andExpect(status().isForbidden());
        api.post("/api/frontoffice/gate-passes/" + pass + "/approve", security, null)
                .andExpect(status().isForbidden());
        // The principal reads, the admin manages too.
        String principalId = api.createUser(s.admin, "Pat Principal", "pat" + s.domain,
                java.util.List.of("PRINCIPAL"));
        assertThat(principalId).isNotBlank();
        Session principal = api.login(s.school.code(), "pat" + s.domain, TestApi.PASSWORD);
        api.get("/api/frontoffice/gate-passes", principal.accessToken()).andExpect(status().isOk());
        api.post("/api/frontoffice/gate-passes/" + pass + "/approve", principal.accessToken(), null)
                .andExpect(status().isForbidden());
        api.post("/api/frontoffice/gate-passes/" + pass + "/approve", s.admin.accessToken(), null)
                .andExpect(status().isOk());
        api.get("/api/frontoffice/gate-passes", s.parent.accessToken()).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/frontoffice/gate")).andExpect(status().isUnauthorized());
        // Another school's admin, parent and guard see none of it.
        FrontOfficeSchool other = new FrontOfficeSchool(api);
        api.get("/api/frontoffice/gate-passes/" + pass, other.admin.accessToken()).andExpect(status().isNotFound());
        api.post("/api/frontoffice/gate-passes/" + pass + "/reject", other.admin.accessToken(), """
                {"note":"no"}""").andExpect(status().isNotFound());
        api.post("/api/frontoffice/gate-passes/" + pass + "/release", other.security.accessToken(),
                FrontOfficeSchool.releaseJson("123456")).andExpect(status().isNotFound());
        api.get("/api/frontoffice/gate-passes", other.admin.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/frontoffice/gate", other.security.accessToken())
                .andExpect(jsonPath("$.passes.length()").value(0));
        api.get("/api/me/children/" + s.asha + "/pickup", other.parent.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/frontoffice/students/" + s.asha + "/pickup-persons", other.office.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/messages?relatedId=" + pass, other.admin.accessToken()).andExpect(jsonPath("$.total").value(0));
    }
}
