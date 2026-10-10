package com.akshara.reports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A minimal Excel workbook (.xlsx, Office Open XML SpreadsheetML) written with java.util.zip, so reports open in
 * Excel, LibreOffice and Google Sheets with real numbers and dates. Text is stored as inline strings, never as
 * formulas, so a cell such as "=1+1" from a name field stays text. Only what the reports need: several sheets, bold
 * and title rows, whole and decimal numbers, dates shown as DD MMM YYYY, and column widths fitted to the content.
 */
final class Xlsx {

    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Excel's day zero for the 1900 date system (the serial numbers it shows for dates). */
    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);
    private static final int MAX_SHEET_NAME = 31;

    /** Cell formats, by their index in styles.xml. */
    enum Style {
        NORMAL(0), TITLE(1), BOLD(2), DATE(3), BOLD_DATE(4);

        final int index;

        Style(int index) {
            this.index = index;
        }
    }

    private record Row(Style style, List<Object> cells) {
    }

    /** One worksheet, built row by row. A cell is a String, a Number, a LocalDate or null (empty). */
    static final class Sheet {

        private final String name;
        private final List<Row> rows = new ArrayList<>();

        Sheet(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        Sheet title(String text) {
            rows.add(new Row(Style.TITLE, List.of(text)));
            return this;
        }

        Sheet line(String text) {
            rows.add(new Row(Style.NORMAL, List.of(text)));
            return this;
        }

        Sheet blank() {
            rows.add(new Row(Style.NORMAL, List.of()));
            return this;
        }

        Sheet header(String... names) {
            rows.add(new Row(Style.BOLD, Arrays.asList((Object[]) names)));
            return this;
        }

        Sheet row(Object... cells) {
            return row(Arrays.asList(cells));
        }

        Sheet row(List<?> cells) {
            rows.add(new Row(Style.NORMAL, new ArrayList<>(cells)));
            return this;
        }

        /** A totals row, in bold. */
        Sheet total(List<?> cells) {
            rows.add(new Row(Style.BOLD, new ArrayList<>(cells)));
            return this;
        }

        Sheet total(Object... cells) {
            return total(Arrays.asList(cells));
        }

        int rowCount() {
            return rows.size();
        }
    }

    private Xlsx() {
    }

    /** The workbook as the bytes of an .xlsx file. */
    static byte[] write(List<Sheet> sheets) {
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("A workbook needs at least one sheet");
        }
        List<String> names = sheetNames(sheets);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" \
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" \
                    Target="xl/workbook.xml"/></Relationships>""");
            put(zip, "xl/workbook.xml", workbook(names));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size()));
            put(zip, "xl/styles.xml", STYLES);
            for (int i = 0; i < sheets.size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", worksheet(sheets.get(i)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String path, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes(int sheets) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/xl/workbook.xml" \
                ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                <Override PartName="/xl/styles.xml" \
                ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""");
        for (int i = 1; i <= sheets; i++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"")
                    .append("application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return xml.append("</Types>").toString();
    }

    private static String workbook(List<String> names) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""");
        for (int i = 0; i < names.size(); i++) {
            xml.append("<sheet name=\"").append(escape(names.get(i))).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return xml.append("</sheets></workbook>").toString();
    }

    private static String workbookRels(int sheets) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""");
        for (int i = 1; i <= sheets; i++) {
            xml.append("<Relationship Id=\"rId").append(i).append("\" Type=\"")
                    .append("http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" ")
                    .append("Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        xml.append("<Relationship Id=\"rId").append(sheets + 1).append("\" Type=\"")
                .append("http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" ")
                .append("Target=\"styles.xml\"/>");
        return xml.append("</Relationships>").toString();
    }

    /** Normal, title (bold 14 pt), bold, date, bold date. Dates show as "09 Oct 2026". */
    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
            <numFmts count="1"><numFmt numFmtId="164" formatCode="dd mmm yyyy"/></numFmts>\
            <fonts count="3"><font><sz val="11"/><name val="Calibri"/></font>\
            <font><b/><sz val="11"/><name val="Calibri"/></font>\
            <font><b/><sz val="14"/><name val="Calibri"/></font></fonts>\
            <fills count="2"><fill><patternFill patternType="none"/></fill>\
            <fill><patternFill patternType="gray125"/></fill></fills>\
            <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>\
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
            <cellXfs count="5">\
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
            <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>\
            <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>\
            <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
            <xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>\
            </cellXfs>\
            <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>\
            </styleSheet>""";

    private static String worksheet(Sheet sheet) {
        StringBuilder data = new StringBuilder();
        int[] widths = new int[0];
        int r = 0;
        for (Row row : sheet.rows) {
            r++;
            data.append("<row r=\"").append(r).append("\">");
            for (int c = 0; c < row.cells().size(); c++) {
                Object value = row.cells().get(c);
                if (value == null) {
                    continue;
                }
                String ref = column(c) + r;
                int width;
                if (value instanceof LocalDate d) {
                    Style style = row.style() == Style.NORMAL ? Style.DATE : Style.BOLD_DATE;
                    data.append("<c r=\"").append(ref).append("\" s=\"").append(style.index).append("\"><v>")
                            .append(ChronoUnit.DAYS.between(EPOCH, d)).append("</v></c>");
                    width = 12;
                } else if (value instanceof Number n) {
                    String number = number(n);
                    data.append("<c r=\"").append(ref).append('"').append(styleAttribute(row.style()))
                            .append("><v>").append(number).append("</v></c>");
                    width = number.length() + 2;
                } else {
                    String text = clean(value.toString());
                    data.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(styleAttribute(row.style()))
                            .append("><is><t xml:space=\"preserve\">").append(escape(text)).append("</t></is></c>");
                    // Title and filter lines run across the empty cells to their right; they do not set widths.
                    width = row.cells().size() == 1 && row.style() != Style.BOLD ? 0 : text.length() + 2;
                }
                if (c >= widths.length) {
                    widths = Arrays.copyOf(widths, c + 1);
                }
                widths[c] = Math.max(widths[c], Math.min(width, 50));
            }
            data.append("</row>");
        }
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""");
        if (widths.length > 0) {
            xml.append("<cols>");
            for (int c = 0; c < widths.length; c++) {
                xml.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                        .append(Math.max(widths[c], 10)).append("\" customWidth=\"1\"/>");
            }
            xml.append("</cols>");
        }
        xml.append("<sheetData>").append(data).append("</sheetData>")
                .append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" ")
                .append("footer=\"0.3\"/><pageSetup paperSize=\"9\" orientation=\"portrait\"/></worksheet>");
        return xml.toString();
    }

    private static String styleAttribute(Style style) {
        return style == Style.NORMAL ? "" : " s=\"" + style.index + "\"";
    }

    private static String number(Number n) {
        if (n instanceof BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("Not a number: " + d);
            }
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return Long.toString(n.longValue());
    }

    /** A, B, ... Z, AA, AB, ... for a zero-based column index. */
    static String column(int index) {
        StringBuilder name = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            name.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return name.toString();
    }

    /**
     * Sheet names as Excel accepts them: at most 31 characters, none of {@code []:*?/\}, not blank, and unique
     * (ignoring case).
     */
    static List<String> sheetNames(List<Sheet> sheets) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Sheet sheet : sheets) {
            String base = clean(sheet.name() == null ? "" : sheet.name()).replaceAll("[\\[\\]:*?/\\\\]", " ").strip();
            base = base.replaceAll("^'+|'+$", "");
            if (base.isEmpty()) {
                base = "Sheet";
            }
            if (base.length() > MAX_SHEET_NAME) {
                base = base.substring(0, MAX_SHEET_NAME).strip();
            }
            String name = base;
            for (int i = 2; !seen.add(name.toLowerCase(Locale.ROOT)); i++) {
                String suffix = " (" + i + ")";
                name = (base.length() + suffix.length() > MAX_SHEET_NAME
                        ? base.substring(0, MAX_SHEET_NAME - suffix.length()) : base) + suffix;
            }
            names.add(name);
        }
        return names;
    }

    /** Drops characters XML 1.0 cannot hold (control characters other than tab and line breaks, lone surrogates). */
    static String clean(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                out.append(ch).append(text.charAt(++i));
            } else if (ch == '\t' || ch == '\n' || ch == '\r' || (ch >= 0x20 && ch <= 0xD7FF)
                    || (ch >= 0xE000 && ch <= 0xFFFD)) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
