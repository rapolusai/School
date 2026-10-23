package com.akshara.exams;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ExamClassRepository extends JpaRepository<ExamClass, UUID> {

    List<ExamClass> findByExamId(UUID examId);

    List<ExamClass> findByExamIdIn(Collection<UUID> examIds);

    Optional<ExamClass> findByExamIdAndClassId(UUID examId, UUID classId);

    /** The exam's class, locked for this transaction: publishing and withdrawing its results are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ec from ExamClass ec where ec.examId = ?1 and ec.classId = ?2")
    Optional<ExamClass> lockByExamAndClass(UUID examId, UUID classId);

    /** The classes of every exam of a year covering the class. */
    @Query("select ec from ExamClass ec join Exam e on e.id = ec.examId where e.academicYearId = ?1 "
            + "and ec.classId = ?2")
    List<ExamClass> findByYearAndClass(UUID academicYearId, UUID classId);
}
