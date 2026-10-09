package com.akshara.billing;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.akshara.billing.BillingViews.PlatformHealth;
import com.akshara.billing.BillingViews.PlatformHealth.App;
import com.akshara.billing.BillingViews.PlatformHealth.Database;
import com.akshara.billing.BillingViews.PlatformHealth.Outbox;
import com.akshara.billing.BillingViews.PlatformHealth.Schools;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantStatus;
import com.akshara.platform.TenantSummary;

/**
 * The Super Admin's platform health page: schools by status, people and students on roll, the notifications outbox,
 * whether the database answers, and the app's version and uptime. Cross-school numbers come from the school list and
 * from security-definer functions that return counts only, so row-level security is never switched off.
 */
@Service
public class PlatformHealthService {

    private static final Logger log = LoggerFactory.getLogger(PlatformHealthService.class);

    private final TenantDirectory tenants;
    private final PlatformCounts counts;
    private final String version;

    PlatformHealthService(TenantDirectory tenants, PlatformCounts counts,
            @Value("${akshara.build.version:unknown}") String version) {
        this.tenants = tenants;
        this.counts = counts;
        this.version = version;
    }

    public PlatformHealth health() {
        Instant now = Instant.now();
        Instant started = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        App app = new App(version, started, Math.max(0, Duration.between(started, now).toSeconds()));
        try {
            long before = System.nanoTime();
            counts.ping();
            Database database = new Database(true, Duration.ofNanos(System.nanoTime() - before).toMillis());
            List<TenantSummary> schools = tenants.summaries();
            Map<TenantStatus, Long> byStatus = new EnumMap<>(TenantStatus.class);
            for (TenantStatus status : TenantStatus.values()) {
                byStatus.put(status, 0L);
            }
            schools.forEach(s -> byStatus.merge(s.status(), 1L, Long::sum));
            long users = schools.stream().mapToLong(TenantSummary::userCount).sum();
            long students = counts.activeStudents().values().stream().mapToLong(Long::longValue).sum();
            Outbox outbox = counts.outbox();
            return new PlatformHealth(now, new Schools(schools.size(), byStatus), users, students, outbox, database,
                    app);
        } catch (DataAccessException | TransactionException e) {
            // The type is enough to investigate; the message could name hosts.
            log.warn("Platform health check could not reach the database ({})", e.getClass().getSimpleName());
            return new PlatformHealth(now, null, null, null, null, new Database(false, null), app);
        }
    }
}
