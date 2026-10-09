package com.akshara.staff;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsUsage;

/** Tells school setup which academic years have leave requests or hand-set balances, so they are not deleted. */
@Component
@Transactional(readOnly = true)
class StaffUsage implements AcademicsUsage {

    private final LeaveRequestRepository requests;
    private final LeaveBalanceRepository balances;

    StaffUsage(LeaveRequestRepository requests, LeaveBalanceRepository balances) {
        this.requests = requests;
        this.balances = balances;
    }

    @Override
    public long sectionUseCount(UUID sectionId) {
        return 0;
    }

    @Override
    public long yearUseCount(UUID yearId) {
        return requests.countByAcademicYearId(yearId) + balances.countByAcademicYearId(yearId);
    }

    @Override
    public Map<UUID, Long> enrolledPerSection(UUID yearId) {
        return Map.of();
    }
}
