package com.akshara.fees;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a fee receipt is issued, at the counter or online, inside the transaction that records it. Listen
 * with {@code @TransactionalEventListener} (after commit) to act only on payments that were saved, for example to send
 * the parent a receipt message.
 */
public record FeePaymentReceived(UUID tenantId, UUID receiptId, String receiptNo, UUID studentId, long amountPaise,
        PaymentMode mode, Instant receivedAt) {
}
