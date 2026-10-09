package com.akshara.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.akshara.shared.TenantContext;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** One school must never see or change another school's data, through the API or straight in the database. */
class TenantIsolationIT extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    private School schoolA;
    private School schoolB;
    private Session adminA;
    private Session adminB;
    private String teacherA;

    @BeforeEach
    void twoSchools() throws Exception {
        schoolA = api.signup();
        schoolB = api.signup();
        adminA = api.login(schoolA);
        adminB = api.login(schoolB);
        teacherA = api.createUser(adminA, "Tara Teacher", "tara@" + schoolA.code() + ".akshara.test",
                List.of("TEACHER"));
    }

    @Test
    void usersOfOneSchoolAreInvisibleToAnother() throws Exception {
        api.get("/api/users", adminA.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(teacherA)))
                .andExpect(jsonPath("$.length()").value(2));
        api.get("/api/users", adminB.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(teacherA))))
                .andExpect(jsonPath("$.length()").value(1));
        api.get("/api/users/" + teacherA, adminB.accessToken()).andExpect(status().isNotFound());
        api.get("/api/users/" + teacherA, adminA.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("TEACHER"));
    }

    @Test
    void auditTrailsAreSeparate() throws Exception {
        api.get("/api/audit-events?limit=200", adminA.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].entityId", hasItem(teacherA)))
                .andExpect(jsonPath("$[*].action", hasItem("school.created")));
        api.get("/api/audit-events?limit=200", adminB.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].entityId", not(hasItem(teacherA))));
    }

    @Test
    void sameEmailCanExistInTwoSchools() throws Exception {
        String shared = "shared@akshara.test";
        api.createUser(adminA, "Same Person", shared, List.of("PARENT"));
        api.createUser(adminB, "Same Person", shared, List.of("PARENT"));
        api.login(schoolA.code(), shared, com.akshara.support.TestApi.PASSWORD);
        api.login(schoolB.code(), shared, com.akshara.support.TestApi.PASSWORD);
    }

    @Test
    void pooledConnectionsForgetTheSchoolWhenTheyAreReturned() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long inSchoolA = TenantContext.runAs(schoolA.tenantId(),
                () -> jdbc.queryForObject("select count(*) from identity.user_account", Long.class));
        assertThat(inSchoolA).isEqualTo(2);
        for (int i = 0; i < 20; i++) {
            assertThat(jdbc.queryForObject("select count(*) from identity.user_account", Long.class)).isZero();
        }
    }

    @Test
    void theDatabaseShowsNoSchoolRowsWithoutASchoolSelected() throws Exception {
        try (Connection app = appConnection()) {
            assertThat(count(app, "identity.user_account")).isZero();
            assertThat(count(app, "identity.role")).isZero();
            assertThat(count(app, "audit.audit_event")).isZero();
            assertThat(count(app, "identity.refresh_token")).isZero();
            assertThat(count(app, "identity.user_role")).isZero();
        }
    }

    @Test
    void theDatabaseOnlyShowsTheSelectedSchool() throws Exception {
        try (Connection app = appConnection()) {
            select(app, schoolA.tenantId());
            assertThat(count(app, "identity.user_account")).isEqualTo(2);
            assertThat(count(app, "identity.role")).isEqualTo(7);
            assertThat(distinctTenants(app, "identity.user_account")).containsExactly(schoolA.tenantId());
            assertThat(distinctTenants(app, "audit.audit_event")).containsExactly(schoolA.tenantId());

            select(app, schoolB.tenantId());
            assertThat(count(app, "identity.user_account")).isEqualTo(1);
        }
    }

    @Test
    void theDatabaseRefusesWritesIntoAnotherSchool() throws Exception {
        try (Connection app = appConnection()) {
            select(app, schoolA.tenantId());
            assertThatThrownBy(() -> {
                try (PreparedStatement insert = app.prepareStatement("""
                        insert into identity.user_account (id, tenant_id, email, name, password_hash, status,
                            created_at, updated_at)
                        values (?, ?, 'intruder@akshara.test', 'Intruder', 'x', 'ACTIVE', now(), now())""")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, schoolB.tenantId());
                    insert.executeUpdate();
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");

            // Updates and deletes aimed at the other school simply match nothing.
            try (PreparedStatement update = app.prepareStatement(
                    "update identity.user_account set name = 'Hacked' where tenant_id = ?")) {
                update.setObject(1, schoolB.tenantId());
                assertThat(update.executeUpdate()).isZero();
            }
            // Linking one of A's users to one of B's roles is refused too.
            assertThatThrownBy(() -> {
                try (PreparedStatement link = app.prepareStatement("""
                        insert into identity.user_role (user_id, role_id)
                        select ?, r.id from identity.role r where r.tenant_id = ? limit 1""")) {
                    link.setObject(1, UUID.fromString(teacherA));
                    link.setObject(2, schoolB.tenantId());
                    link.executeUpdate();
                    // RLS hides B's roles, so the select finds nothing; insert the id directly as well.
                }
                UUID roleOfB = roleIdAsOwner(schoolB.tenantId());
                try (PreparedStatement link = app.prepareStatement(
                        "insert into identity.user_role (user_id, role_id) values (?, ?)")) {
                    link.setObject(1, UUID.fromString(teacherA));
                    link.setObject(2, roleOfB);
                    link.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void theAuditTrailCannotBeRewritten() throws Exception {
        try (Connection app = appConnection()) {
            select(app, schoolA.tenantId());
            assertThatThrownBy(() -> app.createStatement().executeUpdate("update audit.audit_event set action = 'x'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> app.createStatement().executeUpdate("delete from audit.audit_event"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    private static void select(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            s.setString(1, tenantId.toString());
            s.execute();
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<UUID> distinctTenants(Connection connection, String table) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery("select distinct tenant_id from " + table)) {
            List<UUID> ids = new java.util.ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getObject(1, UUID.class));
            }
            return ids;
        }
    }

    private static UUID roleIdAsOwner(UUID tenantId) throws SQLException {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement(
                        "select id from identity.role where tenant_id = ? and code = 'SCHOOL_ADMIN'")) {
            s.setObject(1, tenantId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }
}
