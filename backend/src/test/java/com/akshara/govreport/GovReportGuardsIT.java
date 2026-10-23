package com.akshara.govreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/**
 * Guards found by negative testing: an APAAR ID belongs to one student of the school (grid, import, family app and
 * two saves at once), a student appears once in a bulk save, and a scheme's application date is not in the future.
 */
class GovReportGuardsIT extends IntegrationTest {

    static final String GRID = "/api/govreport/students";

    @Test
    void anApaarIdBelongsToOneStudentOfTheSchool() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        String token = s.principal.accessToken();
        api.put(GRID, token, """
                {"entries":[{"studentId":"%s","apaarId":"555544443333"}]}""".formatted(s.asha))
                .andExpect(status().isOk());
        api.put(GRID, token, """
                {"entries":[{"studentId":"%s","apaarId":"555544443333"}]}""".formatted(s.bala))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors['entries[0].apaarId']").exists());
        // Within one save too.
        api.put(GRID, token, """
                {"entries":[{"studentId":"%s","apaarId":"666655554444"},
                            {"studentId":"%s","apaarId":"666655554444"}]}""".formatted(s.bala, s.chitra))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors['entries[1].apaarId']").exists());
        // Saving Asha again with her own ID is fine; the refused saves wrote nothing.
        api.put(GRID, token, """
                {"entries":[{"studentId":"%s","apaarId":"555544443333","pen":"TS1"}]}""".formatted(s.asha))
                .andExpect(status().isOk());
        api.get(GRID + "?sectionId=" + s.section, s.office.accessToken())
                .andExpect(jsonPath("$.rows[?(@.apaarId == '555544443333')].fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.rows[?(@.apaarId == '666655554444')]").isEmpty());

