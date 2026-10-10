package com.akshara.privacy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when a student's personal data is erased for an erasure request, inside that transaction. Other modules
 * that keep their own copies of a child's name (the message log, later homework and communication) can listen and
 * clear them. {@code unlinkedUserIds} are sign-ins that no longer point at any student or guardian record.
 */
public record StudentDataErased(UUID tenantId, UUID studentId, UUID requestId, List<UUID> unlinkedUserIds,
        Instant at) {
}
