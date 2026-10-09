package com.akshara.communication;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService.Actor;
import com.akshara.communication.CommunicationTypes.ReviewOutcome;
import com.akshara.communication.CommunicationTypes.Status;
import com.akshara.shared.TenantContext;

/**
 * Sends what is due: scheduled circulars whose time has come and calendar reminders whose lead time has started.
 * Finds the schools with work due, then, as each school in turn, handles each circular or entry in its own short
 * transaction, holding the row with {@code for update skip locked} so two API instances never send the same thing.
 * Only ids are logged.
 */
@Service
public class CommunicationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CommunicationScheduler.class);
    private static final Actor SCHEDULER = new Actor(null, null);

    private final CircularRepository circulars;
    private final CalendarEntryRepository entries;
    private final CircularSender sender;
    private final CalendarReminders reminders;
    private final CommunicationProperties.Scheduler config;
    private final TransactionTemplate tx;

    CommunicationScheduler(CircularRepository circulars, CalendarEntryRepository entries, CircularSender sender,
            CalendarReminders reminders, CommunicationProperties properties, PlatformTransactionManager transactions) {
        this.circulars = circulars;
        this.entries = entries;
        this.sender = sender;
        this.reminders = reminders;
        this.config = properties.scheduler();
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * One round over every school with circulars or reminders due at {@code now}. Must run with no school selected.
     * Returns how many circulars were sent (reminders included).
     */
    public int runDue(Instant now) {
        if (TenantContext.current().isPresent()) {
            throw new IllegalStateException("The scheduler works across schools; run it with no school selected");
        }
        LocalDate today = LocalDate.ofInstant(now, CalendarService.INDIA);
        int sent = 0;
        List<UUID> withCirculars = tx.execute(s -> circulars.tenantsWithDueCirculars(now, config.schoolsPerRound()));
        for (UUID school : withCirculars == null ? List.<UUID>of() : withCirculars) {
            try {
                sent += sendDueCirculars(school, now);
            } catch (RuntimeException e) {
                log.warn("Sending scheduled circulars for school {} stopped early ({})", school,
                        e.getClass().getSimpleName());
            }
        }
        List<UUID> withReminders = tx.execute(s -> entries.tenantsWithDueReminders(today, config.schoolsPerRound()));
        for (UUID school : withReminders == null ? List.<UUID>of() : withReminders) {
            try {
                sent += sendDueReminders(school, today, now);
            } catch (RuntimeException e) {
                log.warn("Sending calendar reminders for school {} stopped early ({})", school,
                        e.getClass().getSimpleName());
            }
        }
        return sent;
    }

    /** Sends at most one batch of the school's scheduled circulars that are due at {@code now}. */
    public int sendDueCirculars(UUID tenantId, Instant now) {
        return TenantContext.runAs(tenantId, () -> {
            List<UUID> due = tx.execute(s -> circulars.dueIds(Status.SCHEDULED, now, Limit.of(config.batchSize())));
            int sent = 0;
            for (UUID id : due == null ? List.<UUID>of() : due) {
                try {
                    if (Boolean.TRUE.equals(tx.execute(s -> sendCircular(id, now)))) {
                        sent++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Scheduled circular {} was not sent ({})", id, e.getClass().getSimpleName());
                }
            }
            return sent;
        });
    }

    /** Sends at most one batch of the school's calendar reminders that are due on {@code today}. */
    public int sendDueReminders(UUID tenantId, LocalDate today, Instant now) {
        return TenantContext.runAs(tenantId, () -> {
            List<UUID> due = tx.execute(s -> entries.dueReminderIds(today, config.batchSize()));
            int sent = 0;
            for (UUID id : due == null ? List.<UUID>of() : due) {
                try {
                    if (Boolean.TRUE.equals(tx.execute(s -> entries.lockDueReminder(id, today)
                            .map(e -> reminders.send(e, now) != null).orElse(false)))) {
                        sent++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Reminder of calendar entry {} was not sent ({})", id, e.getClass().getSimpleName());
                }
            }
            return sent;
        });
    }

    private boolean sendCircular(UUID id, Instant now) {
        Circular circular = circulars.lockDue(id, now).orElse(null);
        if (circular == null) {
            return false;
        }
        // Shown as sent by whoever let it go: the approver, or the author who scheduled it.
        String by = circular.getReviewOutcome() == ReviewOutcome.APPROVED && circular.getReviewedByName() != null
                ? circular.getReviewedByName() : circular.getCreatedByName();
        sender.send(circular, SCHEDULER, by, now, sender.circularTemplate(circular));
        return true;
    }
}
