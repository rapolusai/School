package com.akshara.certificates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Settings, templates, the issue flow (request, prepare, sign, cancel, correct), permissions and other schools. */
@RecordApplicationEvents
class CertificateIT extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    @Test
    void settingsAndTemplatesHaveDefaultsAndAreSavedWithAudit() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        api.get("/api/certificates/settings", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.signatoryDesignation").value("Principal"))
                .andExpect(jsonPath("$.cardValidMonths").value(12));
        api.put("/api/certificates/settings", s.office.accessToken(), """
                {"headerName":"Akshara Vidyalaya","headerAddress":"12 MG Road","affiliationNo":"CBSE 123",
                 "logoText":"AV","signatoryName":"Prem Principal","signatoryDesignation":"Principal",
                 "tcDuesCheck":true,"cardValidMonths":24,"lostCardFeePaise":10000}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.headerName").value("Akshara Vidyalaya"))
                .andExpect(jsonPath("$.cardValidMonths").value(24));
        api.put("/api/certificates/settings", s.office.accessToken(), """
                {"tcDuesCheck":true,"cardValidMonths":0,"lostCardFeePaise":0}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cardValidMonths").exists());
        api.get("/api/certificates/templates", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/certificates/templates", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(CertificateType.values().length)))
                .andExpect(jsonPath("$[?(@.type=='TRANSFER')].nextSerial").value("TC/2026-27/0001"))
                .andExpect(jsonPath("$[?(@.type=='TRANSFER')].custom").value(false))
                .andExpect(jsonPath("$[?(@.type=='EXPERIENCE')].staff").value(true));
        api.put("/api/certificates/templates/BONAFIDE", s.office.accessToken(), """
                {"title":"Bonafide","body":"{student} is a bonafide student of {class}.","serialPrefix":"bc"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.serialPrefix").exists());
        api.put("/api/certificates/templates/BONAFIDE", s.office.accessToken(), """
                {"title":"Bonafide","body":"{student} is a bonafide student of {class}.","serialPrefix":"BON"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(true))
                .andExpect(jsonPath("$.nextSerial").value("BON/2026-27/0001"));
        api.put("/api/certificates/templates/BONAFIDE", s.teacher.accessToken(), """
                {"title":"X","body":"Y","serialPrefix":"BC"}""").andExpect(status().isForbidden());
        String audit = api.get("/api/audit-events?limit=20", s.admin.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("certificate_settings.updated").contains("certificate_template.updated");
    }

    @Test
    void officePreparesPrincipalSignsAndOnlySignedCertificatesAreIssued() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        api.put("/api/certificates/settings", s.office.accessToken(), """
                {"headerName":"Akshara Vidyalaya","signatoryName":"Prem Principal","signatoryDesignation":"Principal",
                 "tcDuesCheck":true,"cardValidMonths":12,"lostCardFeePaise":0}""").andExpect(status().isOk());
        // Teachers cannot prepare; the office can, and the fields come prefilled.
        api.post("/api/certificates", s.teacher.accessToken(), """
                {"type":"BONAFIDE","studentId":"%s"}""".formatted(s.asha)).andExpect(status().isForbidden());
        api.post("/api/certificates", s.office.accessToken(), """
                {"type":"BONAFIDE","purpose":"Passport"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        api.post("/api/certificates", s.office.accessToken(), """
                {"type":"BONAFIDE","studentId":"%s","purpose":"Passport"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        String id = TestApi.read(api.post("/api/certificates", s.office.accessToken(), """
                {"type":"BONAFIDE","studentId":"%s","purpose":"Passport","fields":{"conduct":"excellent"}}"""
                .formatted(s.asha))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.serialNo").doesNotExist())
                .andExpect(jsonPath("$.verifyToken").doesNotExist())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.fields.student").value("Asha Rao"))
                .andExpect(jsonPath("$.fields.admissionNo").value("CRT-1"))
                .andExpect(jsonPath("$.fields.class").value("Class 5 A"))
                .andExpect(jsonPath("$.fields.mother").value("Lata Rao"))
                .andExpect(jsonPath("$.fields.conduct").value("excellent"))
                .andExpect(jsonPath("$.subject.name").value("Asha Rao"))
                .andExpect(jsonPath("$.school.name").value("Akshara Vidyalaya"))
                .andExpect(jsonPath("$.signatory.name").value("Prem Principal")), "$.id");
        // The preview body is rendered from the template; nothing is frozen yet.
        api.get("/api/certificates/" + id, s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/certificates/" + id, s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("Asha Rao")))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("{student}"))));
        // Edits: the office changes a value; a bad placeholder name is refused.
        api.put("/api/certificates/" + id, s.office.accessToken(), """
                {"title":"Bonafide Certificate","purpose":"Passport","fields":{"student":"Asha Rao",
                 "class":"Class 5 A","conduct":"good","bad key":"x"}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fields").exists());
        api.put("/api/certificates/" + id, s.office.accessToken(), """
                {"title":"Bonafide Certificate","purpose":"Passport","fields":{"student":"Asha Rao",
                 "admissionNo":"CRT-1","class":"Class 5 A","conduct":"good"}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.conduct").value("good"));
        // Only a signatory signs; the office cannot.
        api.post("/api/certificates/" + id + "/sign", s.office.accessToken(), "").andExpect(status().isForbidden());
        api.post("/api/certificates/" + id + "/sign", s.principal.accessToken(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.serialNo").value("BC/2026-27/0001"))
                .andExpect(jsonPath("$.academicYear").value("2026-27"))
                .andExpect(jsonPath("$.signedByName").value("Prem Principal"))
                .andExpect(jsonPath("$.verifyToken").isString())
                .andExpect(jsonPath("$.verifyPath").value(org.hamcrest.Matchers.startsWith("/verify/")))
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("Class 5 A")))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("{"))));
        // Once signed, nothing changes: no edit, no second signature.
        api.put("/api/certificates/" + id, s.office.accessToken(), """
                {"title":"Changed","fields":{}}""").andExpect(status().isConflict());
        api.post("/api/certificates/" + id + "/sign", s.principal.accessToken(), "").andExpect(status().isConflict());
        // Serial numbers run per type: the next bonafide is 0002, the first character certificate is 0001.
        String second = s.prepare("BONAFIDE", s.bala, "Bus pass");
        assertThat(s.sign(second)).isEqualTo("BC/2026-27/0002");
        assertThat(s.sign(s.prepare("CHARACTER", s.bala, "School change"))).isEqualTo("CC/2026-27/0001");
        api.get("/api/certificates?status=ISSUED&type=BONAFIDE", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].serialNo").value("BC/2026-27/0002"));
        api.get("/api/certificates?studentId=" + s.bala, s.office.accessToken())
                .andExpect(jsonPath("$.total").value(2));
        api.get("/api/certificates", s.teacher.accessToken()).andExpect(status().isForbidden());
        String audit = api.get("/api/audit-events?limit=30", s.admin.accessToken()).andReturn().getResponse()
                .getContentAsString();
        assertThat(audit).contains("certificate.prepared").contains("certificate.updated")
                .contains("certificate.signed");
    }

    @Test
    void correctionsCancelTheOldVersionAndMakeTheNextOne() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        String first = s.prepare("BONAFIDE", s.asha, "Passport");
        // Only an issued certificate can be corrected; a prepared one is cancelled instead.
        api.post("/api/certificates/" + first + "/correct", s.office.accessToken(), """
                {"reason":"Typo"}""").andExpect(status().isConflict());
        s.sign(first);
        api.post("/api/certificates/" + first + "/correct", s.teacher.accessToken(), """
                {"reason":"Typo"}""").andExpect(status().isForbidden());
        api.post("/api/certificates/" + first + "/correct", s.office.accessToken(), """
                {"reason":""}""").andExpect(status().isBadRequest());
        String second = TestApi.read(api.post("/api/certificates/" + first + "/correct", s.office.accessToken(), """
                {"reason":"Mother's name misspelt"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.supersedesId").value(first))
                .andExpect(jsonPath("$.fields.student").value("Asha Rao")), "$.id");
        api.get("/api/certificates/" + first, s.office.accessToken())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Corrected: Mother's name misspelt"))
                .andExpect(jsonPath("$.supersededById").value(second));
        // The old one cannot be corrected twice, nor cancelled again.
        api.post("/api/certificates/" + first + "/correct", s.office.accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());
        api.post("/api/certificates/" + first + "/cancel", s.office.accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());
        api.post("/api/certificates/" + second + "/sign", s.principal.accessToken(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNo").value("BC/2026-27/0002"))
                .andExpect(jsonPath("$.versionNo").value(2));
        // A plain cancellation of a prepared certificate, with its reason.
        String third = s.prepare("STUDY", s.chitra, "Visa");
        api.post("/api/certificates/" + third + "/cancel", s.office.accessToken(), """
                {"reason":"Not needed"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Not needed"));
        api.post("/api/certificates/" + third + "/sign", s.principal.accessToken(), "").andExpect(status().isConflict());
        String audit = api.get("/api/audit-events?limit=30", s.admin.accessToken()).andReturn().getResponse()
                .getContentAsString();
        assertThat(audit).contains("certificate.corrected").contains("certificate.cancelled");
    }

    @Test
    void transferCertificateMarksTheStudentAsLeftAndPublishesTheEvent() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        String id = TestApi.read(api.post("/api/certificates", s.office.accessToken(), """
                {"type":"TRANSFER","studentId":"%s","purpose":"Parent transferred",
                 "fields":{"reason":"Parent transferred to Pune","dateOfLeaving":"%s"}}"""
                .formatted(s.bala, today.minusDays(1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fields.workingDays").value("0"))
                .andExpect(jsonPath("$.fields.promotionStatus").exists()), "$.id");
        api.get("/api/students/" + s.bala, s.admin.accessToken()).andExpect(jsonPath("$.status").value("ACTIVE"));
        api.post("/api/certificates/" + id + "/sign", s.principal.accessToken(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNo").value("TC/2026-27/0001"));
        api.get("/api/students/" + s.bala, s.admin.accessToken())
                .andExpect(jsonPath("$.status").value("TRANSFERRED"))
                .andExpect(jsonPath("$.leftOn").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.leavingReason").value("Parent transferred to Pune"));
        java.util.List<TransferCertificateIssued> published = events.stream(TransferCertificateIssued.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().serialNo()).isEqualTo("TC/2026-27/0001");
        assertThat(published.getFirst().studentId()).isEqualTo(UUID.fromString(s.bala));
        assertThat(published.getFirst().tenantId()).isEqualTo(s.school.tenantId());
        // A student who left still gets certificates (a second TC after a correction, for instance).
        api.post("/api/certificates", s.office.accessToken(), """
                {"type":"BONAFIDE","studentId":"%s"}""".formatted(s.bala)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.subject.active").value(false));
    }

    @Test
    void staffCertificatesUseTheStaffRecord() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        api.post("/api/certificates", s.office.accessToken(), """
                {"type":"EXPERIENCE","studentId":"%s"}""".formatted(s.asha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.staffUserId").exists());
        String id = TestApi.read(api.post("/api/certificates", s.office.accessToken(), """
                {"type":"EXPERIENCE","staffUserId":"%s","purpose":"New job"}""".formatted(s.teacherId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subject.type").value("STAFF"))
                .andExpect(jsonPath("$.subject.name").value("Tara Teacher"))
                .andExpect(jsonPath("$.fields.employeeCode").value("EMP-7"))
                .andExpect(jsonPath("$.fields.designation").value("PGT Mathematics"))
                .andExpect(jsonPath("$.fields.dateOfJoining").value("2021-06-01")), "$.id");
        assertThat(s.sign(id)).isEqualTo("EXP/2026-27/0001");
        // A salary certificate without payroll set up still prepares, without the salary placeholders.
        api.post("/api/certificates", s.office.accessToken(), """
                {"type":"SALARY","staffUserId":"%s","purpose":"Loan"}""".formatted(s.teacherId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fields.grossSalary").doesNotExist());
    }

    @Test
    void otherSchoolsCertificatesAreInvisibleAndTheirStudentsUnusable() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        String id = s.prepare("BONAFIDE", s.asha, "Passport");
        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get("/api/certificates/" + id, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/certificates/" + id, otherAdmin.accessToken(), """
                {"title":"X","fields":{}}""").andExpect(status().isNotFound());
        api.post("/api/certificates/" + id + "/sign", otherAdmin.accessToken(), "").andExpect(status().isNotFound());
        api.post("/api/certificates/" + id + "/cancel", otherAdmin.accessToken(), """
                {"reason":"x"}""").andExpect(status().isNotFound());
        api.post("/api/certificates", otherAdmin.accessToken(), """
                {"type":"BONAFIDE","studentId":"%s"}""".formatted(s.asha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        api.get("/api/certificates", otherAdmin.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/certificates/templates", otherAdmin.accessToken())
                .andExpect(jsonPath("$[?(@.type=='BONAFIDE')].nextSerial").value("BC/2026-27/0001"));
    }

    @Test
    void reportsListIssuedAndPendingCertificatesWithCsvDownloads() throws Exception {
        CertificatesSchool s = new CertificatesSchool(api);
        s.sign(s.prepare("BONAFIDE", s.asha, "Passport"));
        s.sign(s.prepare("CHARACTER", s.bala, "Change"));
        s.prepare("STUDY", s.chitra, "Visa");
        api.post("/api/me/certificates/requests", s.parent.accessToken(), """
                {"studentId":"%s","type":"FEE","purpose":"Tax"}""".formatted(s.asha)).andExpect(status().isCreated());
        api.get("/api/certificates/reports/issued", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/certificates/reports/issued", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.byType", hasSize(2)))
                .andExpect(jsonPath("$.byType[0].type").value("BONAFIDE"))
                .andExpect(jsonPath("$.byType[0].count").value(1));
        api.get("/api/certificates/reports/issued?type=CHARACTER", s.office.accessToken())
                .andExpect(jsonPath("$.total").value(1));
        api.get("/api/certificates/reports/issued?from=2026-01-01&to=2025-01-01", s.office.accessToken())
                .andExpect(status().isBadRequest());
        byte[] csv = api.get("/api/certificates/reports/issued.csv", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(
                        "certificates-issued.csv")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(csv[0] & 0xFF).isEqualTo(0xEF);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains("Serial no,Type").contains("BC/2026-27/0001").contains("Asha Rao");
        api.get("/api/certificates/reports/pending", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.prepared").value(1))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[1].requestedVia").value("FAMILY"));
        String pending = new String(api.get("/api/certificates/reports/pending.csv", s.office.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(pending).contains("REQUESTED,FEE,Asha Rao").contains("PREPARED,STUDY,Chitra Das");
    }
}
