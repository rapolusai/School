package com.akshara.certificates;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The module's report downloads: CSV in UTF-8 with a byte order mark (so Excel opens Hindi names correctly) and
 * CRLF line ends. Text a spreadsheet would run as a formula is defused; dates are YYYY-MM-DD.
 */
final class CertificateCsv {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String BOM = "﻿";

    private final StringBuilder out = new StringBuilder(BOM);

    CertificateCsv(String... header) {
        row((Object[]) header);
    }

    CertificateCsv row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            Object c = cells[i];
            if (c == null) {
                continue;
            }
            out.append(c instanceof Number || c instanceof LocalDate ? c.toString() : text(c.toString()));
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

    static ResponseEntity<byte[]> download(CertificateCsv csv, String fileName) {
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv.bytes());
    }
}
