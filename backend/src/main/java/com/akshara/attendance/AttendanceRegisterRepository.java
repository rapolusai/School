package com.akshara.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface AttendanceRegisterRepository extends JpaRepository<AttendanceRegister, UUID> {

    Optional<AttendanceRegister> findBySectionIdAndAttendanceDate(UUID sectionId, LocalDate date);

    /** Holds the register until the transaction ends, so two people saving the same day take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AttendanceRegister r where r.sectionId = ?1 and r.attendanceDate = ?2")
    Optional<AttendanceRegister> lockBySectionAndDate(UUID sectionId, LocalDate date);

    List<AttendanceRegister> findByAttendanceDate(LocalDate date);

    @Query("select r from AttendanceRegister r where r.sectionId = ?1 and r.attendanceDate between ?2 and ?3 "
            + "order by r.attendanceDate")
    List<AttendanceRegister> findInRange(UUID sectionId, LocalDate from, LocalDate to);

    long countBySectionId(UUID sectionId);

    long countByAcademicYearId(UUID academicYearId);
}
