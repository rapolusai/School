package com.akshara.library;

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
 * Runs the overdue reminders across schools: asks the database which schools have library messages on and a student's
 * book overdue (a function that works without a school selected), then works as each school in turn, in its own
 * transaction, so one school's failure does not stop the others.
 */
@Service
public class LibraryReminderRunner {

    private static final Logger log = LoggerFactory.getLogger(LibraryReminderRunner.class);

    private final LoanRepository loans;
    private final LibraryReminders reminders;
    private final TransactionTemplate tx;

    LibraryReminderRunner(LoanRepository loans, LibraryReminders reminders, PlatformTransactionManager transactions) {
        this.loans = loans;
        this.reminders = reminders;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Queues the overdue reminders due today. Must run with no school selected. Returns messages queued. */
    public int remindOverdue(LocalDate today, Instant at) {
        if (TenantContext.current().isPresent()) {
            throw new IllegalStateException("Library reminders work across schools; run them with no school selected");
        }
        List<UUID> schools = tx.execute(s -> loans.tenantsWithOverdueReminders(today));
        int queued = 0;
        for (UUID school : schools == null ? List.<UUID>of() : schools) {
            try {
                Integer n = TenantContext.runAs(school, () -> tx.execute(s -> reminders.overdue(today, at)));
                queued += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.warn("Library reminders for school {} failed ({})", school, e.getClass().getSimpleName());
            }
        }
        return queued;
    }
}
