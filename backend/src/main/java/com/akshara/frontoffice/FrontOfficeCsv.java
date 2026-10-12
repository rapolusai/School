package com.akshara.frontoffice;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The front office's report downloads: CSV in UTF-8 with a byte order mark (so Excel opens Hindi names correctly)
 * and CRLF line ends. Text that a spreadsheet would run as a formula is defused; dates are written as YYYY-MM-DD and
 * instants in India time.
 */
final class FrontOfficeCsv {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String BOM = "﻿";

    private final StringBuilder out = new StringBuilder(BOM);

    FrontOfficeCsv(String... header) {
        row((Object[]) header);
    }

    /** Adds a row. Numbers and dates are written as they are, instants in India time; null is empty. */
    FrontOfficeCsv row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            Object c = cells[i];
            if (c == null) {
                continue;
            }
            if (c instanceof Instant at) {
                out.append(FrontOfficeText.time(at));
            } else {
                out.append(c instanceof Number || c instanceof LocalDate ? c.toString() : text(c.toString()));
            }
        }
        out.append("\r\n");
        return this;
    }

    byte[] bytes() {
        return out.toString().getBytes(StandardCharsets.UTF_8);
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

    static ResponseEntity<byte[]> download(FrontOfficeCsv csv, String fileName) {
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv.bytes());
    }
}
