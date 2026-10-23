package com.akshara.govreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;

/** The UDISE+, OASIS and CBSE registration exports, their audit, and the Government reports. */
class GovExportIT extends IntegrationTest {

    @Test
    void udisePreviewWorkbookAndTablesAreLabelledAndAudited() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.put("/api/govreport/students", s.principal.accessToken(), """
                {"entries":[{"studentId":"%s","socialCategory":"SC","bpl":true,"rteAdmitted":true}]}"""
                .formatted(s.asha)).andExpect(status().isOk());
        api.get("/api/govreport/exports/udise/preview?asOf=2026-09-30", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("UDISE_XLSX"))
                .andExpect(jsonPath("$.verify").value(true))
                .andExpect(jsonPath("$.asOf").value("2026-09-30"))
                .andExpect(jsonPath("$.tables", hasSize(5)))
                .andExpect(jsonPath("$.tables[1].key").value("enrolment"))
                .andExpect(jsonPath("$.tables[1].columns[0]").value("Class"))
                .andExpect(jsonPath("$.tables[1].columns[4]").value("Total"));
        byte[] xlsx = api.get("/api/govreport/exports/udise.xlsx", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx[0]).isEqualTo((byte) 'P');
        assertThat(xlsx[1]).isEqualTo((byte) 'K');
        String csv = api.get("/api/govreport/exports/udise/enrolment.csv", s.office.accessToken())
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("﻿Class,Boys,Girls,Other,Total").contains("Class 1").contains("Total");
        api.get("/api/govreport/exports/udise/nope.csv", s.office.accessToken()).andExpect(status().isNotFound());
        api.get("/api/govreport/exports/udise/preview?yearId=" + UUID.randomUUID(), s.office.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/govreport/exports/udise.xlsx", s.teacher.accessToken()).andExpect(status().isForbidden());
        String audit = api.get("/api/audit-events?limit=20", s.admin.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("govreport_export.downloaded").contains("UDISE_XLSX").contains("UDISE_CSV");
    }

    @Test
    void oasisAndCbseRegistrationExports() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.get("/api/govreport/exports/oasis/preview", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("OASIS_XLSX"))
                .andExpect(jsonPath("$.tables[?(@.key=='staff')]").exists())
                .andExpect(jsonPath("$.tables[?(@.key=='fees')]").exists());
        api.get("/api/govreport/exports/oasis.xlsx", s.office.accessToken()).andExpect(status().isOk());
        api.get("/api/govreport/exports/cbse-registration/classes", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Class 9"));
        api.get("/api/govreport/exports/cbse-registration/preview", s.office.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.classId").exists());
        api.get("/api/govreport/exports/cbse-registration/preview?classId=" + UUID.randomUUID(),
                s.office.accessToken()).andExpect(status().isNotFound());
        api.get("/api/govreport/exports/cbse-registration/preview?classId=" + s.class9, s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].rows", hasSize(1)))
                .andExpect(jsonPath("$.tables[0].rows[0][2]").value("DEV NAIR"));
        api.get("/api/govreport/exports/cbse-registration.xlsx?classId=" + s.class9, s.office.accessToken())
                .andExpect(status().isOk());
        GovReportSchool other = new GovReportSchool(api);
        api.get("/api/govreport/exports/cbse-registration/preview?classId=" + s.class9, other.admin.accessToken())
                .andExpect(status().isNotFound());
    }

    @Test
    void governmentReportsAsJsonAndCsv() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.put("/api/govreport/students", s.principal.accessToken(), """
                {"entries":[{"studentId":"%s","socialCategory":"SC"},{"studentId":"%s","socialCategory":"GEN"}]}"""
                .formatted(s.asha, s.bala)).andExpect(status().isOk());
        api.get("/api/govreport/reports/enrolment", s.office.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.total").value(4))
                .andExpect(jsonPath("$.totals.byCategory.SC").value(1))
                .andExpect(jsonPath("$.totals.byCategory.NOT_RECORDED").value(2));
        for (String r : new String[] {"completeness", "enrolment", "rte", "schemes"}) {
            String csv = api.get("/api/govreport/reports/" + r + ".csv", s.office.accessToken())
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(csv).as(r).startsWith("﻿");
            api.get("/api/govreport/reports/" + r, s.teacher.accessToken()).andExpect(status().isForbidden());
        }
    }
}
