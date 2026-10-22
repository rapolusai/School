package com.akshara.accounts;

import java.nio.charset.StandardCharsets;

/**
 * Writes the accounts reports as CSV for Excel: UTF-8 with a byte order mark, amounts in rupees with two decimals,
 * and text a spreadsheet would run as a formula defused with a leading apostrophe.
 */
final class AccountsCsv {

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

    /** A file to download. */
    record Download(String fileName, String contentType, byte[] content) {
    }

    private final StringBuilder out = new StringBuilder();

    AccountsCsv(String... header) {
        line((Object[]) header);
    }

    AccountsCsv row(Object... cells) {
        return line(cells);
    }

    private AccountsCsv line(Object... cells) {
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

    Download download(String fileName) {
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, bytes, 0, BOM.length);
        System.arraycopy(body, 0, bytes, BOM.length, body.length);
        return new Download(fileName, CONTENT_TYPE, bytes);
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
