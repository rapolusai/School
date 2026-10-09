package com.akshara.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface StaffAttendanceRepository extends JpaRepository<StaffAttendance, UUID> {

    Optional<StaffAttendance> findByUserIdAndAttendanceDate(UUID userId, LocalDate date);

    /** Holds the day until the transaction ends, so a double tap on "Check in" cannot create two rows. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StaffAttendance a where a.userId = ?1 and a.attendanceDate = ?2")
    Optional<StaffAttendance> lockDay(UUID userId, LocalDate date);

    List<StaffAttendance> findByAttendanceDate(LocalDate date);

    @Query("select a from StaffAttendance a where a.attendanceDate between ?1 and ?2")
    List<StaffAttendance> findBetween(LocalDate from, LocalDate to);

    @Query("select a from StaffAttendance a where a.userId = ?1 and a.attendanceDate between ?2 and ?3 "
            + "order by a.attendanceDate")
    List<StaffAttendance> findOfUserBetween(UUID userId, LocalDate from, LocalDate to);

    List<StaffAttendance> findByLeaveRequestId(UUID leaveRequestId);
}
