package com.akshara.timetable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TeacherAssignmentRepository extends JpaRepository<TeacherAssignment, UUID> {

    List<TeacherAssignment> findByAcademicYearId(UUID academicYearId);

    List<TeacherAssignment> findByAcademicYearIdAndSectionId(UUID academicYearId, UUID sectionId);

    List<TeacherAssignment> findByAcademicYearIdAndTeacherId(UUID academicYearId, UUID teacherId);

    Optional<TeacherAssignment> findByAcademicYearIdAndSectionIdAndSubjectId(UUID academicYearId, UUID sectionId,
            UUID subjectId);

    long countByAcademicYearId(UUID academicYearId);
}
