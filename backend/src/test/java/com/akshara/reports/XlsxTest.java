package com.akshara.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The minimal .xlsx writer: package parts, well-formed XML, cell types, escaping, names and columns. */
class XlsxTest {

    static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    @Test
    void writesAWorkbookWithEveryPartExcelNeeds() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 9);
        byte[] bytes = Xlsx.write(List.of(
                new Xlsx.Sheet("Absentees")
                        .title("Sunrise <Public> School & Co")
                        .header("Student", "Days", "Percent", "Date", "Days off")
                        .row("Asha \"Rao\"", 3, 92.5, day, new BigDecimal("1.50"))
                        .row("=SUM(A1:A9)", null, null, null, null)
                        .total("Total", 3L, 100.0, null, BigDecimal.ZERO),
                new Xlsx.Sheet("By class").row("Class 5")));

        Map<String, String> parts = unzip(bytes);
        assertThat(parts.keySet()).containsExactly("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml");
        // Every part is well-formed XML.
        for (String xml : parts.values()) {
            parse(xml);
        }
        assertThat(parts.get("[Content_Types].xml"))
                .contains("PartName=\"/xl/worksheets/sheet2.xml\"")
                .contains("spreadsheetml.sheet.main+xml");
        assertThat(parts.get("xl/workbook.xml"))
                .contains("<sheet name=\"Absentees\" sheetId=\"1\" r:id=\"rId1\"/>")
                .contains("<sheet name=\"By class\" sheetId=\"2\" r:id=\"rId2\"/>");
        assertThat(parts.get("xl/_rels/workbook.xml.rels")).contains("Target=\"worksheets/sheet2.xml\"")
                .contains("Id=\"rId3\"").contains("Target=\"styles.xml\"");
        assertThat(parts.get("xl/styles.xml")).contains("formatCode=\"dd mmm yyyy\"");

        String sheet = parts.get("xl/worksheets/sheet1.xml");
        // Text is an inline string, never a formula; numbers and dates are real numbers.
        assertThat(sheet).doesNotContain("<f>")
                .contains("<c r=\"A4\" t=\"inlineStr\"><is><t xml:space=\"preserve\">=SUM(A1:A9)</t></is></c>")
                .contains("<c r=\"B3\"><v>3</v></c>")
                .contains("<c r=\"C3\"><v>92.5</v></c>")
                .contains("<c r=\"D3\" s=\"3\"><v>" + ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), day)
                        + "</v></c>")
                .contains("<c r=\"E3\"><v>1.5</v></c>")
                // Header and totals are bold (style 2), the title bigger (style 1).
                .contains("<c r=\"A1\" t=\"inlineStr\" s=\"1\">")
                .contains("<c r=\"A2\" t=\"inlineStr\" s=\"2\">")
                .contains("<c r=\"B5\" s=\"2\"><v>3</v></c>")
                .contains("<c r=\"C5\" s=\"2\"><v>100</v></c>")
                .contains("<c r=\"E5\" s=\"2\"><v>0</v></c>")
                .contains("<pageSetup paperSize=\"9\"");
        // Empty cells are left out.
        assertThat(sheet).doesNotContain("r=\"B4\"");

        Map<String, List<List<String>>> read = read(bytes);
        assertThat(read.keySet()).containsExactly("Absentees", "By class");
        List<List<String>> rows = read.get("Absentees");
        assertThat(rows.get(0)).containsExactly("Sunrise <Public> School & Co");
        assertThat(rows.get(2)).containsExactly("Asha \"Rao\"", "3", "92.5", "46304", "1.5");
        assertThat(rows.get(4)).containsExactly("Total", "3", "100", "", "0");
        assertThat(read.get("By class")).containsExactly(List.of("Class 5"));
    }

    @Test
    void dropsCharactersXmlCannotHoldAndKeepsEverythingElse() throws Exception {
        String text = "Ravi\u0001 Kumar\u0007\tटीचर 😀 & <b>";
        byte[] bytes = Xlsx.write(List.of(new Xlsx.Sheet("Names").row(text)));
        assertThat(read(bytes).get("Names").getFirst()).containsExactly("Ravi Kumar\tटीचर 😀 & <b>");
        assertThat(Xlsx.clean("a\uD800b\uDC00c")).isEqualTo("abc");
        assertThat(new String(unzip(bytes).get("xl/worksheets/sheet1.xml").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8)).contains("&amp; &lt;b&gt;");
    }

    @Test
    void namesColumnsAndSheetsAsExcelExpects() {
        assertThat(Xlsx.column(0)).isEqualTo("A");
        assertThat(Xlsx.column(25)).isEqualTo("Z");
        assertThat(Xlsx.column(26)).isEqualTo("AA");
        assertThat(Xlsx.column(51)).isEqualTo("AZ");
        assertThat(Xlsx.column(52)).isEqualTo("BA");
        assertThat(Xlsx.column(701)).isEqualTo("ZZ");
        assertThat(Xlsx.column(702)).isEqualTo("AAA");

        List<String> names = Xlsx.sheetNames(List.of(new Xlsx.Sheet("Attendance by class and section, July"),
                new Xlsx.Sheet("a/b:c*d?e[f]g\\h"), new Xlsx.Sheet("Funnel"), new Xlsx.Sheet("FUNNEL"),
                new Xlsx.Sheet("  "), new Xlsx.Sheet("'quoted'")));
        assertThat(names).containsExactly("Attendance by class and section", "a b c d e f g h", "Funnel",
                "FUNNEL (2)", "Sheet", "quoted");
        assertThat(names).allMatch(n -> n.length() <= 31);
    }

    @Test
    void refusesAnEmptyWorkbookAndNumbersThatAreNot() {
        assertThatThrownBy(() -> Xlsx.write(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Xlsx.write(List.of(new Xlsx.Sheet("X").row(Double.NaN))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ reading back, for this and the ITs

    static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Each sheet by name as rows of cell text (numbers as written, empty cells as ""), trailing blanks dropped. */
    static Map<String, List<List<String>>> read(byte[] bytes) throws Exception {
        Map<String, String> parts = unzip(bytes);
        NodeList sheets = parse(parts.get("xl/workbook.xml")).getElementsByTagNameNS(MAIN, "sheet");
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        for (int i = 0; i < sheets.getLength(); i++) {
            String name = ((Element) sheets.item(i)).getAttribute("name");
            Document sheet = parse(parts.get("xl/worksheets/sheet" + (i + 1) + ".xml"));
            List<List<String>> rows = new ArrayList<>();
            NodeList rowNodes = sheet.getElementsByTagNameNS(MAIN, "row");
            for (int r = 0; r < rowNodes.getLength(); r++) {
                Element row = (Element) rowNodes.item(r);
                int number = Integer.parseInt(row.getAttribute("r"));
                while (rows.size() < number - 1) {
                    rows.add(List.of());
                }
                List<String> cells = new ArrayList<>();
                NodeList cellNodes = row.getElementsByTagNameNS(MAIN, "c");
                for (int c = 0; c < cellNodes.getLength(); c++) {
                    Element cell = (Element) cellNodes.item(c);
                    int col = columnIndex(cell.getAttribute("r").replaceAll("[0-9]", ""));
                    while (cells.size() < col) {
                        cells.add("");
                    }
                    cells.add(cell.getTextContent());
                }
                rows.add(cells);
            }
            result.put(name, rows);
        }
        return result;
    }

    private static int columnIndex(String letters) {
        int n = 0;
        for (char ch : letters.toCharArray()) {
            n = n * 26 + (ch - 'A' + 1);
        }
        return n - 1;
    }
}
