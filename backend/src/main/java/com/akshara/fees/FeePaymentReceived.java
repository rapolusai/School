package com.akshara.fees;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a fee receipt is issued, at the counter or online, inside the transaction that records it.
 * {@link FeeMessages} queues the parent's receipt message in the same transaction; listeners that act outside the
 * database should use {@code @TransactionalEventListener} (after commit) to act only on payments that were saved.
 */
public record FeePaymentReceived(UUID tenantId, UUID receiptId, String receiptNo, UUID studentId, long amountPaise,
        PaymentMode mode, Instant receivedAt) {
}
