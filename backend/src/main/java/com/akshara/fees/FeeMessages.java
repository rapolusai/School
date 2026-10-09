package com.akshara.fees;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;

/**
 * Tells the student's primary parent or guardian about a fee receipt and about overdue fees when staff ask for a
 * reminder. The messages go into the notifications outbox in the same transaction as the receipt or reminder, so a
 * rolled-back payment never sends anything. They use the school's alert channel, language and quiet hours. A student
 * with no valid mobile number gets no message; the payment or reminder is recorded all the same.
 *
 * <p>Only payments received today (India time) get a receipt message: payments entered for an earlier day, such as
 * the demo data, are not announced months later.
 */
@Component
class FeeMessages {

    private static final Logger log = LoggerFactory.getLogger(FeeMessages.class);
    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    private final NotificationQueue queue;
    private final NotificationSettingsService settings;
    private final StudentRoster roster;
    private final TenantDirectory tenants;

    FeeMessages(NotificationQueue queue, NotificationSettingsService settings, StudentRoster roster,
            TenantDirectory tenants) {
        this.queue = queue;
        this.settings = settings;
        this.roster = roster;
        this.tenants = tenants;
    }

    static String receiptKey(UUID receiptId) {
        return "fee-receipt:" + receiptId;
    }

    static String reminderKey(UUID reminderId) {
        return "fee-reminder:" + reminderId;
    }

    @EventListener
    void paymentReceived(FeePaymentReceived event) {
        if (!SchoolDay.of(event.receivedAt()).equals(SchoolDay.today())) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("amount", Fees.rupees(event.amountPaise()));
        params.put("receiptNo", event.receiptNo());
        params.put("date", SchoolDay.of(event.receivedAt()).toString());
        send(MessageTemplates.FEE_RECEIPT, event.tenantId(), event.studentId(), params, receiptKey(event.receiptId()));
    }

    @EventListener
    void reminderRequested(FeeReminderRequested event) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("amount", Fees.rupees(event.overduePaise()));
        params.put("date", event.oldestDueDate().toString());
        send(MessageTemplates.FEE_REMINDER, event.tenantId(), event.studentId(), params,
                reminderKey(event.reminderId()));
    }

    private void send(String template, UUID tenantId, UUID studentId, Map<String, String> params, String dedupeKey) {
        StudentRoster.Entry student = roster.student(studentId);
        if (student.guardianPhone() == null || !student.guardianPhone().matches(PHONE)) {
            log.debug("No valid mobile number for student {}; {} not queued", studentId, template);
            return;
        }
        MessageSettings school = settings.current();
        params.put("student", student.fullName());
        params.put("school", tenants.profile(tenantId).map(p -> p.name()).orElse(""));
        String guardian = student.guardianName() == null ? "" : student.guardianName();
        String classLabel = student.classLabel();
        String label = classLabel == null ? student.fullName() : student.fullName() + " (" + classLabel + ")";
        queue.enqueue(template, school.alertLanguage(), params, new Related("student", studentId, label),
                List.of(Recipient.phone(school.absenceAlertChannel(), student.guardianPhone(), guardian,
                        Map.of("guardian", guardian), dedupeKey)),
                Instant.now());
    }
}
