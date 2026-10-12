package com.akshara.frontoffice;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the office approves an early pickup pass, in the transaction that approves it. The class teacher of
 * the student's section ({@code classTeacherUserId}, null when the section has none) is told in the app through the
 * front office's own dashboard card; a future app-notification module can push it to their phone as well.
 */
public record EarlyPickupApproved(UUID tenantId, UUID passId, UUID studentId, String studentName, UUID sectionId,
        UUID classTeacherUserId, Instant pickupAt, Instant at) {
}
