package com.akshara.billing;

import java.time.LocalDate;
import java.util.UUID;

/** A school's subscription invoice is fully paid (the Super Admin recorded the last payment). */
public record SubscriptionInvoicePaid(UUID tenantId, UUID invoiceId, String invoiceNo, long totalPaise,
        LocalDate paidOn) {
}
