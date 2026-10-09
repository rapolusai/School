package com.akshara.attendance;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AttendanceEntryRepository extends JpaRepository<AttendanceEntry, UUID> {

    List<AttendanceEntry> findByRegisterId(UUID registerId);

    List<AttendanceEntry> findByRegisterIdIn(Collection<UUID> registerIds);

    /** Rows of [registerId, status, count]. */
    @Query("select e.registerId, e.status, count(e) from AttendanceEntry e where e.registerId in ?1 "
            + "group by e.registerId, e.status")
    List<Object[]> countPerRegister(Collection<UUID> registerIds);

    /** Rows of [date, status] for one student, oldest first. */
    @Query("select r.attendanceDate, e.status from AttendanceEntry e join AttendanceRegister r on r.id = e.registerId "
            + "where e.studentId = ?1 and r.attendanceDate between ?2 and ?3 order by r.attendanceDate")
    List<Object[]> daysOf(UUID studentId, LocalDate from, LocalDate to);
}
