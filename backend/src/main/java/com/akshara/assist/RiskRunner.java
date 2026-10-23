package com.akshara.assist;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.shared.TenantContext;

/**
 * Runs the early warning across schools: asks the database which schools have it on (a function that works
 * without a school selected), then works as each school in turn, in its own transaction, so one school's failure
 * does not stop the others.
 */
@Service
public class RiskRunner {

    private static final Logger log = LoggerFactory.getLogger(RiskRunner.class);

    private final AssistSettingsRepository settings;
    private final RiskService risk;
    private final TransactionTemplate tx;

    RiskRunner(AssistSettingsRepository settings, RiskService risk, PlatformTransactionManager transactions) {
        this.settings = settings;
        this.risk = risk;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Recomputes every school's watch list. Must run with no school selected. Returns schools done. */
    public int runAll(LocalDate today, Instant now) {
        if (TenantContext.current().isPresent()) {
            throw new IllegalStateException("The early warning runs across schools; run it with no school selected");
        }
        List<UUID> schools = tx.execute(s -> settings.tenantsWithEarlyWarning());
        int done = 0;
        for (UUID school : schools == null ? List.<UUID>of() : schools) {
            try {
                TenantContext.runAs(school, () -> tx.execute(s -> risk.compute(AssistAccess.SYSTEM, today, now)));
                done++;
            } catch (RuntimeException e) {
                log.warn("Early warning for school {} failed ({})", school, e.getClass().getSimpleName());
            }
        }
        return done;
    }
}
