package com.akshara.govreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/** Code lists, the school profile, students' government fields (grid, import, family), schemes and permissions. */
class GovReportIT extends IntegrationTest {

    @Test
    void codeListsAreSeededPerSchoolAndTheOfficeCanAddAndEditCodes() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.get("/api/govreport/code-lists?list=SOCIAL_CATEGORY", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].code").value("GEN"))
                .andExpect(jsonPath("$[0].verify").value(true))
                .andExpect(jsonPath("$[0].inForce").value(true));
        api.get("/api/govreport/code-lists", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.post("/api/govreport/code-lists", s.office.accessToken(), """
                {"list":"SOCIAL_CATEGORY","code":"EWS","label":"EWS","value":"5","effectiveFrom":"2026-04-01",
                 "displayOrder":9}""").andExpect(status().isForbidden());
        String id = TestApi.read(api.post("/api/govreport/code-lists", s.principal.accessToken(), """
                {"list":"SOCIAL_CATEGORY","code":"EWS","label":"EWS","value":"5","effectiveFrom":"2026-04-01",
                 "displayOrder":9}""").andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("EWS")),
                "$.id");
        api.post("/api/govreport/code-lists", s.principal.accessToken(), """
                {"list":"SOCIAL_CATEGORY","code":"EWS","label":"Again","effectiveFrom":"2026-04-01",
                 "displayOrder":9}""").andExpect(status().isConflict());
        api.post("/api/govreport/code-lists", s.principal.accessToken(), """
                {"list":"SOCIAL_CATEGORY","code":"bad code","label":"X","effectiveFrom":"2026-04-01",
                 "displayOrder":9}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").exists());
        api.put("/api/govreport/code-lists/" + id, s.principal.accessToken(), """
                {"label":"Economically weaker section","value":"5","effectiveFrom":"2026-04-01","effectiveTo":null,
                 "verify":false,"active":true,"displayOrder":9}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Economically weaker section"))
                .andExpect(jsonPath("$.verify").value(false));
        api.put("/api/govreport/code-lists/" + UUID.randomUUID(), s.principal.accessToken(), """
                {"label":"X","effectiveFrom":"2026-04-01","verify":true,"active":true,"displayOrder":1}""")
                .andExpect(status().isNotFound());
        // Another school has its own lists: the new code is not there.
        GovReportSchool other = new GovReportSchool(api);
        api.get("/api/govreport/code-lists?list=SOCIAL_CATEGORY", other.admin.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(4)));
        api.put("/api/govreport/code-lists/" + id, other.admin.accessToken(), """
                {"label":"X","effectiveFrom":"2026-04-01","verify":true,"active":true,"displayOrder":1}""")
                .andExpect(status().isNotFound());
        String audit = api.get("/api/audit-events?limit=20", s.admin.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("govreport_code.added").contains("govreport_code.updated");
    }

