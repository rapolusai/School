package com.akshara.timetable;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SlotRepository extends JpaRepository<Slot, UUID> {

    List<Slot> findByAcademicYearId(UUID academicYearId);

    List<Slot> findByAcademicYearIdAndSectionId(UUID academicYearId, UUID sectionId);

    List<Slot> findByAcademicYearIdAndTeacherId(UUID academicYearId, UUID teacherId);

    List<Slot> findByAcademicYearIdAndDayOfWeek(UUID academicYearId, int dayOfWeek);

    List<Slot> findByAcademicYearIdAndSectionIdAndSubjectId(UUID academicYearId, UUID sectionId, UUID subjectId);

    long countByAcademicYearId(UUID academicYearId);

    /**
     * Makes timetable changes in one school take turns until the transaction ends, so two people saving at once
     * cannot book the same teacher twice. Other schools are not held up.
     */
    @Query(value = "select count(*) from (select pg_advisory_xact_lock(?1, ?2)) l", nativeQuery = true)
    long lockSchool(int area, int school);
}
