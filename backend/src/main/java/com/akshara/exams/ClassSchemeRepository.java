package com.akshara.exams;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ClassSchemeRepository extends JpaRepository<ClassScheme, UUID> {

    List<ClassScheme> findByAcademicYearId(UUID academicYearId);

    Optional<ClassScheme> findByAcademicYearIdAndClassId(UUID academicYearId, UUID classId);

    long countBySchemeId(UUID schemeId);

    long countByAcademicYearId(UUID academicYearId);
}
