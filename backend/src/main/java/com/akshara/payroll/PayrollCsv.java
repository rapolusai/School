package com.akshara.payroll;

import java.nio.charset.StandardCharsets;

/**
 * Writes the payroll CSV files. Text cells a spreadsheet would run as a formula are defused (a leading apostrophe);
 * amounts are rupees with two decimals. Report files start with a byte order mark so Excel reads them as UTF-8; the
 * bank file does not, because bank upload screens expect plain text.
 */
final class PayrollCsv {

    static final String CONTENT_TYPE = "text/csv;charset=UTF-8";
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** An amount in paise, written in rupees with two decimals ("12500.00"). */
    record Money(long paise) {

        @Override
        public String toString() {
            String sign = paise < 0 ? "-" : "";
            long abs = Math.abs(paise);
            return sign + (abs / 100) + "." + String.format("%02d", abs % 100);
        }
    }

    private final StringBuilder out = new StringBuilder();
    private int rows;

    PayrollCsv(String... header) {
        line((Object[]) header);
    }

    /** Adds a data row. Strings are text; {@link Money} and numbers are written as they are; null is empty. */
    PayrollCsv row(Object... cells) {
        rows++;
        return line(cells);
    }

    /** Data rows written so far (the header not counted). */
    int rows() {
        return rows;
    }

    private PayrollCsv line(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            Object c = cells[i];
            if (c == null) {
                continue;
            }
            out.append(c instanceof Money || c instanceof Number ? c.toString() : text(c.toString()));
        }
        out.append("\r\n");
        return this;
    }

    /** UTF-8 with a byte order mark, for spreadsheets. */
    byte[] forSpreadsheet() {
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, bytes, 0, BOM.length);
        System.arraycopy(body, 0, bytes, BOM.length, body.length);
        return bytes;
    }

    /** UTF-8 without a byte order mark, for upload screens. */
    byte[] plain() {
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
}
