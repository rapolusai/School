package com.akshara.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ChildLeaveRepository extends JpaRepository<ChildLeaveRequest, UUID> {

    /** One child's requests, the latest dates first. */
    @Query("select r from ChildLeaveRequest r where r.studentId = ?1 order by r.fromDate desc, r.createdAt desc")
    List<ChildLeaveRequest> findByStudent(UUID studentId);

    /** Pending or approved requests of the child that touch the date range. */
    @Query("select r from ChildLeaveRequest r where r.studentId = ?1 and r.fromDate <= ?3 and r.toDate >= ?2 "
            + "and r.status in (com.akshara.attendance.ChildLeaveStatus.PENDING, "
            + "com.akshara.attendance.ChildLeaveStatus.APPROVED)")
    List<ChildLeaveRequest> findOpenOverlapping(UUID studentId, LocalDate from, LocalDate to);

    /** Approved requests of these students that cover the date. */
    @Query("select r from ChildLeaveRequest r where r.status = com.akshara.attendance.ChildLeaveStatus.APPROVED "
            + "and r.fromDate <= ?1 and r.toDate >= ?1 and r.studentId in ?2")
    List<ChildLeaveRequest> findApprovedOn(LocalDate date, Collection<UUID> studentIds);

    /** Approved requests of one student that touch the date range. */
    @Query("select r from ChildLeaveRequest r where r.status = com.akshara.attendance.ChildLeaveStatus.APPROVED "
            + "and r.studentId = ?1 and r.fromDate <= ?3 and r.toDate >= ?2 order by r.fromDate")
    List<ChildLeaveRequest> findApprovedBetween(UUID studentId, LocalDate from, LocalDate to);

    /** Waiting requests, oldest first, so the longest-waiting note is at the top. */
    @Query("select r from ChildLeaveRequest r where r.status = com.akshara.attendance.ChildLeaveStatus.PENDING "
            + "order by r.createdAt")
    List<ChildLeaveRequest> findPending();

    /** Decided or cancelled requests changed since the instant, newest first. */
    @Query("select r from ChildLeaveRequest r where r.status <> com.akshara.attendance.ChildLeaveStatus.PENDING "
            + "and r.updatedAt >= ?1 order by r.updatedAt desc")
    List<ChildLeaveRequest> findClosedSince(Instant since);

    long countBySectionId(UUID sectionId);

    long countByAcademicYearId(UUID academicYearId);
}