        // The import reports the clash on the row, in a dry run and for real.
        String csv = """
                {"csv":"admission_no,apaar_id\\r\\nGOV-2,555544443333\\r\\nGOV-3,777766665555\\r\\n\
                GOV-9,777766665555\\r\\n"}""";
        String result = api.post(GRID + "/import?dryRun=true", token, csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.validRows").value(1))
                .andExpect(jsonPath("$.invalidRows").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(result).contains("already recorded for another student").contains("another student in this file");
        api.post(GRID + "/import", token, csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(false));

        // A parent cannot give their child an ID another student has.
        api.put(GRID, token, """
                {"entries":[{"studentId":"%s","apaarId":"888877776666"}]}""".formatted(s.bala))
                .andExpect(status().isOk());
        api.put("/api/me/govreport/children/" + s.asha, s.parent.accessToken(), """
                {"motherTongue":"HINDI","apaarId":"888877776666"}""")
                .andExpect(status().isBadRequest()); // Asha's ID is already recorded and locked by the office.
        api.put(GRID, token, """
                {"entries":[{"studentId":"%s"}]}""".formatted(s.asha)).andExpect(status().isOk());
        api.put("/api/me/govreport/children/" + s.asha, s.parent.accessToken(), """
                {"motherTongue":"HINDI","apaarId":"888877776666"}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.apaarId").exists());

        // Another school may hold the same ID for its own student.
        GovReportSchool other = new GovReportSchool(api);
        api.put(GRID, other.principal.accessToken(), """
                {"entries":[{"studentId":"%s","apaarId":"888877776666"}]}""".formatted(other.asha))
                .andExpect(status().isOk());
    }

    @Test
    void twoSavesOfOneApaarIdAtOnceLeaveItOnOneStudent() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        // Seed the code lists first, so the saves race only for the ID.
        api.put(GRID, s.principal.accessToken(), """
                {"entries":[{"studentId":"%s","pen":"P1"}]}""".formatted(s.asha)).andExpect(status().isOk());
        List<String> students = List.of(s.asha, s.bala, s.chitra, s.dev);
        ExecutorService pool = Executors.newFixedThreadPool(students.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (String student : students) {
                Callable<ResultActions> save = () -> api.put(GRID, s.principal.accessToken(), """
                        {"entries":[{"studentId":"%s","apaarId":"999988887777"}]}""".formatted(student));
                results.add(pool.submit(() -> {
                    start.await();
                    return save.call().andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(200, 409);
            assertThat(statuses).filteredOn(c -> c == 200).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theMigrationKeepsTheFirstOfExistingDuplicatesAndRecordsTheOthers() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        api.put(GRID, s.principal.accessToken(), """
                {"entries":[{"studentId":"%s","apaarId":"121212121212"}]}""".formatted(s.asha))
                .andExpect(status().isOk());
        api.put(GRID, s.principal.accessToken(), """
                {"entries":[{"studentId":"%s","apaarId":"343434343434"}]}""".formatted(s.bala))
                .andExpect(status().isOk());
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V31__govreport_guards.sql")) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        // Replays the migration on data from before it (the index dropped, Bala given Asha's ID), then rolls back.
        try (java.sql.Connection c = ownerConnection()) {
            c.setAutoCommit(false);
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("drop index govreport.uq_student_record_apaar");
                st.execute("update govreport.student_record set apaar_id = '121212121212' where student_id = '"
                        + s.bala + "'");
                st.execute(sql);
                try (java.sql.ResultSet rs = st.executeQuery("select student_id::text, apaar_id, apaar_locked "
                        + "from govreport.student_record where student_id in ('" + s.asha + "','" + s.bala + "')")) {
                    java.util.Map<String, String> ids = new java.util.HashMap<>();
                    java.util.Map<String, Boolean> locked = new java.util.HashMap<>();
                    while (rs.next()) {
                        ids.put(rs.getString(1), rs.getString(2));
                        locked.put(rs.getString(1), rs.getBoolean(3));
                    }
                    assertThat(ids.get(s.asha)).isEqualTo("121212121212");
                    assertThat(ids.get(s.bala)).isNull();
                    assertThat(locked.get(s.bala)).isFalse();
                }
                try (java.sql.ResultSet rs = st.executeQuery("select details->>'apaarId', details->>'studentId' "
                        + "from audit.audit_event where action = 'govreport_fields.apaar_duplicate_cleared' "
                        + "and tenant_id = '" + s.school.tenantId() + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("121212121212");
                    assertThat(rs.getString(2)).isEqualTo(s.bala);
                    assertThat(rs.next()).isFalse();
                }
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void aStudentAppearsOnceInASaveAndSchemeDatesAreNotInTheFuture() throws Exception {
        GovReportSchool s = new GovReportSchool(api);
        String token = s.principal.accessToken();
        api.put(GRID, token, """
                {"entries":[{"studentId":"%s","pen":"A1"},{"studentId":"%s","pen":"B2"}]}"""
                .formatted(s.asha, s.asha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['entries[1].studentId']").exists());
        api.get(GRID + "?sectionId=" + s.section, s.office.accessToken())
                .andExpect(jsonPath("$.rows[0].fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.rows[0].pen").value(org.hamcrest.Matchers.nullValue()));

        api.post("/api/govreport/schemes", token, """
                {"studentId":"%s","academicYearId":"%s","schemeCode":"NMMS","status":"APPLIED",
                 "appliedOn":"2099-01-01"}""".formatted(s.asha, s.yearId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.appliedOn").exists());
        String id = TestApi.read(api.post("/api/govreport/schemes", token, """
                {"studentId":"%s","academicYearId":"%s","schemeCode":"NMMS","status":"APPLIED",
                 "appliedOn":"%s"}""".formatted(s.asha, s.yearId, GovReportAuth.today()))
                .andExpect(status().isCreated()), "$.id");
        api.put("/api/govreport/schemes/" + id, token, """
                {"status":"APPLIED","appliedOn":"2099-01-01"}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.appliedOn").exists());
        api.put("/api/govreport/schemes/" + id, token, """
                {"status":"APPLIED","appliedOn":"%s"}""".formatted(GovReportAuth.today().minusDays(3)))
                .andExpect(status().isOk());
    }
}
