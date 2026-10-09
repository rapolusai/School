package com.akshara.staff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, UUID> {

    List<LeaveBalance> findByUserId(UUID userId);

    Optional<LeaveBalance> findByUserIdAndLeaveTypeIdAndAcademicYearId(UUID userId, UUID leaveTypeId,
            UUID academicYearId);

    long countByLeaveTypeId(UUID leaveTypeId);

    long countByAcademicYearId(UUID academicYearId);
}
