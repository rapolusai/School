package com.akshara.health;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the infirmary closes a student's visit with SENT_HOME or HOSPITAL, in the same transaction. The
 * attendance module may mark the rest of the day accordingly and a class teacher's day view may show it; nothing
 * consumes it yet (docs/api/phase-3-health.md, "Events").
 */
public record StudentSentHome(UUID tenantId, UUID visitId, UUID studentId, String studentName, UUID sectionId,
        VisitOutcome outcome, Instant at) {
}
