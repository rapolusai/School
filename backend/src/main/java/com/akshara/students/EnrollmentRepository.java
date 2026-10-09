package com.akshara.students;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    Optional<Enrollment> findByStudentIdAndAcademicYearId(UUID studentId, UUID academicYearId);

    List<Enrollment> findByStudentId(UUID studentId);

    List<Enrollment> findByStudentIdInAndAcademicYearId(Collection<UUID> studentIds, UUID academicYearId);

    long countBySectionId(UUID sectionId);

    long countByAcademicYearId(UUID academicYearId);

    /** Active students per section in a year: these are the seats in use. */
    @Query("select e.sectionId, count(e) from Enrollment e join Student s on s.id = e.studentId "
            + "where e.academicYearId = ?1 and s.status = com.akshara.students.StudentStatus.ACTIVE "
            + "group by e.sectionId")
    List<Object[]> countActivePerSection(UUID academicYearId);

    @Query("select count(e) from Enrollment e join Student s on s.id = e.studentId "
            + "where e.academicYearId = ?1 and e.sectionId = ?2 and s.status = com.akshara.students.StudentStatus.ACTIVE")
    long countActive(UUID academicYearId, UUID sectionId);

    @Query("select count(e) > 0 from Enrollment e where e.academicYearId = ?1 and e.sectionId = ?2 "
            + "and e.rollNo = ?3 and e.id <> ?4")
    boolean rollNoTaken(UUID academicYearId, UUID sectionId, int rollNo, UUID exceptEnrollmentId);

    @Query("select e.rollNo from Enrollment e where e.academicYearId = ?1 and e.sectionId in ?2 and e.rollNo is not null")
    List<Integer> rollNos(UUID academicYearId, Collection<UUID> sectionIds);

    @Query("select e.sectionId, e.rollNo from Enrollment e where e.academicYearId = ?1 and e.rollNo is not null")
    List<Object[]> rollNosInYear(UUID academicYearId);

    @Query("select coalesce(max(e.rollNo), 0) from Enrollment e where e.academicYearId = ?1 and e.sectionId = ?2")
    int maxRollNo(UUID academicYearId, UUID sectionId);

    @Query("select s, e from Enrollment e join Student s on s.id = e.studentId "
            + "where e.academicYearId = ?1 and e.sectionId = ?2 and s.status = com.akshara.students.StudentStatus.ACTIVE "
            + "order by lower(s.firstName), lower(s.lastName), s.admissionNo")
    List<Object[]> activeIn(UUID academicYearId, UUID sectionId);
}
