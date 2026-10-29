package com.akshara.bulkimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.akshara.bulkimport.XlsxReader.Limits;
import com.akshara.bulkimport.XlsxReader.Sheet;
import com.akshara.bulkimport.XlsxReader.XlsxException;
import com.akshara.reports.Xlsx;

class XlsxReaderTest {

    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RNS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private final XlsxReader reader = new XlsxReader();

    // ------------------------------------------------------------------ reading what Excel writes

    @Test
    void readsWhatTheReportsWriterWrites() {
        byte[] file = Xlsx.write(List.of(
                new Xlsx.Sheet("Class 5 - A").header("Admission no", "Roll no", "Date of birth", "Mobile")
                        .row("A/1", 3, LocalDate.of(2015, 5, 14), 9876543210L)
                        .row("=1+1", null, null, new java.math.BigDecimal("2.50"))
                        .blank(),
                new Xlsx.Sheet("Staff").header("Name").row("Asha & <Co> \"Rao\"")));

        List<Sheet> sheets = reader.read(file);

        assertThat(sheets).extracting(Sheet::name).containsExactly("Class 5 - A", "Staff");
        List<XlsxReader.Row> rows = sheets.getFirst().rows();
        assertThat(rows.get(0).cells()).containsExactly("Admission no", "Roll no", "Date of birth", "Mobile");
        assertThat(rows.get(1).cells()).containsExactly("A/1", "3", "2015-05-14", "9876543210");
        assertThat(rows.get(2).cells()).containsExactly("=1+1", "", "", "2.5");
        assertThat(rows.get(2).number()).isEqualTo(3);
        assertThat(rows.get(3).isBlank()).isTrue();
        assertThat(sheets.get(1).rows().get(1).cell(0)).isEqualTo("Asha & <Co> \"Rao\"");
    }

    @Test
    void readsSharedStringsStylesDatesAndCellsWithoutReferences() {
        String styles = """
                <styleSheet xmlns="%s"><numFmts count="1"><numFmt numFmtId="170" formatCode="[$-409]d\\-mmm\\-yy;@"/>\
                <numFmt numFmtId="171" formatCode="&quot;Day&quot; 0"/></numFmts>\
                <cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="170"/><xf numFmtId="171"/>\
                </cellXfs></styleSheet>""".formatted(NS);
        String shared = """
                <sst xmlns="%s" count="3" uniqueCount="3"><si><t>Admission no</t></si>\
                <si><r><t>Ar</t></r><r><rPr><b/></rPr><t xml:space="preserve">jun </t></r>\
                <rPh sb="0" eb="1"><t>IGNORED</t></rPh></si><si><t>Tom &amp; Jerry</t></si></sst>""".formatted(NS);
        String sheet = """
                <worksheet xmlns="%s"><sheetData>\
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>2</v></c></row>\
                <row r="4"><c t="s"><v>1</v></c><c s="1"><v>42139</v></c><c s="2"><v>42139.75</v></c>\
                <c s="3"><v>7</v></c><c t="b"><v>1</v></c><c t="str"><f>A1</f><v>calc</v></c>\
                <c t="e"><v>#DIV/0!</v></c><c t="inlineStr"><is><t>inline</t></is></c><c><v>1.5E3</v></c></row>\
                </sheetData></worksheet>""".formatted(NS);
        byte[] file = zip(workbookParts(List.of("Students"), Map.of("xl/styles.xml", styles,
                "xl/sharedStrings.xml", shared, "xl/worksheets/sheet1.xml", sheet)));

        List<XlsxReader.Row> rows = reader.read(file).getFirst().rows();

        assertThat(rows.get(0).cells()).containsExactly("Admission no", "", "Tom & Jerry");
        assertThat(rows.get(1).number()).isEqualTo(4);
        assertThat(rows.get(1).cells()).containsExactly("Arjun ", "2015-05-15", "2015-05-15", "7", "TRUE", "calc", "",
                "inline", "1500");
    }

