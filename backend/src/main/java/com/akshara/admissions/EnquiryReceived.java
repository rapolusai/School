package com.akshara.admissions;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a family sends an enquiry through a school's public admissions form, for the notifications module
 * (not built yet) to tell the admissions office. Carries ids only; a listener reads what it needs through
 * {@link AdmissionsService} as that school.
 */
public record EnquiryReceived(UUID tenantId, UUID applicationId, UUID classId, UUID academicYearId, Instant at) {
}
