package com.akshara.transport;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The transport report downloads: CSV in UTF-8 with a byte order mark, so Excel opens names in Hindi and other scripts
 * correctly, and CRLF line ends. Text that a spreadsheet would run as a formula is defused; amounts are written in
 * rupees with two decimals, dates as YYYY-MM-DD and times as HH:MM.
 */
final class TransportCsv {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String BOM = "﻿";

    /** An amount in paise, written in rupees ("12.50"). */
    record Rupees(long paise) {

        @Override
        public String toString() {
            String sign = paise < 0 ? "-" : "";
            long abs = Math.abs(paise);
            return sign + (abs / 100) + "." + String.format("%02d", abs % 100);
        }
    }

    private final StringBuilder out = new StringBuilder(BOM);

    TransportCsv(String... header) {
        row((Object[]) header);
    }

    /** Adds a row. Strings are text; numbers, {@link Rupees}, dates and times are written as they are; null is empty. */
    TransportCsv row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            Object c = cells[i];
            if (c == null) {
                continue;
            }
            if (c instanceof LocalTime t) {
                out.append(String.format("%02d:%02d", t.getHour(), t.getMinute()));
            } else if (c instanceof Rupees || c instanceof Number || c instanceof LocalDate) {
                out.append(c);
            } else {
                out.append(text(c.toString()));
            }
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
    static ResponseEntity<byte[]> download(TransportCsv csv, String fileName) {
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv.bytes());
    }
}