    @Test
    void honoursThe1904DateSystemAndAbsoluteTargets() {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>");
        parts.put("xl/workbook.xml", """
                <workbook xmlns="%s" xmlns:r="%s"><workbookPr date1904="1"/><sheets>\
                <sheet name="One" sheetId="1" r:id="rId7"/></sheets></workbook>""".formatted(NS, RNS));
        parts.put("xl/_rels/workbook.xml.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId7" Type="x" Target="/xl/worksheets/data.xml"/></Relationships>""");
        parts.put("xl/styles.xml", "<styleSheet xmlns=\"" + NS + "\"><cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/>"
                + "</cellXfs></styleSheet>");
        parts.put("xl/worksheets/data.xml", "<worksheet xmlns=\"" + NS + "\"><sheetData><row><c s=\"1\"><v>0</v></c>"
                + "<c s=\"1\"><v>1</v></c></row></sheetData></worksheet>");

        List<XlsxReader.Row> rows = reader.read(zip(parts)).getFirst().rows();

        // Serial 0 is not a real date in either system and stays a number.
        assertThat(rows.getFirst().cells()).containsExactly("0", "1904-01-02");
    }

    @Test
    void keepsSheetOrderAndIgnoresColumnsBeyondTheLimit() {
        Limits narrow = new Limits(1024 * 1024, 100, 1024 * 1024, 4 * 1024 * 1024, 10, 100, 2, 100, 1000);
        Map<String, String> parts = workbookParts(List.of("B", "A"), Map.of(
                "xl/worksheets/sheet1.xml", sheetXml("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>x</t></is></c>"
                        + "<c r=\"C1\" t=\"inlineStr\"><is><t>far</t></is></c></row>"),
                "xl/worksheets/sheet2.xml", sheetXml("")));
        List<Sheet> sheets = new XlsxReader(narrow).read(zip(parts));
        assertThat(sheets).extracting(Sheet::name).containsExactly("B", "A");
        assertThat(sheets.getFirst().rows().getFirst().cells()).containsExactly("x");
        assertThat(sheets.get(1).rows()).isEmpty();
    }

    // ------------------------------------------------------------------ refusing what is not a spreadsheet

    @Test
    void refusesFilesThatAreNotWorkbooks() {
        assertRefused(new byte[0], "empty");
        assertRefused("admission_no,first_name\n".getBytes(StandardCharsets.UTF_8), "not an Excel");
        assertRefused(new byte[] {'P', 'K', 3, 4, 0, 0, 0}, "damaged");
        assertRefused(zip(Map.of("hello.txt", "hi")), "not an Excel");
        // A .xls (old binary format) starts with the OLE signature.
        assertRefused(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0}, "not an Excel");
    }

    @Test
    void refusesFilesOverTheSizeLimit() {
        Limits small = new Limits(1024, 100, 1024 * 1024, 4 * 1024 * 1024, 10, 100, 50, 100, 1000);
        byte[] big = new byte[2048];
        big[0] = 'P';
        big[1] = 'K';
        big[2] = 3;
        big[3] = 4;
        assertThatThrownBy(() -> new XlsxReader(small).read(big)).isInstanceOf(XlsxException.class)
                .hasMessageContaining("larger than");
        assertThat(XlsxReader.Limits.DEFAULT.maxFileBytes()).isEqualTo(5L * 1024 * 1024);
    }

    @Test
    void stopsAZipBombWhileInflating() throws IOException {
        // 30 MB of zeros compresses to about 30 KB; the header sizes are not trusted, the inflated bytes are counted.
        Limits limits = new Limits(5L * 1024 * 1024, 100, 10L * 1024 * 1024, 20L * 1024 * 1024, 10, 100, 50, 100, 1000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 30; i++) {
                zip.write(zeros);
            }
            zip.closeEntry();
        }
        assertThat(bytes.size()).isLessThan(200_000);
        assertThatThrownBy(() -> new XlsxReader(limits).read(bytes.toByteArray())).isInstanceOf(XlsxException.class)
                .hasMessageContaining("unpacks to far more data");
    }

    @Test
    void countsTheTotalOfAllEntriesNotJustEachOne() throws IOException {
        Limits limits = new Limits(5L * 1024 * 1024, 100, 1024 * 1024, 3L * 1024 * 1024, 10, 100, 50, 100, 1000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < 5; i++) {
                zip.putNextEntry(new ZipEntry("docProps/padding" + i + ".bin"));
                zip.write(new byte[900 * 1024]);
                zip.closeEntry();
            }
        }
        assertThatThrownBy(() -> new XlsxReader(limits).read(bytes.toByteArray())).isInstanceOf(XlsxException.class)
                .hasMessageContaining("unpacks to far more data");
    }

    @Test
    void refusesTooManyEntries() {
        Limits limits = new Limits(5L * 1024 * 1024, 10, 1024 * 1024, 4L * 1024 * 1024, 10, 100, 50, 100, 1000);
        Map<String, String> parts = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) {
            parts.put("customXml/item" + i + ".xml", "<a/>");
        }
        assertThatThrownBy(() -> new XlsxReader(limits).read(zip(parts))).isInstanceOf(XlsxException.class)
                .hasMessageContaining("too many parts");
    }

    @Test
    void refusesPathTricksInEntryNamesAndTargets() {
        for (String name : List.of("../evil.xml", "xl/../../evil.xml", "/etc/passwd", "xl\\workbook.xml",
                "C:/windows.xml", "xl/./workbook.xml")) {
            assertRefused(zip(Map.of(name, "<a/>")), "unsafe path");
        }
        Map<String, String> parts = workbookParts(List.of("S"), Map.of());
        parts.put("xl/_rels/workbook.xml.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="x" Target="../../secrets/sheet1.xml"/></Relationships>""");
        assertRefused(zip(parts), "unsafe path");
        assertThat(XlsxReader.safeName("xl/worksheets/sheet1.xml")).isTrue();
    }

