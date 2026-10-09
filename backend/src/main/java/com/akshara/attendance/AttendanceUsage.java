package com.akshara.attendance;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsUsage;

/** Tells school setup which years and sections have attendance registers, so they are not deleted. */
@Component
@Transactional(readOnly = true)
class AttendanceUsage implements AcademicsUsage {

    private final AttendanceRegisterRepository registers;

    AttendanceUsage(AttendanceRegisterRepository registers) {
        this.registers = registers;
    }

    @Override
    public long sectionUseCount(UUID sectionId) {
        return registers.countBySectionId(sectionId);
    }

    @Override
    public long yearUseCount(UUID yearId) {
        return registers.countByAcademicYearId(yearId);
    }

    @Override
    public Map<UUID, Long> enrolledPerSection(UUID yearId) {
        return Map.of();
    }
}
