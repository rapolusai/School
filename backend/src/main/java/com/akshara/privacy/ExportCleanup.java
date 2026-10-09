package com.akshara.privacy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.TenantContext;

/**
 * Deletes the files of data exports that have passed their 7 days, school by school, keeping each export's record.
 * Runs on a timer ({@link PrivacyConfig}) with no school selected, like the message dispatcher: a security-definer
 * function names the schools with expired files, and each school is then cleaned as itself. Only ids are logged.
 */
@Component
class ExportCleanup {

    private static final Logger log = LoggerFactory.getLogger(ExportCleanup.class);
    static final int SCHOOLS_PER_ROUND = 100;
    static final Actor SYSTEM = new Actor(null, "System");

    private final DataExportRepository exports;
    private final AuditService audit;
    private final TransactionTemplate tx;

    ExportCleanup(DataExportRepository exports, AuditService audit, PlatformTransactionManager transactions) {
        this.exports = exports;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** One round over every school with exports past their expiry at {@code now}. Returns how many were deleted. */
    int purgeExpired(Instant now) {
        if (TenantContext.current().isPresent()) {
            throw new IllegalStateException("The export clean-up works across schools; run it with no school selected");
        }
        List<UUID> schools = tx.execute(s -> exports.tenantsWithExpiredExports(now, SCHOOLS_PER_ROUND));
        int purged = 0;
        for (UUID school : schools == null ? List.<UUID>of() : schools) {
            try {
                purged += purgeSchool(school, now);
            } catch (RuntimeException e) {
                log.warn("Deleting expired exports for school {} failed ({})", school, e.getClass().getSimpleName());
            }
        }
        return purged;
    }

    /** Deletes the school's expired export files and audits each one. */
    int purgeSchool(UUID tenantId, Instant now) {
        Integer purged = TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            List<DataExport> expired = exports.expired(now);
            for (DataExport export : expired) {
                export.discard(ExportStatus.EXPIRED, now);
            }
            exports.flush();
            for (DataExport export : expired) {
                audit.record(SYSTEM, "data_export.expired", "data_export", export.getId(),
                        Map.of("requestId", export.getRequestId().toString(), "fileName", export.getFileName()));
            }
            return expired.size();
        }));
        return purged == null ? 0 : purged;
    }
}
