package com.akshara.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditCsvTest {

    @Test
    void textThatASpreadsheetWouldRunIsDefusedAndCommasAndQuotesAreQuoted() {
        assertThat(AuditCsv.text("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(AuditCsv.text("+91 98")).isEqualTo("'+91 98");
        assertThat(AuditCsv.text("-1")).isEqualTo("'-1");
        assertThat(AuditCsv.text("@SUM")).isEqualTo("'@SUM");
        assertThat(AuditCsv.text("a,b")).isEqualTo("\"a,b\"");
        assertThat(AuditCsv.text("Asha Admin")).isEqualTo("Asha Admin");
        assertThat(new AuditCsv("A", "B").row("x", null).toString()).isEqualTo("A,B\r\nx,\r\n");
    }
}