    @Test
    void refusesTheSamePartTwice() {
        Map<String, String> parts = workbookParts(List.of("S"), Map.of("xl/worksheets/sheet1.xml", sheetXml("")));
        parts.put("XL/WORKBOOK.XML", "<workbook/>");
        assertRefused(zip(parts), "same part twice");
    }

    @Test
    void refusesEntityExpansionAndExternalEntities() {
        String laughs = """
                <?xml version="1.0"?>
                <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">\
                <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">]>
                <sst xmlns="%s"><si><t>&lol3;</t></si></sst>""".formatted(NS);
        assertRefused(zip(workbookParts(List.of("S"), Map.of("xl/sharedStrings.xml", laughs,
                "xl/worksheets/sheet1.xml", sheetXml("")))), "");

        String external = """
                <?xml version="1.0"?>
                <!DOCTYPE x [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <worksheet xmlns="%s"><sheetData><row><c t="inlineStr"><is><t>&secret;</t></is></c></row>\
                </sheetData></worksheet>""".formatted(NS);
        assertThatThrownBy(() -> reader.read(zip(workbookParts(List.of("S"),
                Map.of("xl/worksheets/sheet1.xml", external)))))
                .isInstanceOf(XlsxException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("root:"));

        String doctypeOnly = "<?xml version=\"1.0\"?><!DOCTYPE workbook SYSTEM \"http://example.invalid/x.dtd\">"
                + "<workbook xmlns=\"" + NS + "\"><sheets/></workbook>";
        Map<String, String> parts = workbookParts(List.of(), Map.of());
        parts.put("xl/workbook.xml", doctypeOnly);
        assertRefused(zip(parts), "document type declaration");
    }

    @Test
    void refusesTooManyRowsSheetsAndOverlongCells() {
        Limits limits = new Limits(5L * 1024 * 1024, 100, 1024 * 1024, 4L * 1024 * 1024, 2, 3, 50, 10, 1000);
        XlsxReader strict = new XlsxReader(limits);
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            rows.append("<row r=\"").append(i).append("\"><c><v>").append(i).append("</v></c></row>");
        }
        assertThatThrownBy(() -> strict.read(zip(workbookParts(List.of("S"),
                Map.of("xl/worksheets/sheet1.xml", sheetXml(rows.toString()))))))
                .isInstanceOf(XlsxException.class).hasMessageContaining("more than 3 rows");

        assertThatThrownBy(() -> strict.read(zip(workbookParts(List.of("A", "B", "C"), Map.of()))))
                .isInstanceOf(XlsxException.class).hasMessageContaining("more than 2 sheets");

