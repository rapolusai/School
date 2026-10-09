package com.akshara.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Guards the database itself: every table that holds a school's data must have row-level security with the
 * tenant_isolation policy, so a table added by a future migration without it fails the build.
 */
class RowLevelSecurityCoverageIT extends IntegrationTest {

    /** Tables that are not owned by one school. Everything else must be isolated. */
    static final Set<String> NOT_SCHOOL_OWNED = Set.of("platform.tenant", "platform.platform_admin",
            "public.flyway_schema_history");

    /** Tables whose isolation comes from a parent row instead of their own tenant_id. */
    static final Set<String> ISOLATED_THROUGH_PARENT = Set.of("identity.user_role");

    static final List<String> PHASE_ONE_TABLES = List.of("academics.academic_year", "academics.school_class",
            "academics.section", "academics.subject", "academics.class_subject", "students.student",
            "students.guardian", "students.student_guardian", "students.enrollment", "fees.fee_head",
            "fees.fee_structure", "fees.fee_instalment", "fees.fee_instalment_share", "fees.late_fee_rule",
            "fees.concession", "fees.concession_head", "fees.student_due", "fees.receipt_counter", "fees.receipt",
            "fees.payment_allocation", "fees.late_fee_waiver", "fees.payment_order", "fees.gateway_event",
            "fees.fee_reminder");

    /** Phase 0 foreign keys created before composite tenant keys were required. V1 cannot change. */
    static final Set<String> SINGLE_COLUMN_FK_ALLOWED = Set.of("identity.refresh_token");

    record TableInfo(String name, boolean rowSecurity, boolean hasTenantId, boolean hasPolicy, boolean partition,
            boolean appCanSelect, boolean appOwns) {
    }

