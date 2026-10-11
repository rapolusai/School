package com.akshara.lessons;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The lessons module's CSV downloads (syllabus coverage): UTF-8 with a byte order mark, so Excel opens names in Hindi
 * and other scripts correctly, and CRLF line ends. Text that a spreadsheet would run as a formula is defused; dates
 * are written as YYYY-MM-DD.
 */
final class LessonsCsv {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String BOM = "﻿";

    private final StringBuilder out = new StringBuilder(BOM);

    LessonsCsv(String... header) {
        row((Object[]) header);
    }

    /** Adds a row. Strings are text; numbers and dates are written as they are; null is empty. */
    LessonsCsv row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            Object c = cells[i];
            if (c == null) {
                continue;
            }
            out.append(c instanceof BigDecimal d ? d.toPlainString()
                    : c instanceof Number || c instanceof LocalDate ? c.toString() : text(c.toString()));
        }
        out.append("\r\n");
        return this;
    }

    byte[] bytes() {
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return out.toString();
    }

    /** Quotes when needed; text starting with = + - @ or a tab gets a leading apostrophe so it is never a formula. */
    static String text(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** A download of the CSV under the file name. */
    static ResponseEntity<byte[]> download(LessonsCsv csv, String fileName) {
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv.bytes());
    }
}