        String longCell = "<row><c t=\"inlineStr\"><is><t>" + "x".repeat(11) + "</t></is></c></row>";
        assertThatThrownBy(() -> strict.read(zip(workbookParts(List.of("S"),
                Map.of("xl/worksheets/sheet1.xml", sheetXml(longCell))))))
                .isInstanceOf(XlsxException.class).hasMessageContaining("more than 10 characters");
    }

    @Test
    void refusesDamagedSheets() {
        assertRefused(zip(workbookParts(List.of("S"), Map.of("xl/worksheets/sheet1.xml",
                sheetXml("<row><c t=\"s\"><v>5</v></c></row>")))), "points to text that is not there");
        assertRefused(zip(workbookParts(List.of("S"), Map.of("xl/worksheets/sheet1.xml",
                sheetXml("<row r=\"2\"/><row r=\"1\"/>")))), "out of order");
        assertRefused(zip(workbookParts(List.of("S"), Map.of("xl/worksheets/sheet1.xml",
                "<worksheet><sheetData><row>"))), "damaged");
        Map<String, String> noSheet = workbookParts(List.of("S"), Map.of());
        noSheet.remove("xl/worksheets/sheet1.xml");
        assertRefused(zip(noSheet), "missing from the file");
        assertRefused(zip(workbookParts(List.of("S"), Map.of("xl/worksheets/sheet1.xml",
                sheetXml("<row><c r=\"1A\"><v>1</v></c></row>")))), "damaged");
    }

    @Test
    void numbersWithHugeExponentsAreReadQuicklyAsTextAndHugeDateSerialsAreNotDates() {
        // Negative sweep 2026-10-29: "1E999999999" in a number cell made the reader build a billion-digit number.
        String styles = """
                <styleSheet xmlns="%s"><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs>\
                </styleSheet>""".formatted(NS);
        byte[] file = zip(workbookParts(List.of("S"), Map.of("xl/styles.xml", styles, "xl/worksheets/sheet1.xml",
                sheetXml("<row><c><v>1E999999999</v></c><c><v>-1e-999999999</v></c><c s=\"1\"><v>1E20</v></c>"
                        + "<c s=\"1\"><v>9E999999999</v></c><c><v>1.5E3</v></c><c s=\"1\"><v>45000.75</v></c></row>"))));
        long started = System.nanoTime();
        List<String> cells = reader.read(file).getFirst().rows().getFirst().cells();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
        assertThat(cells).containsExactly("1E999999999", "-1e-999999999", "100000000000000000000", "9E999999999", "1500",
                "2023-03-15");
    }

    @Test
    void formulasGiveTheirSavedValueAndUnicodeTextIsKept() {
        byte[] file = zip(workbookParts(List.of("S"), Map.of("xl/worksheets/sheet1.xml",
                sheetXml("<row><c><f>SUM(1,1)</f><v>2</v></c><c t=\"str\"><f>HYPERLINK(\"http://x\")</f>"
                        + "<v>click</v></c><c t=\"inlineStr\"><is><t>\u0905\u0915\u094D\u0937\u0930 \uD83D\uDE00 "
                        + "\u0645\u0631\u062D\u0628\u0627</t></is></c></row>"))));
        assertThat(reader.read(file).getFirst().rows().getFirst().cells())
                .containsExactly("2", "click", "\u0905\u0915\u094D\u0937\u0930 \uD83D\uDE00 \u0645\u0631\u062D\u0628\u0627");
    }

    @Test
    void recognisesDateFormatsAndColumnLetters() {
        assertThat(XlsxReader.isDateFormat(14, null)).isTrue();
        assertThat(XlsxReader.isDateFormat(22, null)).isTrue();
        assertThat(XlsxReader.isDateFormat(0, null)).isFalse();
        assertThat(XlsxReader.isDateFormat(49, null)).isFalse();
        assertThat(XlsxReader.isDateFormat(164, "dd mmm yyyy")).isTrue();
        assertThat(XlsxReader.isDateFormat(165, "0.00")).isFalse();
        assertThat(XlsxReader.isDateFormat(166, "[Red]0;\"day\" 0")).isFalse();
        assertThat(XlsxReader.isDateFormat(167, "h:mm")).isFalse();
        assertThat(XlsxReader.columnIndex("A1")).isZero();
        assertThat(XlsxReader.columnIndex("Z9")).isEqualTo(25);
        assertThat(XlsxReader.columnIndex("AA10")).isEqualTo(26);
        assertThat(XlsxReader.columnIndex("XFD1")).isEqualTo(16_383);
        assertThat(XlsxReader.columnIndex("ABCD1")).isEqualTo(-1);
        assertThat(XlsxReader.columnIndex("12")).isEqualTo(-1);
    }

    // ------------------------------------------------------------------ helpers

    private void assertRefused(byte[] file, String messagePart) {
        assertThatThrownBy(() -> reader.read(file)).isInstanceOf(XlsxException.class)
                .hasMessageContaining(messagePart);
    }

    private static String sheetXml(String rows) {
        return "<worksheet xmlns=\"" + NS + "\"><sheetData>" + rows + "</sheetData></worksheet>";
    }

    /** A workbook with the named sheets (sheetN.xml), plus extra parts. */
    private static Map<String, String> workbookParts(List<String> names, Map<String, String> extra) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>");
        StringBuilder workbook = new StringBuilder("<workbook xmlns=\"" + NS + "\" xmlns:r=\"" + RNS + "\"><sheets>");
        StringBuilder rels = new StringBuilder(
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < names.size(); i++) {
            workbook.append("<sheet name=\"").append(names.get(i)).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"x\" Target=\"worksheets/sheet")
                    .append(i + 1).append(".xml\"/>");
            parts.put("xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(""));
        }
        parts.put("xl/workbook.xml", workbook.append("</sheets></workbook>").toString());
        parts.put("xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString());
        parts.putAll(extra);
        return parts;
    }

    private static byte[] zip(Map<String, String> parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }
}
