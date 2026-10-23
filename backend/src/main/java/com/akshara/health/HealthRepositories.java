package com.akshara.health;

/*
 * The health module's Spring Data repositories, one interface per table. Kept in one file because each is a few
 * queries; all are package-private and run as the current school.
 */

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface MedicalRecordRepository extends JpaRepository<MedicalRecord, UUID> {

    Optional<MedicalRecord> findByStudentId(UUID studentId);

    List<MedicalRecord> findByStudentIdIn(Collection<UUID> studentIds);
}

interface GrowthMeasurementRepository extends JpaRepository<GrowthMeasurement, UUID> {

    List<GrowthMeasurement> findByStudentIdOrderByMeasuredOnAsc(UUID studentId);

    Optional<GrowthMeasurement> findByStudentIdAndMeasuredOn(UUID studentId, LocalDate measuredOn);
}

interface RecordItemRepository extends JpaRepository<RecordItem, UUID> {

    List<RecordItem> findByStudentIdOrderByKindAscNameAsc(UUID studentId);

    List<RecordItem> findByStudentIdInAndKind(Collection<UUID> studentIds, ItemKind kind);

    /** Every item of a kind in the school: the allergies-by-class report counts these. */
    List<RecordItem> findByKind(ItemKind kind);
}

interface MedicineRepository extends JpaRepository<Medicine, UUID> {

    List<Medicine> findAllByOrderByNameAsc();
}

interface InfirmaryVisitRepository extends JpaRepository<InfirmaryVisit, UUID> {

    @Query("select v from InfirmaryVisit v where v.timeIn >= ?1 and v.timeIn < ?2 order by v.timeIn desc")
    List<InfirmaryVisit> between(Instant from, Instant to);

    @Query("select v from InfirmaryVisit v where v.timeOut is null order by v.timeIn")
    List<InfirmaryVisit> open();

    @Query("select v from InfirmaryVisit v where v.studentId = ?1 order by v.timeIn desc")
    List<InfirmaryVisit> ofStudent(UUID studentId);

    @Query("select v from InfirmaryVisit v where v.studentId in ?1 and v.timeIn >= ?2 and v.timeIn < ?3")
    List<InfirmaryVisit> ofStudentsBetween(Collection<UUID> studentIds, Instant from, Instant to);
}

interface MedicineGivenRepository extends JpaRepository<MedicineGiven, UUID> {

    List<MedicineGiven> findByVisitIdIn(Collection<UUID> visitIds);
}

interface FireDrillRepository extends JpaRepository<FireDrill, UUID> {

    List<FireDrill> findAllByOrderByDrillDateDesc();
}

interface SafetyAuditRepository extends JpaRepository<SafetyAudit, UUID> {

    @Query("select a from SafetyAudit a order by a.status desc, a.dueOn asc nulls last, a.auditDate desc")
    List<SafetyAudit> listed();
}

interface IncidentRepository extends JpaRepository<Incident, UUID> {

    List<Incident> findAllByOrderByIncidentAtDesc();
}

interface FirstAidKitRepository extends JpaRepository<FirstAidKit, UUID> {

    List<FirstAidKit> findAllByOrderByLocationAsc();
}

interface PocsoCommitteeRepository extends JpaRepository<PocsoCommittee, UUID> {

    @Query("select c from PocsoCommittee c")
    Optional<PocsoCommittee> one();
}

interface PocsoMemberRepository extends JpaRepository<PocsoMember, UUID> {

    List<PocsoMember> findAllByOrderByRoleAscNameAsc();

    boolean existsByUserId(UUID userId);
}

interface PocsoMeetingRepository extends JpaRepository<PocsoMeeting, UUID> {

    List<PocsoMeeting> findAllByOrderByMeetingDateDesc();
}

interface PocsoComplaintRepository extends JpaRepository<PocsoComplaint, UUID> {

    List<PocsoComplaint> findAllByOrderByComplaintDateDescCreatedAtDesc();
}

interface StaffVerificationRepository extends JpaRepository<StaffVerification, UUID> {

    Optional<StaffVerification> findByUserId(UUID userId);
}

interface StaffTrainingRepository extends JpaRepository<StaffTraining, UUID> {

    List<StaffTraining> findAllByOrderByTrainedOnDesc();

    List<StaffTraining> findByUserIdOrderByTrainedOnDesc(UUID userId);
}
