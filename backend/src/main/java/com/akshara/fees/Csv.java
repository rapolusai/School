package com.akshara.fees;

/**
 * Writes CSV files for spreadsheets and Tally. Text cells that a spreadsheet would run as a formula are defused;
 * numbers and amounts are written as they are.
 */
final class Csv {

    /** An amount in paise, written in rupees with two decimals ("12500.00", "-450.50"). */
    record Money(long paise) {

        @Override
        public String toString() {
            String sign = paise < 0 ? "-" : "";
            long abs = Math.abs(paise);
            return sign + (abs / 100) + "." + String.format("%02d", abs % 100);
        }
    }

    private final StringBuilder out = new StringBuilder();

    Csv(String... header) {
        row((Object[]) header);
    }

    /** Adds a row. Strings are text; {@link Money} and numbers are written unquoted; null is an empty cell. */
    Csv row(Object... cells) {
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
}
