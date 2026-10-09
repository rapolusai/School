package com.akshara.students;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsUsage;

/** Tells school setup which years and sections students are enrolled in. */
@Component
@Transactional(readOnly = true)
class EnrollmentUsage implements AcademicsUsage {

    private final EnrollmentRepository enrollments;

    EnrollmentUsage(EnrollmentRepository enrollments) {
        this.enrollments = enrollments;
    }

    @Override
    public long sectionUseCount(UUID sectionId) {
        return enrollments.countBySectionId(sectionId);
    }

    @Override
    public long yearUseCount(UUID yearId) {
        return enrollments.countByAcademicYearId(yearId);
    }

    @Override
    public Map<UUID, Long> enrolledPerSection(UUID yearId) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : enrollments.countActivePerSection(yearId)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }
}
