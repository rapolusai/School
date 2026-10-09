package com.akshara.attendance;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published (as a Spring application event, inside the saving transaction) every time a section's register for a
 * day is saved. {@code firstSave} is false when an existing register was changed; {@code counts} are the marks after
 * the save. Other modules (reports, dashboards, analytics) can listen for it.
 */
public record AttendanceSaved(UUID tenantId, UUID registerId, UUID sectionId, LocalDate date, boolean firstSave,
        AttendanceCounts counts) {
}
