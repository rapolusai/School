package com.akshara.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published (as a Spring application event, inside the deciding transaction) when a class teacher or an admin approves
 * or rejects a child's leave request ({@code status} APPROVED or REJECTED). A later notifications slice can tell the
 * parent who applied ({@code requestedById}); the parent app already shows the decision.
 */
public record ChildLeaveDecided(UUID tenantId, UUID requestId, UUID studentId, UUID sectionId, ChildLeaveStatus status,
        LocalDate fromDate, LocalDate toDate, boolean halfDay, UUID requestedById, UUID decidedById, Instant at) {
}
