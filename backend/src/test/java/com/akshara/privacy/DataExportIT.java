package com.akshara.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * Data exports for access requests: what goes in the ZIP (only the parent's own child and their own contact details),
 * who can download it, and its deletion after 7 days.
 */
class DataExportIT extends PrivacyTestBase {

    @Autowired
    private ExportCleanup cleanup;

    @Test
    void theExportHoldsTheChildsRecordsAndOnlyThisFamilysDetails() throws Exception {
        FeeFixtures fees = new FeeFixtures(api, mvc);
        Heads heads = fees.defaultHeads(admin);
        fees.publishedStructure(admin, yearId, classId, heads);
        String receipt = fees.cash(admin, arjun, 5_000_00);
        String receiptNo = TestApi.read(api.get("/api/fees/receipts/" + receipt, admin.accessToken()), "$.receiptNo");
        LocalDate lastDay = LocalDate.of(2027, 3, 31);
        LocalDate day = today().isAfter(lastDay) ? lastDay : today();
        api.put("/api/attendance/registers/" + sectionId + "/" + day, admin.accessToken(), """
                {"entries":[{"studentId":"%s","status":"ABSENT"},{"studentId":"%s","status":"PRESENT"},
                            {"studentId":"%s","status":"PRESENT"}]}""".formatted(arjun, diya, kabir))
                .andExpect(status().isOk());
        acceptForBoth(publish(null));

        String request = submit(parent, "ACCESS", "CHILD", arjun, "Everything about Arjun, please.");
        // Nothing to download before staff make the export.
        api.get("/api/me/privacy/requests/" + request + "/export", parent.accessToken())
                .andExpect(status().isNotFound());
        api.post("/api/privacy/requests/" + request + "/export", admin.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.export.status").value("READY"))
                .andExpect(jsonPath("$.export.fileName", startsWith("data-export-p-1-")))
                .andExpect(jsonPath("$.events[-1].kind").value("EXPORT_READY"));
        api.get("/api/me/privacy/requests/" + request, parent.accessToken())
                .andExpect(jsonPath("$.export.status").value("READY"))
                .andExpect(jsonPath("$.export.expiresAt").exists());

        byte[] zip = api.get("/api/me/privacy/requests/" + request + "/export", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"data-export-")))
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, String> files = unzip(zip);
        assertThat(files.keySet()).contains("README.txt", "student.json", "guardians.json", "enrollments.json",
                "attendance.json", "fees.json", "fee-receipts.json", "consents.json");

        assertThat(JsonPath.<String>read(files.get("student.json"), "$.fullName")).isEqualTo("Arjun Sharma");
        assertThat(JsonPath.<String>read(files.get("student.json"), "$.admissionNo")).isEqualTo("P-1");
        assertThat(JsonPath.<String>read(files.get("guardians.json"), "$[0].phone")).isEqualTo(ANITHA_PHONE);
        assertThat(JsonPath.<Integer>read(files.get("enrollments.json"), "$.length()")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(files.get("attendance.json"), "$[0].counts.absent")).isEqualTo(1);
        assertThat(JsonPath.<String>read(files.get("fee-receipts.json"), "$[0].receiptNo")).isEqualTo(receiptNo);
        assertThat(JsonPath.<Integer>read(files.get("fee-receipts.json"), "$[0].amountPaise")).isEqualTo(5_000_00);
        assertThat(JsonPath.<Integer>read(files.get("consents.json"), "$.length()")).isEqualTo(3);
        assertThat(files.get("README.txt")).contains("Digital Personal Data Protection Act, 2023", "Arjun Sharma");
        // Nothing about the sibling or the other family.
        String everything = String.join("\n", files.values());
        assertThat(everything).doesNotContain("Diya", "Kabir", "Farah", FARAH_PHONE, "P-2", "P-3");

        // Downloads are counted and on the timeline; another family or school gets nothing.
        api.get("/api/me/privacy/requests/" + request, parent.accessToken())
                .andExpect(jsonPath("$.export.downloadCount").value(1))
                .andExpect(jsonPath("$.events[-1].kind").value("EXPORT_DOWNLOADED"));
        api.get("/api/me/privacy/requests/" + request + "/export", otherParent.accessToken())
                .andExpect(status().isNotFound());
        School other = api.signup();
        Session outsider = api.login(other);
        api.post("/api/privacy/requests/" + request + "/export", outsider.accessToken(), null)
                .andExpect(status().isNotFound());
        api.get("/api/privacy/requests/" + request + "/export", outsider.accessToken())
                .andExpect(status().isNotFound());
        // Staff can download it too (for example to hand it over in person).
        api.get("/api/privacy/requests/" + request + "/export", admin.accessToken()).andExpect(status().isOk());

        // A new export replaces the previous file.
        api.post("/api/privacy/requests/" + request + "/export", admin.accessToken(), null)
                .andExpect(jsonPath("$.exports.length()").value(2))
                .andExpect(jsonPath("$.exports[1].status").value("REPLACED"))
                .andExpect(jsonPath("$.exports[0].status").value("READY"));
        assertThat(countFiles(school.tenantId())).isEqualTo(1);

        api.get("/api/audit-events?limit=100", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("data_export.created", "data_export.downloaded")));
    }

    @Test
    void exportsAreDeletedAfterSevenDays() throws Exception {
        String request = submit(parent, "ACCESS", "SELF", null, null);
        api.post("/api/privacy/requests/" + request + "/export", admin.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.export.fileName", startsWith("data-export-parent-")));
        String expiresAt = TestApi.read(api.get("/api/me/privacy/requests/" + request, parent.accessToken()),
                "$.export.expiresAt");
        assertThat(Instant.parse(expiresAt)).isCloseTo(Instant.now().plus(7, ChronoUnit.DAYS),
                org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));

        // The parent's own data: their sign-in, guardian record, children and consent decisions.
        Map<String, String> files = unzip(api.get("/api/me/privacy/requests/" + request + "/export",
                parent.accessToken()).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(files.keySet()).contains("account.json", "guardian.json", "children.json", "consents-given.json",
                "data-requests.json");
        assertThat(JsonPath.<String>read(files.get("account.json"), "$.email")).isEqualTo(email("anitha"));
        assertThat(JsonPath.<String>read(files.get("guardian.json"), "$.phone")).isEqualTo(ANITHA_PHONE);
        assertThat(JsonPath.<Integer>read(files.get("children.json"), "$.length()")).isEqualTo(2);

        // Seven days on, the file cannot be downloaded and the clean-up deletes it, keeping the record.
        expire(request);
        api.get("/api/me/privacy/requests/" + request + "/export", parent.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/me/privacy/requests", parent.accessToken())
                .andExpect(jsonPath("$[0].exportStatus").value("EXPIRED"));
        assertThat(cleanup.purgeExpired(Instant.now())).isGreaterThanOrEqualTo(1);
        assertThat(countFiles(school.tenantId())).isZero();
        api.get("/api/privacy/requests/" + request, admin.accessToken())
                .andExpect(jsonPath("$.export").doesNotExist())
                .andExpect(jsonPath("$.exports[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.exports[0].deletedAt").exists());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("data_export.expired")));
        // Another round finds nothing more for this school.
        assertThat(cleanup.purgeSchool(school.tenantId(), Instant.now())).isZero();
    }

    @Test
    void onlyAccessRequestsGetAnExport() throws Exception {
        String grievance = submit(parent, "GRIEVANCE", "SELF", null, "Too many messages");
        api.post("/api/privacy/requests/" + grievance + "/export", admin.accessToken(), null)
                .andExpect(status().isConflict());
        String access = submit(parent, "ACCESS", "CHILD", diya, null);
        api.post("/api/privacy/requests/" + access + "/close", admin.accessToken(), """
                {"resolution":"DECLINED","note":"Sent on paper instead."}""").andExpect(status().isOk());
        api.post("/api/privacy/requests/" + access + "/export", admin.accessToken(), null)
                .andExpect(status().isConflict());
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static void expire(String requestId) throws Exception {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement("update privacy.data_export "
                        + "set created_at = created_at - interval '8 days', expires_at = now() - interval '1 day' "
                        + "where request_id = ?")) {
            s.setObject(1, UUID.fromString(requestId));
            s.executeUpdate();
        }
    }

    private static long countFiles(UUID tenantId) throws Exception {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement(
                        "select count(*) from privacy.data_export where tenant_id = ? and content is not null")) {
            s.setObject(1, tenantId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
