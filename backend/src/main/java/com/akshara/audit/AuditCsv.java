package com.akshara.audit;

/** Writes audit trails as CSV. Text cells that a spreadsheet would run as a formula are defused. */
final class AuditCsv {

    private final StringBuilder out = new StringBuilder();

    AuditCsv(String... header) {
        row((Object[]) header);
    }

    AuditCsv row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            if (cells[i] != null) {
                out.append(text(cells[i].toString()));
            }
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
