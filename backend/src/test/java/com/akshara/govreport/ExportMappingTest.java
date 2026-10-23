package com.akshara.govreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akshara.students.Gender;

/** The export plumbing: CSV cells, the import parser and template, gender codes and the phone mask. */
class ExportMappingTest {

    @Test
    void csvHasABomCrlfAndPortalFriendlyCells() {
        String csv = new GovCsv("Admission no", "Name", "BPL", "DOB", "Km")
                .row("ADM-1", "Rao, Asha", true, LocalDate.of(2019, 5, 1), new BigDecimal("1.50"))
                .row("=cmd", null, false, null, null)
                .toString();
        assertThat(csv).startsWith("﻿");
        assertThat(csv).endsWith("\r\n");
        assertThat(List.of(csv.substring(1).split("\r\n"))).containsExactly("Admission no,Name,BPL,DOB,Km", "ADM-1,\"Rao, Asha\",Y,2019-05-01,1.5",
                "'=cmd,,N,,");
    }

    @Test
    void theImportParserReadsQuotesBomsAndLineNumbers() {
        List<GovCsvParser.Row> rows = GovCsvParser.parse("﻿admission_no,apaar_id\r\n\"A, 1\",\"12\"\"3\"\n\n"
                + "B-2,\n");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).values()).containsExactly("admission_no", "apaar_id");
        assertThat(rows.get(1).values()).containsExactly("A, 1", "12\"3");
        assertThat(rows.get(1).line()).isEqualTo(2);
        assertThat(rows.get(2).isBlank()).isTrue();
        assertThat(rows.get(3).line()).isEqualTo(4);
        assertThat(rows.get(3).values()).containsExactly("B-2", "");
    }

    @Test
    void theImportColumnsMatchTheGridFields() {
        assertThat(StudentFieldsService.IMPORT_COLUMNS).containsExactly("admission_no", "apaar_id", "pen",
                "social_category", "religion", "mother_tongue", "bpl", "cwsn_type", "rte_admitted",
                "previous_school_udise");
        // Every required grid field has a column, named in snake case.
        for (String field : StudentFieldsService.REQUIRED) {
            String column = field.replaceAll("([A-Z])", "_$1").toLowerCase();
            assertThat(StudentFieldsService.IMPORT_COLUMNS).contains(column);
        }
    }

    @Test
    void genderCodesAndPhoneMasksAreWhatThePortalsAndListsExpect() {
        assertThat(GovReportReportService.genderIndex(Gender.MALE)).isZero();
        assertThat(GovReportReportService.genderIndex(Gender.FEMALE)).isEqualTo(1);
        assertThat(GovReportReportService.genderIndex(Gender.OTHER)).isEqualTo(2);
        assertThat(GovReportReportService.genderIndex(null)).isEqualTo(2);
        assertThat(RteService.maskPhone("+91 98765 43210")).isEqualTo("XXXXXXXX3210");
        assertThat(RteService.maskPhone("123")).isEqualTo("XXX");
        assertThat(RteService.maskPhone(null)).isNull();
    }

    @Test
    void udiseTablesAreTheFiveDcfSections() {
        assertThat(GovExportService.UDISE_TABLES).containsExactly("school", "enrolment", "teachers",
                "infrastructure", "facilities");
        assertThat(GovExportService.VERIFY).contains("Verify").contains("portal template");
    }
}
