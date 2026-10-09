package com.akshara.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when leave that had been approved is cancelled (by the requester before it starts, by an approver, or
 * because the person left the school), so substitutes arranged for {@link StaffLeaveApproved} can be released.
 */
public record StaffLeaveCancelled(UUID tenantId, UUID requestId, UUID userId, LocalDate fromDate, LocalDate toDate,
        UUID cancelledById, Instant at) {
}
