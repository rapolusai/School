package com.akshara.lessons;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsUsage;

/**
 * Tells school setup which sections have lesson plans, study material or online tests, and which academic years have
 * syllabi, plans, material or tests, so neither is deleted from under them.
 */
@Component
@Transactional(readOnly = true)
class LessonsUsage implements AcademicsUsage {

    private final LessonPlanRepository plans;
    private final MaterialRepository materials;
    private final TestSectionRepository testSections;
    private final SyllabusRepository syllabi;
    private final OnlineTestRepository tests;

    LessonsUsage(LessonPlanRepository plans, MaterialRepository materials, TestSectionRepository testSections,
            SyllabusRepository syllabi, OnlineTestRepository tests) {
        this.plans = plans;
        this.materials = materials;
        this.testSections = testSections;
        this.syllabi = syllabi;
        this.tests = tests;
    }

    @Override
    public long sectionUseCount(UUID sectionId) {
        return plans.countBySectionId(sectionId) + materials.countBySectionId(sectionId)
                + testSections.countBySectionId(sectionId);
    }

    @Override
    public long yearUseCount(UUID yearId) {
        return syllabi.countByAcademicYearId(yearId) + plans.countByAcademicYearId(yearId)
                + materials.countByAcademicYearId(yearId) + tests.countByAcademicYearId(yearId);
    }

    @Override
    public Map<UUID, Long> enrolledPerSection(UUID yearId) {
        return Map.of();
    }
}