    @Test
    void everySchoolOwnedTableHasRowLevelSecurity() throws Exception {
        List<TableInfo> tables = tables();
        assertThat(tables).extracting(TableInfo::name).containsAll(PHASE_ONE_TABLES);

        List<String> problems = new ArrayList<>();
        for (TableInfo t : tables) {
            if (t.appOwns()) {
                problems.add(t.name() + " is owned by the runtime role");
            }
            if (NOT_SCHOOL_OWNED.contains(t.name())) {
                continue;
            }
            if (t.partition()) {
                // A partition is read through its parent's policy; the runtime role must not reach it directly.
                if (t.appCanSelect() && !t.rowSecurity()) {
                    problems.add(t.name() + " is a partition the runtime role can read without row-level security");
                }
                continue;
            }
            if (!t.rowSecurity()) {
                problems.add(t.name() + " does not enable row-level security");
            }
            if (!t.hasPolicy()) {
                problems.add(t.name() + " has no tenant_isolation policy with USING and WITH CHECK");
            }
            if (!t.hasTenantId() && !ISOLATED_THROUGH_PARENT.contains(t.name())) {
                problems.add(t.name() + " has no tenant_id column");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void schoolDataIsInvisibleWithoutTheSchoolSelected() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        String yearId = fixtures.currentYear(admin);
        String classId = fixtures.schoolClass(admin, "Class 1");
        String section = fixtures.section(admin, classId, "A", 30);
        String subject = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"English"}"""), "$.id");
        api.put("/api/academics/classes/" + classId + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s"]}""".formatted(subject));
        String student = fixtures.student(admin, section, "RLS-1", "Asha", null, "Lata Rao", "9876501001");
        feeRecords(admin, yearId, classId, student);
        School other = api.signup();

        try (Connection app = appConnection(); Connection owner = ownerConnection()) {
            for (String table : PHASE_ONE_TABLES) {
                long owned = countAsOwner(owner, table, school.tenantId());
                assertThat(owned).as(table).isPositive();
                assertThat(count(app, table)).as(table + " without a school").isZero();
                select(app, school.tenantId());
                assertThat(count(app, table)).as(table + " in its school").isEqualTo(owned);
                select(app, other.tenantId());
                assertThat(count(app, table)).as(table + " in another school").isZero();
                // Writes aimed at the other school match nothing.
                try (PreparedStatement update = app.prepareStatement(
                        "update " + table + " set tenant_id = tenant_id where tenant_id = ?")) {
                    update.setObject(1, school.tenantId());
                    if (canUpdateTenantId(app, table)) {
                        assertThat(update.executeUpdate()).as(table).isZero();
                    } else {
                        // Append-only tables (ledgers, receipts) cannot be updated by the runtime role at all.
                        assertThatThrownBy(update::executeUpdate).as(table).hasMessageContaining("permission denied");
                    }
                }
                reset(app);
            }

            // Inserting a row for another school is refused by the policy's WITH CHECK.
            select(app, other.tenantId());
            assertThatThrownBy(() -> {
                try (PreparedStatement insert = app.prepareStatement("""
                        insert into academics.subject (id, tenant_id, name, created_at, updated_at)
                        values (?, ?, 'Intruder', now(), now())""")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, school.tenantId());
                    insert.executeUpdate();
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
        }
    }

    /**
     * Foreign key checks are not filtered by row-level security, so a key between two school-owned tables must
     * include tenant_id; otherwise a row could point at another school's row.
     */
    @Test
    void foreignKeysBetweenSchoolTablesIncludeTheSchool() throws Exception {
        List<String> problems = new ArrayList<>();
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement("""
                        select cn.nspname || '.' || c.relname, con.conname,
                               exists (select 1 from unnest(con.conkey) k
                                       join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k
                                       where a.attname = 'tenant_id')
                        from pg_constraint con
                        join pg_class c on c.oid = con.conrelid
                        join pg_namespace cn on cn.oid = c.relnamespace
                        join pg_class r on r.oid = con.confrelid
                        join pg_namespace rn on rn.oid = r.relnamespace
                        where con.contype = 'f'
                          and not (rn.nspname = 'platform' and r.relname = 'tenant')
                          and exists (select 1 from pg_attribute a
                                      where a.attrelid = con.conrelid and a.attname = 'tenant_id' and not a.attisdropped)
                          and exists (select 1 from pg_attribute a
                                      where a.attrelid = con.confrelid and a.attname = 'tenant_id' and not a.attisdropped)
                        order by 1, 2""");
                ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                if (!rs.getBoolean(3) && !SINGLE_COLUMN_FK_ALLOWED.contains(rs.getString(1))) {
                    problems.add(rs.getString(1) + "." + rs.getString(2) + " does not include tenant_id");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void aRowCannotPointAtAnotherSchoolsRow() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        String classId = fixtures.schoolClass(admin, "Class 2");
        School other = api.signup();

        try (Connection app = appConnection()) {
            select(app, other.tenantId());
            assertThatThrownBy(() -> {
                try (PreparedStatement insert = app.prepareStatement("""
                        insert into academics.section (id, tenant_id, class_id, name, created_at, updated_at)
                        values (?, ?, ?, 'X', now(), now())""")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, other.tenantId());
                    insert.setObject(3, UUID.fromString(classId));
                    insert.executeUpdate();
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("foreign key");
        }
    }

    /** One row in every fee table: setup, dues, a payment, a waiver, an online order with its event, a reminder. */
    private void feeRecords(Session admin, String yearId, String classId, String student) throws Exception {
        FeeFixtures fees = new FeeFixtures(api, mvc);
        Heads heads = fees.defaultHeads(admin);
        fees.publishedStructure(admin, yearId, classId, heads);
        api.put("/api/fees/late-fee-rule", admin.accessToken(), """
                {"mode":"FLAT","graceDays":1,"flatPaise":5000}""").andExpect(status().isOk());
        api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"SIBLING","mode":"PERCENT","percent":5,"headIds":["%s"],"reason":"Sibling"}"""
                .formatted(student, heads.tuition())).andExpect(status().isCreated());
        fees.cash(admin, student, 1_000_00);
        List<String> instalments = fees.instalmentIds(admin, student);
        api.post("/api/fees/students/" + student + "/late-fee-waivers", admin.accessToken(), """
                {"instalmentId":"%s","reason":"Kind"}""".formatted(instalments.getFirst())).andExpect(status().isOk());
        String order = TestApi.read(api.post("/api/fees/students/" + student + "/orders", admin.accessToken(), """
                {"instalmentIds":["%s"]}""".formatted(instalments.get(1))).andExpect(status().isCreated()),
                "$.gatewayOrderId");
        api.post("/api/payments/sandbox/orders/" + order + "/complete", admin.accessToken(), """
                {"outcome":"FAILURE"}""").andExpect(status().isOk());
        api.post("/api/fees/reminders", admin.accessToken(), """
                {"studentIds":["%s"]}""".formatted(student)).andExpect(jsonPath("$.requested").value(1));
    }

    private static boolean canUpdateTenantId(Connection connection, String table) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(
                "select has_column_privilege(current_user, ?, 'tenant_id', 'UPDATE')")) {
            s.setString(1, table);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static List<TableInfo> tables() throws SQLException {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement("""
                        select n.nspname || '.' || c.relname,
                               c.relrowsecurity,
                               exists (select 1 from pg_attribute a
                                       where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped),
                               exists (select 1 from pg_policy p
                                       where p.polrelid = c.oid and p.polname = 'tenant_isolation'
                                         and p.polcmd = '*' and p.polqual is not null and p.polwithcheck is not null),
                               c.relispartition,
                               has_table_privilege(?, c.oid, 'select'),
                               pg_get_userbyid(c.relowner) = ?
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where c.relkind in ('r', 'p')
                          and n.nspname not like 'pg\\_%' and n.nspname <> 'information_schema'
                        order by 1""")) {
            s.setString(1, APP_USER);
            s.setString(2, APP_USER);
            List<TableInfo> tables = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    tables.add(new TableInfo(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4),
                            rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7)));
                }
            }
            return tables;
        }
    }

    private static void select(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            s.setString(1, tenantId.toString());
            s.execute();
        }
    }

    private static void reset(Connection connection) throws SQLException {
        connection.createStatement().execute("reset app.tenant_id");
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long countAsOwner(Connection owner, String table, UUID tenantId) throws SQLException {
        try (PreparedStatement s = owner.prepareStatement("select count(*) from " + table + " where tenant_id = ?")) {
            s.setObject(1, tenantId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
