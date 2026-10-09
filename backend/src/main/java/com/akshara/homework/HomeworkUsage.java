package com.akshara.homework;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsUsage;

/**
 * Tells school setup which academic years have homework, so a year is not deleted from under it. Homework links to
 * sections go with the section, so sections are not held back.
 */
@Component
@Transactional(readOnly = true)
class HomeworkUsage implements AcademicsUsage {

    private final HomeworkRepository homework;

    HomeworkUsage(HomeworkRepository homework) {
        this.homework = homework;
    }

    @Override
    public long sectionUseCount(UUID sectionId) {
        return 0;
    }

    @Override
    public long yearUseCount(UUID yearId) {
        return homework.countByAcademicYearId(yearId);
    }

    @Override
    public Map<UUID, Long> enrolledPerSection(UUID yearId) {
        return Map.of();
    }
}
