package com.akshara.exams;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsUsage;

/**
 * Tells school setup which sections have mark sheets and which academic years have terms or grading choices, so
 * neither is deleted from under the marks.
 */
@Component
@Transactional(readOnly = true)
class ExamsUsage implements AcademicsUsage {

    private final MarkSheetRepository sheets;
    private final TermRepository terms;
    private final ClassSchemeRepository classSchemes;

    ExamsUsage(MarkSheetRepository sheets, TermRepository terms, ClassSchemeRepository classSchemes) {
        this.sheets = sheets;
        this.terms = terms;
        this.classSchemes = classSchemes;
    }

    @Override
    public long sectionUseCount(UUID sectionId) {
        return sheets.countBySectionId(sectionId);
    }

    @Override
    public long yearUseCount(UUID yearId) {
        return terms.countByAcademicYearId(yearId) + classSchemes.countByAcademicYearId(yearId);
    }

    @Override
    public Map<UUID, Long> enrolledPerSection(UUID yearId) {
        return Map.of();
    }
}
