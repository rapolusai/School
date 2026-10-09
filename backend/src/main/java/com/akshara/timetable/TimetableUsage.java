package com.akshara.timetable;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsUsage;

/**
 * Tells school setup which academic years have timetables, so a year is not deleted from under them. A section's
 * timetable goes with the section, so sections are not held back.
 */
@Component
@Transactional(readOnly = true)
class TimetableUsage implements AcademicsUsage {

    private final TeacherAssignmentRepository assignments;
    private final SlotRepository slots;

    TimetableUsage(TeacherAssignmentRepository assignments, SlotRepository slots) {
        this.assignments = assignments;
        this.slots = slots;
    }

    @Override
    public long sectionUseCount(UUID sectionId) {
        return 0;
    }

    @Override
    public long yearUseCount(UUID yearId) {
        return assignments.countByAcademicYearId(yearId) + slots.countByAcademicYearId(yearId);
    }

    @Override
    public Map<UUID, Long> enrolledPerSection(UUID yearId) {
        return Map.of();
    }
}
