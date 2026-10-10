package com.akshara.billing;

import java.time.LocalDate;
import java.util.UUID;

/** Akshara issued a GST invoice to a school for a period of its subscription. Amounts are in paise. */
public record SubscriptionInvoiceIssued(UUID tenantId, UUID invoiceId, String invoiceNo, long totalPaise,
        LocalDate dueDate) {
}
