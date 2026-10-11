package com.akshara.exams;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ExamClassRepository extends JpaRepository<ExamClass, UUID> {

    List<ExamClass> findByExamId(UUID examId);

    List<ExamClass> findByExamIdIn(Collection<UUID> examIds);

    Optional<ExamClass> findByExamIdAndClassId(UUID examId, UUID classId);

    /** The classes of every exam of a year covering the class. */
    @Query("select ec from ExamClass ec join Exam e on e.id = ec.examId where e.academicYearId = ?1 "
            + "and ec.classId = ?2")
    List<ExamClass> findByYearAndClass(UUID academicYearId, UUID classId);
}