    @Test
    void theSchoolProfileIsSavedPerYearWithCompletenessAndTeacherCounts() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.get("/api/govreport/profile", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.academicYearName").value("2026-27"))
                .andExpect(jsonPath("$.completeness.percent").value(0))
                .andExpect(jsonPath("$.teachers.total").value(1))
                .andExpect(jsonPath("$.teachers.profileIncomplete").exists());
        api.put("/api/govreport/profile", s.office.accessToken(), """
                {"udiseCode":"36011200912"}""").andExpect(status().isForbidden());
        api.put("/api/govreport/profile", s.principal.accessToken(), """
                {"udiseCode":"123"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.udiseCode").exists());
        api.put("/api/govreport/profile?yearId=" + s.yearId, s.principal.accessToken(), """
                {"udiseCode":"36011200912","board":"CBSE","affiliationNo":"3630412","schoolCategoryCode":"CAT3",
                 "managementCode":"PVT_UNAIDED","diseBlockCode":"3601","diseDistrictCode":"3601","stateCode":"TS",
                 "recognitionAuthority":"DSE","recognitionNo":"1182","lowestClass":"LKG","highestClass":"Class 10",
                 "mediumOfInstruction":"English","classrooms":24,"toiletsBoys":8,"toiletsGirls":8,"toiletsCwsn":2,
                 "drinkingWater":true,"electricity":true,"library":true,"playground":true,"computers":30,
                 "internet":true,"ramps":true,"boundaryWall":true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.udiseCode").value("36011200912"))
                .andExpect(jsonPath("$.completeness.percent").value(100))
                .andExpect(jsonPath("$.completeness.missing", hasSize(0)));
        api.put("/api/govreport/profile", s.principal.accessToken(), """
                {"udiseCode":"36011200912","board":"CBSE"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completeness.filled").value(2));
        api.get("/api/govreport/profile?yearId=" + UUID.randomUUID(), s.office.accessToken())
                .andExpect(status().isNotFound());
        String audit = api.get("/api/audit-events?limit=20", s.admin.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("govreport_profile.updated");
    }

    @Test
    void theGridSavesFieldsBySectionAndReportsCompleteness() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.get("/api/govreport/students?sectionId=" + s.section, s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students").value(3))
                .andExpect(jsonPath("$.complete").value(0))
                .andExpect(jsonPath("$.rows[0].missing", hasSize(8)));
        api.get("/api/govreport/students?sectionId=" + s.section, s.teacher.accessToken())
                .andExpect(status().isForbidden());
        api.get("/api/govreport/students?sectionId=" + UUID.randomUUID(), s.office.accessToken())
                .andExpect(status().isNotFound());
        api.put("/api/govreport/students", s.office.accessToken(), """
                {"entries":[]}""").andExpect(status().isForbidden());
        api.put("/api/govreport/students", s.principal.accessToken(), """
                {"entries":[{"studentId":"%s","apaarId":"12"}]}""".formatted(s.asha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['entries[0].apaarId']").exists());
        api.put("/api/govreport/students", s.principal.accessToken(), """
                {"entries":[{"studentId":"%s","socialCategory":"NOPE"}]}""".formatted(s.asha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['entries[0].socialCategory']").exists());
        api.put("/api/govreport/students", s.principal.accessToken(), """
                {"entries":[{"studentId":"%s"}]}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['entries[0].studentId']").exists());
        api.put("/api/govreport/students", s.principal.accessToken(), """
                {"entries":[
                  {"studentId":"%s","apaarId":"123456789012","pen":"TS1","socialCategory":"sc","religion":"HINDU",
                   "motherTongue":"TELUGU","bpl":false,"cwsnType":"","rteAdmitted":true},
                  {"studentId":"%s","socialCategory":"GEN","religion":"MUSLIM","motherTongue":"URDU","bpl":true,
                   "rteAdmitted":false}]}""".formatted(s.asha, s.bala))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(2))
                .andExpect(jsonPath("$.rows[0].socialCategory").value("SC"))
                .andExpect(jsonPath("$.rows[0].apaarLocked").value(true))
                .andExpect(jsonPath("$.rows[0].missing", hasSize(0)))
                .andExpect(jsonPath("$.rows[1].missing", hasSize(2)))
                .andExpect(jsonPath("$.rows[1].updatedVia").value("OFFICE"));
        api.get("/api/govreport/students?sectionId=" + s.section, s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(1));
        api.get("/api/govreport/students/completeness", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students").value(4))
                .andExpect(jsonPath("$.complete").value(1))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].missingByField.pen").value(2));
        // The other school cannot see or save these students.
        GovReportSchool other = new GovReportSchool(api);
        api.get("/api/govreport/students?sectionId=" + s.section, other.admin.accessToken())
                .andExpect(status().isNotFound());
        api.put("/api/govreport/students", other.admin.accessToken(), """
                {"entries":[{"studentId":"%s","pen":"X"}]}""".formatted(s.asha))
                .andExpect(status().isBadRequest());
        String audit = api.get("/api/audit-events?limit=20", s.admin.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("govreport_fields.saved");
    }

    @Test
    void theCsvImportValidatesEveryRowAndWritesNothingWhenOneIsBad() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.get("/api/govreport/students/template.csv", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
        String bad = """
                {"csv":"admission_no,apaar_id,pen,social_category,religion,mother_tongue,bpl,cwsn_type,rte_admitted\\r\\n\
                GOV-1,123456789012,TS1,SC,HINDU,TELUGU,N,,Y\\r\\nGOV-2,12,TS2,XX,HINDU,TELUGU,maybe,,N\\r\\n\
                NOPE-9,,,,,,,,\\r\\n"}""";
        api.post("/api/govreport/students/import?dryRun=true", s.principal.accessToken(), bad)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.validRows").value(1))
                .andExpect(jsonPath("$.invalidRows").value(2))
                .andExpect(jsonPath("$.errors[0].row").value(3))
                .andExpect(jsonPath("$.errors[?(@.column=='apaar_id')]").exists())
                .andExpect(jsonPath("$.errors[?(@.column=='social_category')]").exists())
                .andExpect(jsonPath("$.errors[?(@.column=='bpl')]").exists())
                .andExpect(jsonPath("$.errors[?(@.column=='admission_no')]").exists());
        api.post("/api/govreport/students/import", s.principal.accessToken(), bad)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.saved").value(0));
        api.get("/api/govreport/students?sectionId=" + s.section, s.office.accessToken())
                .andExpect(jsonPath("$.complete").value(0));
        api.post("/api/govreport/students/import", s.office.accessToken(), bad).andExpect(status().isForbidden());
        api.post("/api/govreport/students/import", s.principal.accessToken(), """
                {"csv":"nothing,here\\r\\n1,2\\r\\n"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.csv").exists());
        api.post("/api/govreport/students/import", s.principal.accessToken(), """
                {"csv":"admission_no,apaar_id,pen,social_category,religion,mother_tongue,bpl,rte_admitted\\r\\n\
                gov-1,123456789012,TS1,SC,HINDU,TELUGU,N,Y\\r\\nGOV-2,,TS2,GEN,MUSLIM,URDU,Y,N\\r\\n"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.saved").value(2));
        api.get("/api/govreport/students?sectionId=" + s.section, s.office.accessToken())
                .andExpect(jsonPath("$.complete").value(1))
                .andExpect(jsonPath("$.rows[0].updatedVia").value("IMPORT"))
                .andExpect(jsonPath("$.rows[1].religion").value("MUSLIM"));
        // A blank cell keeps the value; "-" clears it.
        api.post("/api/govreport/students/import", s.principal.accessToken(), """
                {"csv":"admission_no,pen,religion\\r\\nGOV-1,,-\\r\\n"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(1));
        api.get("/api/govreport/students?sectionId=" + s.section, s.office.accessToken())
                .andExpect(jsonPath("$.rows[0].pen").value("TS1"))
                .andExpect(jsonPath("$.rows[0].religion").doesNotExist());
        String audit = api.get("/api/audit-events?limit=20", s.admin.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("govreport_fields.imported");
    }

    @Test
    void aParentFillsTheMotherTongueAndTheApaarIdOnceAndTheOfficeCanLockIt() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.get("/api/me/govreport", s.parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children", hasSize(1)))
                .andExpect(jsonPath("$.children[0].fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.children[0].apaarEditable").value(true))
                .andExpect(jsonPath("$.motherTongues[0].code").exists());
        api.put("/api/me/govreport/children/" + s.asha, s.parent.accessToken(), """
                {"motherTongue":"NOPE"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.motherTongue").exists());
        api.put("/api/me/govreport/children/" + s.bala, s.parent.accessToken(), """
                {"motherTongue":"HINDI"}""").andExpect(status().isNotFound());
        api.put("/api/me/govreport/children/" + s.asha, s.parent.accessToken(), """
                {"motherTongue":"hindi","apaarId":"111111111111"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children[0].motherTongue").value("HINDI"))
                .andExpect(jsonPath("$.children[0].apaarId").value("111111111111"))
                .andExpect(jsonPath("$.children[0].apaarEditable").value(false));
        api.put("/api/me/govreport/children/" + s.asha, s.parent.accessToken(), """
                {"motherTongue":"HINDI","apaarId":"222222222222"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.apaarId").exists());
        api.get("/api/govreport/students?sectionId=" + s.section, s.office.accessToken())
                .andExpect(jsonPath("$.rows[0].updatedVia").value("FAMILY"))
                .andExpect(jsonPath("$.rows[0].apaarLocked").value(false));
        // The office locks the APAAR and changes it; the parent cannot any more.
        api.put("/api/govreport/students", s.principal.accessToken(), """
                {"entries":[{"studentId":"%s","apaarId":"333333333333","apaarLocked":true,"motherTongue":"HINDI"}]}"""
                .formatted(s.asha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].apaarLocked").value(true));
        api.put("/api/me/govreport/children/" + s.asha, s.parent.accessToken(), """
                {"motherTongue":"TELUGU","apaarId":"444444444444"}""").andExpect(status().isBadRequest());
        api.put("/api/me/govreport/children/" + s.asha, s.parent.accessToken(), """
                {"motherTongue":"TELUGU"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.children[0].motherTongue").value("TELUGU"))
                .andExpect(jsonPath("$.children[0].apaarId").value("333333333333"));
        api.get("/api/me/govreport", s.teacher.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.children", hasSize(0)));
        String audit = api.get("/api/audit-events?limit=20", s.admin.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("govreport_fields.family_updated");
    }

    @Test
    void theSchemeRegisterTracksEnrolmentsAndExportsPerScheme() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.post("/api/govreport/schemes", s.office.accessToken(), """
                {"studentId":"%s","academicYearId":"%s","schemeCode":"NMMS","status":"APPLIED"}"""
                .formatted(s.asha, s.yearId)).andExpect(status().isForbidden());
        api.post("/api/govreport/schemes", s.principal.accessToken(), """
                {"studentId":"%s","academicYearId":"%s","schemeCode":"NOPE","status":"APPLIED"}"""
                .formatted(s.asha, s.yearId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.schemeCode").exists());
        api.post("/api/govreport/schemes", s.principal.accessToken(), """
                {"studentId":"%s","academicYearId":"%s","schemeCode":"NMMS","status":"APPLIED"}"""
                .formatted(UUID.randomUUID(), s.yearId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentId").exists());
        String id = TestApi.read(api.post("/api/govreport/schemes", s.principal.accessToken(), """
                {"studentId":"%s","academicYearId":"%s","schemeCode":"NMMS","status":"APPLIED",
                 "amountPaise":1200000,"referenceNo":"NMMS/4471","appliedOn":"2026-07-01"}"""
                .formatted(s.asha, s.yearId)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.schemeLabel").exists()), "$.id");
        api.post("/api/govreport/schemes", s.principal.accessToken(), """
                {"studentId":"%s","academicYearId":"%s","schemeCode":"NMMS","status":"APPLIED"}"""
                .formatted(s.asha, s.yearId)).andExpect(status().isConflict());
        api.post("/api/govreport/schemes", s.principal.accessToken(), """
                {"studentId":"%s","academicYearId":"%s","schemeCode":"PRE_MATRIC_SC","status":"APPROVED",
                 "amountPaise":350000}""".formatted(s.bala, s.yearId)).andExpect(status().isCreated());
        api.put("/api/govreport/schemes/" + id, s.principal.accessToken(), """
                {"status":"RECEIVED","amountPaise":1200000,"referenceNo":"NMMS/4471","appliedOn":"2026-07-01"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
        api.get("/api/govreport/schemes", s.office.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        api.get("/api/govreport/schemes?schemeCode=NMMS", s.office.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        String csv = api.get("/api/govreport/schemes.csv?schemeCode=NMMS", s.office.accessToken())
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("﻿").contains("Asha Rao").contains("RECEIVED,12000").doesNotContain("Bala");
        api.get("/api/govreport/reports/schemes", s.office.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.students").value(2))
                .andExpect(jsonPath("$.amountPaise").value(1550000))
                .andExpect(jsonPath("$.rows", hasSize(2)));
        GovReportSchool other = new GovReportSchool(api);
        api.put("/api/govreport/schemes/" + id, other.admin.accessToken(), """
                {"status":"REJECTED"}""").andExpect(status().isNotFound());
        api.delete("/api/govreport/schemes/" + id, other.admin.accessToken()).andExpect(status().isNotFound());
        api.delete("/api/govreport/schemes/" + id, s.principal.accessToken()).andExpect(status().isNoContent());
        api.get("/api/govreport/schemes", s.office.accessToken()).andExpect(jsonPath("$", hasSize(1)));
        String audit = api.get("/api/audit-events?limit=20", s.admin.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("govreport_scheme.enrolled").contains("govreport_scheme.updated")
                .contains("govreport_scheme.removed");
    }

    @Test
    void permissionsFollowTheRoleCatalog() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        for (Session reader : new Session[] {s.admin, s.principal, s.office}) {
            api.get("/api/govreport/profile", reader.accessToken()).andExpect(status().isOk());
            api.get("/api/govreport/reports/completeness", reader.accessToken()).andExpect(status().isOk());
        }
        api.get("/api/govreport/profile", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/govreport/profile", s.parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/govreport/rte/intakes", s.parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/govreport/exports/udise/preview", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.put("/api/govreport/profile", s.admin.accessToken(), "{}").andExpect(status().isOk());
        api.put("/api/govreport/profile", s.office.accessToken(), "{}").andExpect(status().isForbidden());
        api.get("/api/roles", s.admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='FRONT_OFFICE')].permissions[*]").value(
                        org.hamcrest.Matchers.hasItem("govreport.read")))
                .andExpect(jsonPath("$[?(@.code=='PRINCIPAL')].permissions[*]").value(
                        org.hamcrest.Matchers.hasItem("govreport.manage")));
    }
}
