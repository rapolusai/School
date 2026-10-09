package com.akshara.staff;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    /** Newest first. */
    @Query("select r from LeaveRequest r where r.userId = ?1 order by r.fromDate desc, r.createdAt desc")
    List<LeaveRequest> findByUser(UUID userId);

    @Query("select r from LeaveRequest r where r.userId = ?1 and r.academicYearId = ?2 "
            + "order by r.fromDate desc, r.createdAt desc")
    List<LeaveRequest> findByUserAndYear(UUID userId, UUID academicYearId);

    /** Requests that hold days (pending or approved) and touch the date range. */
    @Query("select r from LeaveRequest r where r.userId = ?1 and r.status in ?4 and r.fromDate <= ?3 "
            + "and r.toDate >= ?2")
    List<LeaveRequest> findOverlapping(UUID userId, LocalDate from, LocalDate to, Collection<LeaveStatus> statuses);

    /** Oldest first, so the longest-waiting request is at the top of an inbox. */
    @Query("select r from LeaveRequest r where r.status = com.akshara.staff.LeaveStatus.PENDING "
            + "order by r.createdAt")
    List<LeaveRequest> findPending();

    @Query("select r from LeaveRequest r where r.userId = ?1 and r.status in ?2")
    List<LeaveRequest> findByUserAndStatusIn(UUID userId, Collection<LeaveStatus> statuses);

    /** Approved requests of everyone that touch the date range, for reports and the day sheet. */
    @Query("select r from LeaveRequest r where r.status = com.akshara.staff.LeaveStatus.APPROVED "
            + "and r.fromDate <= ?2 and r.toDate >= ?1")
    List<LeaveRequest> findApprovedBetween(LocalDate from, LocalDate to);

    long countByLeaveTypeId(UUID leaveTypeId);

    long countByAcademicYearId(UUID academicYearId);
}
