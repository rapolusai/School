package com.akshara.govreport;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.akshara.govreport.GovReportTypes.CodeListType;

/**
 * The module's Spring Data repositories, one interface per entity, kept in one file because each is a few lines.
 * Row-level security already keeps every query to the current school.
 */
final class GovReportRepositories {

    private GovReportRepositories() {
    }
}

interface CodeListRepository extends JpaRepository<CodeListEntry, UUID> {

    List<CodeListEntry> findByListOrderByDisplayOrderAscCodeAscEffectiveFromAsc(CodeListType list);

    List<CodeListEntry> findAllByOrderByListAscDisplayOrderAscCodeAscEffectiveFromAsc();

    long countByList(CodeListType list);
}

interface GovSchoolProfileRepository extends JpaRepository<GovSchoolProfile, UUID> {

    Optional<GovSchoolProfile> findByAcademicYearId(UUID academicYearId);
}

interface StudentGovRecordRepository extends JpaRepository<StudentGovRecord, UUID> {

    Optional<StudentGovRecord> findByStudentId(UUID studentId);

    List<StudentGovRecord> findByStudentIdIn(Collection<UUID> studentIds);

    List<StudentGovRecord> findByApaarIdIn(Collection<String> apaarIds);
}

interface RteIntakeRepository extends JpaRepository<RteIntake, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from RteIntake i where i.id = :id")
    Optional<RteIntake> lockById(@Param("id") UUID id);

    List<RteIntake> findByAcademicYearIdOrderByCreatedAtDesc(UUID academicYearId);

    List<RteIntake> findAllByOrderByCreatedAtDesc();
}

interface RteApplicationRepository extends JpaRepository<RteApplication, UUID> {

    List<RteApplication> findByIntakeIdOrderByCreatedAtAsc(UUID intakeId);

    Optional<RteApplication> findByIntakeIdAndApplicationId(UUID intakeId, UUID applicationId);

    long countByIntakeIdAndWithdrawnFalse(UUID intakeId);
}

interface RteLotteryRunRepository extends JpaRepository<RteLotteryRun, UUID> {

    List<RteLotteryRun> findByIntakeIdOrderByRunNoDesc(UUID intakeId);

    int countByIntakeId(UUID intakeId);
}

interface RteAllotmentRepository extends JpaRepository<RteAllotment, UUID> {

    List<RteAllotment> findByRunIdOrderByPositionAsc(UUID runId);
}

interface SchemeEnrolmentRepository extends JpaRepository<SchemeEnrolment, UUID> {

    List<SchemeEnrolment> findByAcademicYearIdOrderByCreatedAtDesc(UUID academicYearId);

    List<SchemeEnrolment> findByAcademicYearIdAndSchemeCodeOrderByCreatedAtDesc(UUID academicYearId, String scheme);

    Optional<SchemeEnrolment> findByStudentIdAndAcademicYearIdAndSchemeCode(UUID studentId, UUID academicYearId,
            String schemeCode);
}
