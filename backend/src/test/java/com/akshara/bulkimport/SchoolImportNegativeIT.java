package com.akshara.bulkimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.reports.Xlsx;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Negative sweep of 2026-10-29 (docs/testing/negative-sweep-2026-10-29.md), the school import: numbers with absurd
 * exponents, class names longer than a class may have, unicode and right-to-left names, the wrong request type and the
 * same file imported twice at once.
 */
class SchoolImportNegativeIT extends IntegrationTest {

    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RNS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private School school;
    private Session admin;

    @BeforeEach
    void schoolWithClassFive() throws Exception {
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        String classFive = fixtures.schoolClass(admin, "Class 5");
        fixtures.section(admin, classFive, "A", 40);
    }

    @Test
    void aNumberWithAHugeExponentIsARowProblemNotAStalledServer() throws Exception {
        StringBuilder header = new StringBuilder("<row r=\"1\">");
        for (ImportColumns.Column c : ImportColumns.STUDENT) {
            header.append("<c t=\"inlineStr\"><is><t>").append(c.heading()).append("</t></is></c>");
        }
        header.append("</row>");
        // Admission no 1E999999999, roll no 1E-99999999, guardian mobile 9E99999.
        String row = "<row r=\"2\"><c><v>1E999999999</v></c><c><v>1E-99999999</v></c>"
                + "<c t=\"inlineStr\"><is><t>Asha</t></is></c><c/><c t=\"inlineStr\"><is><t>2015-01-01</t></is></c>"
                + "<c t=\"inlineStr\"><is><t>F</t></is></c><c t=\"inlineStr\"><is><t>Lata</t></is></c>"
                + "<c t=\"inlineStr\"><is><t>MOTHER</t></is></c><c><v>9E99999</v></c></row>";
        byte[] file = workbook("Class 5 - A", "<worksheet xmlns=\"" + NS + "\"><sheetData>" + header + row
                + "</sheetData></worksheet>");
        long started = System.nanoTime();
        upload(file, true, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.invalidRows").value(1));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000);
    }

    @Test
    void aClassNameLongerThanAClassMayHaveIsASheetProblemInTheCheck() throws Exception {
        // Excel stops sheet names at 31 characters, Google Sheets and LibreOffice do not.
        String longClass = "Class of the Very Long Name That Never Ends";
        assertThat(longClass.length()).isGreaterThan(40);
        byte[] file = workbook(longClass + " - A", "<worksheet xmlns=\"" + NS + "\"><sheetData>"
                + inlineRow(1, ImportColumns.STUDENT.stream().map(ImportColumns.Column::heading).toArray())
                + inlineRow(2, row("LONG/1", 1)) + "</sheetData></worksheet>");
        upload(file, true, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.sheets[0].problems[*].message", hasItem(containsString("40 characters"))));
        upload(file, false, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Check the file"));
        api.get("/api/academics/classes", admin.accessToken()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void unicodeEmojiAndRightToLeftNamesAreKeptAsWritten() throws Exception {
        String hindi = "अक्षर";
        String arabic = "مرحبا";
        byte[] file = Xlsx.write(List.of(studentSheet("Class 5 - A",
                new Object[] {"UNI/1", 1, hindi, "😀", "2015-01-01", "F", arabic, "MOTHER", "9876501001",
                        "", ""})));
        upload(file, false, false).andExpect(status().isOk()).andExpect(jsonPath("$.studentsCreated").value(1));
        api.get("/api/students?q=" + hindi, admin.accessToken())
                .andExpect(jsonPath("$.items[0].firstName").value(hindi));
    }

    @Test
    void theWrongRequestTypeAndAnUnknownPartAreRefused() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/school-import").contentType(MediaType.APPLICATION_JSON)
                .content("{}").header("Authorization", "Bearer " + admin.accessToken()))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(MockMvcRequestBuilders.multipart("/api/school-import")
                .file(new MockMultipartFile("upload", "x.xlsx", Xlsx.CONTENT_TYPE, new byte[] {1}))
                .header("Authorization", "Bearer " + admin.accessToken()))
                .andExpect(status().isBadRequest());
        // An .xlsx that is really a PNG, sent as an Excel file.
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        mvc.perform(MockMvcRequestBuilders.multipart("/api/school-import")
                .file(new MockMultipartFile("file", "school.xlsx", Xlsx.CONTENT_TYPE, png))
                .header("Authorization", "Bearer " + admin.accessToken()))
                .andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.multipart("/api/school-import?dryRun=maybe")
                .file(new MockMultipartFile("file", "school.xlsx", Xlsx.CONTENT_TYPE, png))
                .header("Authorization", "Bearer " + admin.accessToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSameFileImportedTwiceAtOnceIsImportedOnce() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int n = 1; n <= 50; n++) {
            rows.add(row("TWICE/" + n, n));
        }
        byte[] file = Xlsx.write(List.of(studentSheet("Class 7 - A", rows.toArray(Object[][]::new))));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> codes = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = List.of(() -> upload(file, false, true).andReturn().getResponse()
                    .getStatus(), () -> upload(file, false, true).andReturn().getResponse().getStatus());
            for (Future<Integer> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(codes).contains(200);
        assertThat(codes).allMatch(c -> c == 200 || c == 400 || c == 409);
        api.get("/api/students?size=1", admin.accessToken()).andExpect(jsonPath("$.total").value(50));
        api.get("/api/academics/classes", admin.accessToken()).andExpect(jsonPath("$.length()").value(2));
    }

    // ------------------------------------------------------------------ helpers

    private static Object[] row(String admissionNo, int n) {
        return new Object[] {admissionNo, null, "Student" + n, "", "2015-01-01", n % 2 == 0 ? "F" : "M",
                "Parent " + n, "MOTHER", String.format("98%08d", n), "", ""};
    }

    private static Xlsx.Sheet studentSheet(String name, Object[]... rows) {
        Xlsx.Sheet sheet = new Xlsx.Sheet(name);
        sheet.header(ImportColumns.STUDENT.stream().map(ImportColumns.Column::heading).toArray(String[]::new));
        for (Object[] r : rows) {
            sheet.row(r);
        }
        return sheet;
    }

    private static String inlineRow(int number, Object... values) {
        StringBuilder xml = new StringBuilder("<row r=\"" + number + "\">");
        for (Object v : values) {
            xml.append(v == null ? "<c/>" : "<c t=\"inlineStr\"><is><t>" + v + "</t></is></c>");
        }
        return xml.append("</row>").toString();
    }

    /** A one-sheet workbook with the given sheet XML, written by hand. */
    private static byte[] workbook(String sheetName, String sheetXml) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>");
        parts.put("xl/workbook.xml", "<workbook xmlns=\"" + NS + "\" xmlns:r=\"" + RNS + "\"><sheets><sheet name=\""
                + sheetName + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/"
                + "2006/relationships\"><Relationship Id=\"rId1\" Type=\"x\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>");
        parts.put("xl/worksheets/sheet1.xml", sheetXml);
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

    private ResultActions upload(byte[] file, boolean dryRun, boolean createMissing) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.multipart("/api/school-import?dryRun=" + dryRun + "&createMissing="
                + createMissing).file(new MockMultipartFile("file", "school.xlsx", Xlsx.CONTENT_TYPE, file))
                .header("Authorization", "Bearer " + admin.accessToken()));
    }
}
