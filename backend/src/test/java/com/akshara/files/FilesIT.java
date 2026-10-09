package com.akshara.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * File uploads end to end, through homework attachments (the first owner of files): size and type checks on the
 * contents, safe download headers, and files of another school.
 */
class FilesIT extends IntegrationTest {

    static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    private School school;
    private Session admin;
    private String homework;

    @BeforeEach
    void homeworkToAttachFilesTo() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.year(admin, "This year", today.minusDays(100).toString(), today.plusDays(200).toString(), true);
        String classId = fixtures.schoolClass(admin, "Class 3");
        String section = fixtures.section(admin, classId, "A", 30);
        String subject = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"English"}""").andExpect(status().isCreated()), "$.id");
        api.put("/api/academics/classes/" + classId + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s"]}""".formatted(subject)).andExpect(status().isOk());
        homework = TestApi.read(api.post("/api/homework", admin.accessToken(), """
                {"sectionIds":["%s"],"subjectId":"%s","title":"Reading","dueOn":"%s","onlineSubmission":true}"""
                .formatted(section, subject, today.plusDays(3))).andExpect(status().isCreated()), "$.id");
    }

    @Test
    void uploadsAreLimitedToFiveMegabytesOfAnAllowedType() throws Exception {
        byte[] big = new byte[FileUploads.MAX_BYTES + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        attach(admin, file("big.pdf", big))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.errors.file", containsString("5 MB")));
        // The contents decide, not the name or the browser's content type.
        attach(admin, new MockMultipartFile("file", "photo.pdf", "application/pdf", PNG))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file", containsString("not really a .pdf")));
        attach(admin, file("tool.exe", new byte[] {'M', 'Z', 0, 0, 1, 2, 3}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").exists());
        attach(admin, file("page.html", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
        attach(admin, file("empty.pdf", new byte[0])).andExpect(status().isBadRequest());
        attach(admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.file").exists());
        attach(admin, file("picture.png", PNG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"));
    }

    @Test
    void downloadsAreAttachmentsThatBrowsersDoNotSniff() throws Exception {
        String pdf = TestApi.read(attach(admin, file("../../Week 1 <notes>.pdf", PDF))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Week 1 notes.pdf"))
                .andExpect(jsonPath("$.size").value(PDF.length)), "$.id");
        api.get("/api/files/" + pdf, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("Week 1 notes.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().bytes(PDF));
        byte[] hindi = "नमस्ते\n".getBytes(StandardCharsets.UTF_8);
        String text = TestApi.read(attach(admin, file("answers.txt", hindi)).andExpect(status().isCreated()), "$.id");
        api.get("/api/files/" + text, admin.accessToken())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"));
        mvc.perform(MockMvcRequestBuilders.get("/api/files/" + pdf)).andExpect(status().isUnauthorized());
        api.get("/api/files/" + UUID.randomUUID(), admin.accessToken()).andExpect(status().isNotFound());

        // Stored as sent, with its checksum.
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement(
                        "select size_bytes, sha256, owner_module, owner_type from files.stored_file where id = ?")) {
            s.setObject(1, UUID.fromString(pdf));
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(PDF.length);
                assertThat(rs.getString(2)).isEqualTo(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(PDF)));
                assertThat(rs.getString(3)).isEqualTo("homework");
                assertThat(rs.getString(4)).isEqualTo("homework");
            }
        }
    }

    @Test
    void aHomeworkHoldsAtMostFiveFilesAndRemovedFilesAreGone() throws Exception {
        String first = null;
        for (int i = 1; i <= 5; i++) {
            String id = TestApi.read(attach(admin, file("sheet-" + i + ".pdf", PDF)).andExpect(status().isCreated()),
                    "$.id");
            first = first == null ? id : first;
        }
        attach(admin, file("sheet-6.pdf", PDF)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.file").exists());
        api.delete("/api/homework/" + homework + "/attachments/" + first, admin.accessToken())
                .andExpect(status().isNoContent());
        api.get("/api/files/" + first, admin.accessToken()).andExpect(status().isNotFound());
        api.delete("/api/homework/" + homework + "/attachments/" + first, admin.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/homework/" + homework, admin.accessToken())
                .andExpect(jsonPath("$.attachments.length()").value(4));
    }

    @Test
    void anotherSchoolsFilesAreNotFoundEvenInTheDatabase() throws Exception {
        String file = TestApi.read(attach(admin, file("sheet.pdf", PDF)).andExpect(status().isCreated()), "$.id");
        School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get("/api/files/" + file, otherAdmin.accessToken()).andExpect(status().isNotFound());
        // Without the school selected, the runtime role sees no file at all.
        try (Connection app = appConnection();
                PreparedStatement s = app.prepareStatement("select count(*) from files.stored_file where id = ?")) {
            s.setObject(1, UUID.fromString(file));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }
        // A person of the same school without access to the homework does not get it either.
        api.createUser(admin, "Meena Reddy", "accounts@" + school.code() + ".akshara.test", List.of("ACCOUNTANT"));
        Session accounts = api.login(school.code(), "accounts@" + school.code() + ".akshara.test", TestApi.PASSWORD);
        api.get("/api/files/" + file, accounts.accessToken()).andExpect(status().isNotFound());
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    private ResultActions attach(Session session, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders
                .multipart("/api/homework/" + homework + "/attachments");
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        return mvc.perform(request.header("Authorization", "Bearer " + session.accessToken()));
    }
}
