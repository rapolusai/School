package com.akshara.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published (as a Spring application event, inside the applying transaction) when a parent applies for their child's
 * leave. A later notifications slice can tell the class teacher of {@code sectionId}; nothing listens yet. Carries no
 * reason text, which may be medical.
 */
public record ChildLeaveRequested(UUID tenantId, UUID requestId, UUID studentId, UUID sectionId, LocalDate fromDate,
        LocalDate toDate, boolean halfDay, UUID requestedById, Instant at) {
}
