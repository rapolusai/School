package com.akshara.library;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when a copy is kept for the member first in a title's hold queue, in the transaction that keeps it. A
 * future app-notification module can tell the member; the library itself already shows the hold on the member's family
 * page and, when the school has library messages on, queues a message to a student's parent.
 */
public record LibraryHoldReady(UUID tenantId, UUID reservationId, UUID titleId, String title, MemberType memberType,
        UUID memberId, LocalDate holdUntil) {
}
