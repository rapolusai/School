package com.akshara.shared;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start if the runtime database role could bypass row-level security (superuser, BYPASSRLS, or owner of
 * the school tables). Those roles would silently see every school's data.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseRoleGuard implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final AksharaProperties properties;

    public DatabaseRoleGuard(DataSource dataSource, AksharaProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String problem = check();
        if (problem != null && !properties.security().allowPrivilegedDbRole()) {
            throw new IllegalStateException("Unsafe database role: " + problem
                    + ". Connect as the application role (for example akshara_app), not the owner or a superuser.");
        }
    }

    /** Returns a description of the problem, or null when the role is safe. */
    public String check() {
        Boolean privileged = jdbc.queryForObject(
                "select rolsuper or rolbypassrls from pg_roles where rolname = current_user", Boolean.class);
        if (Boolean.TRUE.equals(privileged)) {
            return "current role is a superuser or has BYPASSRLS";
        }
        Integer owned = jdbc.queryForObject(
                "select count(*) from pg_tables where schemaname in ('identity', 'audit', 'academics', 'students',"
                        + " 'admissions')"
                        + " and tableowner = current_user",
                Integer.class);
        if (owned != null && owned > 0) {
            return "current role owns " + owned + " school tables";
        }
        return null;
    }
}
