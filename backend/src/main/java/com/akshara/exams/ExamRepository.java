package com.akshara.exams;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ExamRepository extends JpaRepository<Exam, UUID> {

    @Query("select e from Exam e where e.academicYearId = ?1 order by e.createdAt")
    List<Exam> findByYear(UUID academicYearId);

    @Query("select e from Exam e where e.termId = ?1 order by e.createdAt")
    List<Exam> findByTerm(UUID termId);

    @Query("select count(e) > 0 from Exam e where e.termId = ?1 and lower(e.name) = lower(?2) and e.id <> ?3")
    boolean nameTaken(UUID termId, String name, UUID except);

    long countByTermId(UUID termId);
}
