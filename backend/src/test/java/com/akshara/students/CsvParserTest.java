package com.akshara.students;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class CsvParserTest {

    @Test
    void readsQuotedFieldsLineEndingsAndAByteOrderMark() {
        List<CsvParser.Row> rows = CsvParser.parse("﻿a,b,c\r\n"
                + "1,\"two, with comma\",\"say \"\"hi\"\"\"\n"
                + "x,\"multi\nline\",z\r"
                + "last,, \"spaced\"");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0)).isEqualTo(new CsvParser.Row(1, List.of("a", "b", "c")));
        assertThat(rows.get(1).values()).containsExactly("1", "two, with comma", "say \"hi\"");
        assertThat(rows.get(2)).isEqualTo(new CsvParser.Row(3, List.of("x", "multi\nline", "z")));
        // The quoted value spans lines 3 and 4, so the next record starts on line 5.
        assertThat(rows.get(3)).isEqualTo(new CsvParser.Row(5, List.of("last", "", "spaced")));
    }

    @Test
    void blankLinesAndEmptyInput() {
        assertThat(CsvParser.parse("")).isEmpty();
        assertThat(CsvParser.parse(null)).isEmpty();
        List<CsvParser.Row> rows = CsvParser.parse("a\n\nb\n");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).isBlank()).isTrue();
        assertThat(rows.get(2).line()).isEqualTo(3);
        assertThat(CsvParser.parse("a,").getFirst().values()).containsExactly("a", "");
    }

    @Test
    void malformedQuotesAreReportedWithTheirLine() {
        assertThatThrownBy(() -> CsvParser.parse("a,b\n\"open,c\nd"))
                .isInstanceOf(CsvParser.CsvException.class).hasMessage("Line 2: a quoted value is not closed.");
        assertThatThrownBy(() -> CsvParser.parse("a\n\"closed\"x,b"))
                .isInstanceOf(CsvParser.CsvException.class).hasMessageStartingWith("Line 2:");
    }

    @Test
    void phoneNumbersAreStoredAsTenDigits() {
        assertThat(Phones.normalize("+91 98765 00001")).isEqualTo("9876500001");
        assertThat(Phones.normalize("098765-00001")).isEqualTo("9876500001");
        assertThat(Phones.normalize("919876500001")).isEqualTo("9876500001");
        assertThat(Phones.normalize("9876500001")).isEqualTo("9876500001");
        assertThat(Phones.normalize("5876500001")).isNull();
        assertThat(Phones.normalize("98765")).isNull();
        assertThat(Phones.normalize("")).isNull();
        assertThat(Phones.normalize(null)).isNull();
    }

    @Test
    void importDatesAndCodesAreForgiving() {
        assertThat(StudentImportService.parseDate("2015-05-14")).isEqualTo(LocalDate.of(2015, 5, 14));
        assertThat(StudentImportService.parseDate("14-5-2015")).isEqualTo(LocalDate.of(2015, 5, 14));
        assertThat(StudentImportService.parseDate("14/05/2015")).isEqualTo(LocalDate.of(2015, 5, 14));
        assertThat(StudentImportService.parseDate("14.05.2015")).isEqualTo(LocalDate.of(2015, 5, 14));
        assertThat(StudentImportService.parseDate("31-02-2015")).isNull();
        assertThat(StudentImportService.parseDate("05/14/2015")).isNull();
        assertThat(StudentImportService.parseGender("f")).isEqualTo(Gender.FEMALE);
        assertThat(StudentImportService.parseGender("Other")).isEqualTo(Gender.OTHER);
        assertThat(StudentImportService.parseGender("x")).isNull();
        assertThat(StudentImportService.parseRelation("mother")).isEqualTo(GuardianRelation.MOTHER);
        assertThat(StudentImportService.parseRelation("aunt")).isNull();
    }
}
