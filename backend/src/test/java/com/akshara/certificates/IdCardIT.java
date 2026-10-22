package com.akshara.certificates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** The ID card register: single cards, a section at once, reissues, photos, the family view and reports. */
class IdCardIT extends IntegrationTest {

    /** A 1x1 PNG. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Test
    void issuesCardsOneAtATimeAndForAWholeSection() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        api.post("/api/certificates/id-cards", s.teacher.accessToken(), """
                {"subjectType":"STUDENT","studentId":"%s"}""".formatted(s.asha)).andExpect(status().isForbidden());
        api.post("/api/certificates/id-cards", s.office.accessToken(), """
                {"subjectType":"STUDENT","studentId":"%s","bloodGroup":"X+"}""".formatted(s.asha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bloodGroup").exists());
        api.post("/api/certificates/id-cards", s.office.accessToken(), """
                {"subjectType":"STUDENT","studentId":"%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        String card = TestApi.read(api.post("/api/certificates/id-cards", s.office.accessToken(), """
                {"subjectType":"STUDENT","studentId":"%s","bloodGroup":"B+"}""".formatted(s.asha))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cardNo").value("ID/2026-27/0001"))
                .andExpect(jsonPath("$.issueNo").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.bloodGroup").value("B+"))
                .andExpect(jsonPath("$.emergencyPhone").value("9876501001"))
                .andExpect(jsonPath("$.validFrom").value(today.toString()))
                .andExpect(jsonPath("$.validUntil").value(today.plusMonths(12).toString()))
                .andExpect(jsonPath("$.subject.name").value("Asha Rao"))
                .andExpect(jsonPath("$.subject.detail").value("Class 5 A"))
                .andExpect(jsonPath("$.photoFileId").doesNotExist()), "$.id");
        // One card in use per person.
        api.post("/api/certificates/id-cards", s.office.accessToken(), """
                {"subjectType":"STUDENT","studentId":"%s"}""".formatted(s.asha)).andExpect(status().isConflict());
        // The rest of the section at once: Asha is skipped, Bala and Chitra get cards.
        api.get("/api/certificates/reports/id-cards-pending?sectionId=" + s.section, s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        api.post("/api/certificates/id-cards/batch", s.office.accessToken(), """
                {"sectionId":"%s","validUntil":"%s"}""".formatted(s.section, today.plusYears(1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.issued").value(2))
                .andExpect(jsonPath("$.cards[0].cardNo").value("ID/2026-27/0002"));
        api.post("/api/certificates/id-cards/batch", s.office.accessToken(), """
                {"sectionId":"%s"}""".formatted(s.section))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.issued").value(0));
        api.post("/api/certificates/id-cards/batch", s.office.accessToken(), "{}").andExpect(status().isBadRequest());
        api.get("/api/certificates/reports/id-cards-pending?sectionId=" + s.section, s.office.accessToken())
                .andExpect(jsonPath("$.total").value(0));
        api.get("/api/certificates/id-cards?sectionId=" + s.section, s.teacher.accessToken())
                .andExpect(status().isForbidden());
        api.get("/api/certificates/id-cards?sectionId=" + s.section, s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].subject.name").value("Asha Rao"))
                .andExpect(jsonPath("$.school.name").isString());
        api.get("/api/certificates/id-cards/" + card, s.office.accessToken())
                .andExpect(jsonPath("$.cardNo").value("ID/2026-27/0001"));
        // Staff cards take the emergency number from the staff profile.
        api.get("/api/certificates/reports/id-cards-pending?staff=true", s.office.accessToken())
                .andExpect(jsonPath("$.rows[?(@.subject.code=='EMP-7')]").exists());
        api.post("/api/certificates/id-cards", s.office.accessToken(), """
                {"subjectType":"STAFF","staffUserId":"%s","bloodGroup":"O-"}""".formatted(s.teacherId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subject.detail").value("PGT Mathematics"))
                .andExpect(jsonPath("$.emergencyPhone").value("9848054321"));
        api.get("/api/certificates/id-cards?subjectType=STAFF", s.office.accessToken())
                .andExpect(jsonPath("$.total").value(1));
        String csv = new String(api.get("/api/certificates/reports/id-cards-pending.csv?staff=true",
                s.office.accessToken()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Name,").doesNotContain("Tara Teacher");
        String audit = api.get("/api/audit-events?limit=30", s.admin.accessToken()).andReturn().getResponse()
                .getContentAsString();
        assertThat(audit).contains("id_card.issued").contains("id_card.batch_issued");
    }

    @Test
    void reissuesACardAndNotesTheLostCardFee() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        String first = TestApi.read(api.post("/api/certificates/id-cards", s.office.accessToken(), """
                {"subjectType":"STUDENT","studentId":"%s","bloodGroup":"A+"}""".formatted(s.asha))
                .andExpect(status().isCreated()), "$.id");
        api.post("/api/certificates/id-cards/" + first + "/reissue", s.teacher.accessToken(), """
                {"reason":"Lost"}""").andExpect(status().isForbidden());
        String second = TestApi.read(api.post("/api/certificates/id-cards/" + first + "/reissue",
                s.office.accessToken(), """
                {"reason":"Lost","lostFeePaise":5000,"emergencyPhone":"9876509999"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.issueNo").value(2))
                .andExpect(jsonPath("$.cardNo").value("ID/2026-27/0002"))
                .andExpect(jsonPath("$.replacesId").value(first))
                .andExpect(jsonPath("$.reissueReason").value("Lost"))
                .andExpect(jsonPath("$.lostFeePaise").value(5000))
                .andExpect(jsonPath("$.feeNote").isString())
                .andExpect(jsonPath("$.bloodGroup").value("A+"))
                .andExpect(jsonPath("$.emergencyPhone").value("9876509999")), "$.id");
        api.get("/api/certificates/id-cards/" + first, s.office.accessToken())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString());
        api.post("/api/certificates/id-cards/" + first + "/reissue", s.office.accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());
        api.get("/api/certificates/id-cards?sectionId=" + s.section, s.office.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second));
        api.get("/api/certificates/id-cards?sectionId=" + s.section + "&all=true", s.office.accessToken())
                .andExpect(jsonPath("$.total").value(2));
        String audit = api.get("/api/audit-events?limit=30", s.admin.accessToken()).andReturn().getResponse()
                .getContentAsString();
        assertThat(audit).contains("id_card.reissued");
    }

    @Test
    void photosAreUploadedByTheOfficeAndReadByTheFamily() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        api.get("/api/certificates/id-cards/photos/STUDENT/" + s.asha, s.office.accessToken())
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.multipart("/api/certificates/id-cards/photos/STUDENT/" + s.asha)
                .file(new MockMultipartFile("file", "asha.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + s.office.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").exists());
        mvc.perform(MockMvcRequestBuilders.multipart("/api/certificates/id-cards/photos/STUDENT/" + s.asha)
                .file(new MockMultipartFile("file", "asha.png", "image/png", PNG))
                .header("Authorization", "Bearer " + s.teacher.accessToken()))
                .andExpect(status().isForbidden());
        String fileId = TestApi.read(mvc.perform(MockMvcRequestBuilders.multipart(
                "/api/certificates/id-cards/photos/STUDENT/" + s.asha)
                .file(new MockMultipartFile("file", "asha.png", "image/png", PNG))
                .header("Authorization", "Bearer " + s.office.accessToken()))
                .andExpect(status().isCreated()), "$.fileId");
        // A second upload replaces the first.
        String replaced = TestApi.read(mvc.perform(MockMvcRequestBuilders.multipart(
                "/api/certificates/id-cards/photos/STUDENT/" + s.asha)
                .file(new MockMultipartFile("file", "asha2.png", "image/png", PNG))
                .header("Authorization", "Bearer " + s.office.accessToken()))
                .andExpect(status().isCreated()), "$.fileId");
        api.get("/api/files/" + fileId, s.office.accessToken()).andExpect(status().isNotFound());
        api.get("/api/files/" + replaced, s.office.accessToken()).andExpect(status().isOk());
        api.get("/api/files/" + replaced, s.parent.accessToken()).andExpect(status().isOk());
        api.get("/api/files/" + replaced, s.teacher.accessToken()).andExpect(status().isNotFound());
        api.post("/api/certificates/id-cards", s.office.accessToken(), """
                {"subjectType":"STUDENT","studentId":"%s"}""".formatted(s.asha))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photoFileId").value(replaced));
        api.get("/api/me/certificates/id-cards", s.parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].photoFileId").value(replaced))
                .andExpect(jsonPath("$[0].emergencyPhone").value("XXXXXX1001"))
                .andExpect(jsonPath("$[0].issuedByName").doesNotExist());
        // Someone else's photo is a 404 for another school's office.
        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get("/api/files/" + replaced, otherAdmin.accessToken()).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.multipart("/api/certificates/id-cards/photos/STUDENT/" + s.asha)
                .file(new MockMultipartFile("file", "asha.png", "image/png", PNG))
                .header("Authorization", "Bearer " + otherAdmin.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherSchoolsCardsAreInvisible() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        String card = TestApi.read(api.post("/api/certificates/id-cards", s.office.accessToken(), """
                {"subjectType":"STUDENT","studentId":"%s"}""".formatted(s.asha))
                .andExpect(status().isCreated()), "$.id");
        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get("/api/certificates/id-cards/" + card, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.post("/api/certificates/id-cards/" + card + "/reissue", otherAdmin.accessToken(), """
                {"reason":"Lost"}""").andExpect(status().isNotFound());
        api.get("/api/certificates/id-cards?sectionId=" + s.section, otherAdmin.accessToken())
                .andExpect(status().isNotFound());
        api.post("/api/certificates/id-cards/batch", otherAdmin.accessToken(), """
                {"sectionId":"%s"}""".formatted(s.section))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sectionId").exists());
        api.get("/api/certificates/id-cards?subjectType=STUDENT", otherAdmin.accessToken())
                .andExpect(jsonPath("$.total").value(0));
    }
}
