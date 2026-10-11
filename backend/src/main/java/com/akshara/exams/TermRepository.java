package com.akshara.exams;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface TermRepository extends JpaRepository<Term, UUID> {

    @Query("select t from Term t where t.academicYearId = ?1 order by t.startsOn, lower(t.name)")
    List<Term> findByYear(UUID academicYearId);

    @Query("select count(t) > 0 from Term t where t.academicYearId = ?1 and lower(t.name) = lower(?2) and t.id <> ?3")
    boolean nameTaken(UUID academicYearId, String name, UUID except);

    long countByAcademicYearId(UUID academicYearId);
}
