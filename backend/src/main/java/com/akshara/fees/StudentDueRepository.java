package com.akshara.fees;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface StudentDueRepository extends JpaRepository<StudentDue, UUID> {

    List<StudentDue> findByStudentId(UUID studentId);

    /** Every due of a student, locked for the length of the transaction, so two payments cannot both use a balance. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from StudentDue d where d.studentId = ?1 order by d.id")
    List<StudentDue> lockByStudent(UUID studentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from StudentDue d where d.studentId = ?1 and d.structureId = ?2 order by d.id")
    List<StudentDue> lockByStudentAndStructure(UUID studentId, UUID structureId);

    boolean existsByStudentIdAndAcademicYearId(UUID studentId, UUID academicYearId);

    @Query("select distinct d.studentId from StudentDue d where d.academicYearId = ?1")
    List<UUID> studentIdsInYear(UUID academicYearId);

    @Query("select distinct d.studentId from StudentDue d where d.structureId = ?1")
    List<UUID> studentIdsOfStructure(UUID structureId);

    @Query("select distinct d.structureId from StudentDue d where d.studentId = ?1 and d.academicYearId = ?2")
    List<UUID> structureIdsOf(UUID studentId, UUID academicYearId);

    List<StudentDue> findByInstalmentIdIn(Collection<UUID> instalmentIds);

    @Query("select d.structureId, count(distinct d.studentId) from StudentDue d where d.academicYearId = ?1 "
            + "group by d.structureId")
    List<Object[]> studentsPerStructure(UUID academicYearId);

    /**
     * Per student of a year: gross, concession, paid, balance due by {@code asOf} (inclusive) and balance overdue
     * (due before {@code asOf}).
     */
    @Query("select d.studentId, sum(d.grossPaise), sum(d.concessionPaise), sum(d.paidPaise), "
            + "sum(case when d.dueDate <= ?2 then d.grossPaise - d.concessionPaise - d.paidPaise else 0 end), "
            + "sum(case when d.dueDate < ?2 then d.grossPaise - d.concessionPaise - d.paidPaise else 0 end) "
            + "from StudentDue d where d.academicYearId = ?1 group by d.studentId")
    List<Object[]> summaryPerStudent(UUID academicYearId, LocalDate asOf);

    /** Dues of a year with something still owed after their due date. */
    @Query("select d from StudentDue d where d.academicYearId = ?1 and d.dueDate < ?2 "
            + "and d.grossPaise - d.concessionPaise - d.paidPaise > 0")
    List<StudentDue> overdue(UUID academicYearId, LocalDate asOf);

    @Query("select d from StudentDue d where d.studentId = ?1 and d.dueDate < ?2 "
            + "and d.grossPaise - d.concessionPaise - d.paidPaise > 0")
    List<StudentDue> overdueOf(UUID studentId, LocalDate asOf);

    @Query("select coalesce(sum(d.concessionPaise), 0) from StudentDue d where d.studentId = ?1 "
            + "and d.academicYearId = ?2")
    long concessionTotal(UUID studentId, UUID academicYearId);
}
