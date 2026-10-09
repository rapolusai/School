package com.akshara.admissions;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an application moves to another stage (including when the child is admitted as a student), for the
 * notifications module (not built yet) to keep the family informed. {@code actorId} is the staff member who moved it,
 * or null when the system did. {@code studentId} is set once the child has been admitted.
 */
public record ApplicationStageChanged(UUID tenantId, UUID applicationId, ApplicationStage from, ApplicationStage to,
        UUID actorId, UUID studentId, Instant at) {
}
