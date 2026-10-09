package com.akshara.fees;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published once per student when staff ask for an overdue-fee reminder, inside the transaction that records the
 * request. {@link FeeMessages} queues the parent's message in the same transaction.
 */
public record FeeReminderRequested(UUID tenantId, UUID reminderId, UUID studentId, long overduePaise,
        long daysOverdue, LocalDate oldestDueDate) {
}
