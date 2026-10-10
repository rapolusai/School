package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.BillingFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * The platform health page: cross-school numbers come from the school list and count-only functions, while the
 * runtime role still sees no school's rows; only the Super Admin reaches it.
 */
class PlatformHealthIT extends IntegrationTest {

    @Test
    void healthCountsSchoolsPeopleStudentsAndTheOutboxAcrossSchools() throws Exception {
        Session root = new BillingFixtures(api).root();
        Map<String, Object> before = health(root);

        School school = api.signup();
        Session admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        String section = fixtures.section(admin, fixtures.schoolClass(admin, "Class 1"), "A", 30);
        fixtures.student(admin, section, "H-1", "Asha", "Rao", "Lata Rao", "9876501001");
        fixtures.student(admin, section, "H-2", "Bala", "Rao", "Lata Rao", "9876501001");
        api.createUser(admin, "Ravi Kumar", "ravi@" + school.code() + ".akshara.test", java.util.List.of("TEACHER"));
        // Two messages waiting and one that failed, far in the future so no dispatcher picks them up.
        message(school.tenantId(), "QUEUED");
        message(school.tenantId(), "QUEUED");
        message(school.tenantId(), "FAILED");

        Map<String, Object> after = health(root);
        assertThat(number(after, "$.schools.total")).isEqualTo(number(before, "$.schools.total") + 1);
        assertThat(number(after, "$.schools.byStatus.TRIAL")).isEqualTo(number(before, "$.schools.byStatus.TRIAL") + 1);
        assertThat(number(after, "$.schools.byStatus.SUSPENDED"))
                .isEqualTo(number(before, "$.schools.byStatus.SUSPENDED"));
        assertThat(number(after, "$.users")).isEqualTo(number(before, "$.users") + 2);
        assertThat(number(after, "$.activeStudents")).isEqualTo(number(before, "$.activeStudents") + 2);
        assertThat(number(after, "$.outbox.queued")).isEqualTo(number(before, "$.outbox.queued") + 2);
        assertThat(number(after, "$.outbox.failed")).isEqualTo(number(before, "$.outbox.failed") + 1);

        // A suspension moves the school between statuses.
        api.post(BillingFixtures.school(school.tenantId()) + "/suspend", root.accessToken(), """
                {"reason":"Testing the health page"}""").andExpect(status().isOk());
        Map<String, Object> suspended = health(root);
        assertThat(number(suspended, "$.schools.byStatus.TRIAL")).isEqualTo(number(before, "$.schools.byStatus.TRIAL"));
        assertThat(number(suspended, "$.schools.byStatus.SUSPENDED"))
                .isEqualTo(number(before, "$.schools.byStatus.SUSPENDED") + 1);
    }

    @Test
    void theDatabaseAndTheAppReportThemselves() throws Exception {
        Session root = new BillingFixtures(api).root();
        api.get("/api/platform/health", root.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database.reachable").value(true))
                .andExpect(jsonPath("$.database.latencyMs").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.app.version").value(matchesPattern("^[^@\\s]+$")))
                .andExpect(jsonPath("$.app.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.app.uptimeSeconds").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.checkedAt").isNotEmpty())
                .andExpect(jsonPath("$.schools.byStatus.ACTIVE").isNumber())
                .andExpect(jsonPath("$.schools.byStatus.PAST_DUE").isNumber());
    }

    @Test
    void theCountingFunctionsAreTheSuperAdminsOnlyWindowAcrossSchools() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        api.get("/api/platform/health", admin.accessToken()).andExpect(status().isForbidden());
        api.get("/api/platform/health", null).andExpect(status().isUnauthorized());
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement("""
                        select p.proname, p.prosecdef, has_function_privilege('public', p.oid, 'execute'),
                               has_function_privilege(?, p.oid, 'execute')
                        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = 'billing'
                        order by 1""")) {
            s.setString(1, APP_USER);
            var rows = new java.util.ArrayList<String>();
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + " definer=" + rs.getBoolean(2) + " public=" + rs.getBoolean(3)
                            + " app=" + rs.getBoolean(4));
                }
            }
            assertThat(rows).containsExactly(
                    "outbox_counts definer=true public=false app=true",
                    "tenant_student_counts definer=true public=false app=true",
                    "unpaid_invoice_totals definer=true public=false app=true");
        }
        // Without a school selected the runtime role still sees no school's rows.
        try (Connection app = appConnection();
                var rs = app.createStatement().executeQuery("""
                        select (select count(*) from students.student) + (select count(*) from billing.invoice)
                             + (select count(*) from notifications.message)""")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    private Map<String, Object> health(Session root) throws Exception {
        String body = api.get("/api/platform/health", root.accessToken())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$");
    }

    private static long number(Map<String, Object> json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private static void message(UUID tenantId, String status) throws SQLException {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement("""
                        insert into notifications.message (id, tenant_id, channel, recipient, template_key, language,
                                                           body, status, next_attempt_at, created_at, updated_at)
                        values (?, ?, 'SMS', '+919876501001', 'test', 'en', 'Test', ?, now() + interval '30 days',
                                now(), now())""")) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, tenantId);
            s.setString(3, status);
            s.executeUpdate();
        }
    }
}
