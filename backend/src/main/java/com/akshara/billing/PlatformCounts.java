package com.akshara.billing;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.akshara.billing.BillingViews.PlatformHealth.Outbox;

/**
 * Cross-school numbers for the Super Admin. Row-level security hides every school's rows when no school is selected,
 * so these read narrow security-definer functions (V10) that return counts and sums only, never rows.
 */
@Component
class PlatformCounts {

    /** A school's unpaid invoices as of a day. */
    record Unpaid(long unpaidCount, long unpaidPaise, long overdueCount, long overduePaise, LocalDate oldestOverdueDue) {

        static final Unpaid NONE = new Unpaid(0, 0, 0, 0, null);
    }

    private final JdbcTemplate jdbc;

    PlatformCounts(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Active students per school. Schools without students are left out. */
    Map<UUID, Long> activeStudents() {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query("select tenant_id, active_students from billing.tenant_student_counts()",
                rs -> {
                    counts.put(rs.getObject(1, UUID.class), rs.getLong(2));
                });
        return counts;
    }

    Outbox outbox() {
        return jdbc.queryForObject("select queued, failed from billing.outbox_counts()",
                (rs, row) -> new Outbox(rs.getLong(1), rs.getLong(2)));
    }

    /** Unpaid invoices per school on {@code today}. Schools with nothing unpaid are left out. */
    Map<UUID, Unpaid> unpaid(LocalDate today) {
        Map<UUID, Unpaid> totals = new HashMap<>();
        jdbc.query("select tenant_id, unpaid_count, unpaid_paise, overdue_count, overdue_paise, oldest_overdue_due "
                + "from billing.unpaid_invoice_totals(?)", rs -> {
                    Date oldest = rs.getDate(6);
                    totals.put(rs.getObject(1, UUID.class), new Unpaid(rs.getLong(2), rs.getLong(3), rs.getLong(4),
                            rs.getLong(5), oldest == null ? null : oldest.toLocalDate()));
                }, Date.valueOf(today));
        return totals;
    }

    /** Answers when the database takes a simple query. */
    void ping() {
        jdbc.queryForObject("select 1", Integer.class);
    }
}
