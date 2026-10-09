package com.akshara.fees;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published once per student when staff ask for an overdue-fee reminder, inside the transaction that records the
 * request. The fees module only records it; delivering a message (SMS, WhatsApp, app notice) belongs to the
 * communication module, which should listen after commit.
 */
public record FeeReminderRequested(UUID tenantId, UUID reminderId, UUID studentId, long overduePaise,
        long daysOverdue, LocalDate oldestDueDate) {
}
