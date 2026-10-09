package com.akshara.homework;

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
 * Runs the evening-before homework reminders across schools: asks the database which schools have reminders on and
 * homework due (a function that works without a school selected), then works as each school in turn, in its own
 * transaction, so one school's failure does not stop the others.
 */
@Service
public class HomeworkReminderRunner {

    private static final Logger log = LoggerFactory.getLogger(HomeworkReminderRunner.class);

    private final HomeworkRepository homework;
    private final HomeworkReminders reminders;
    private final TransactionTemplate tx;

    HomeworkReminderRunner(HomeworkRepository homework, HomeworkReminders reminders,
            PlatformTransactionManager transactions) {
        this.homework = homework;
        this.reminders = reminders;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Queues reminders for homework due on {@code dueOn}. Must run with no school selected. Returns messages
     * queued.
     */
    public int remindDue(LocalDate dueOn, Instant at) {
        if (TenantContext.current().isPresent()) {
            throw new IllegalStateException("Homework reminders work across schools; run them with no school selected");
        }
        List<UUID> schools = tx.execute(s -> homework.tenantsWithDueReminders(dueOn));
        int queued = 0;
        for (UUID school : schools == null ? List.<UUID>of() : schools) {
            try {
                Integer n = TenantContext.runAs(school, () -> tx.execute(s -> reminders.dueReminders(dueOn, at)));
                queued += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.warn("Homework reminders for school {} failed ({})", school, e.getClass().getSimpleName());
            }
        }
        return queued;
    }
}
