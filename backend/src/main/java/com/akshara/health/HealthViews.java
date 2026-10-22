package com.akshara.health;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.akshara.health.HealthAlerts.AllergyBadge;
import com.akshara.health.HealthText.Due;

/** Response bodies of the health and safety API (docs/api/phase-3-health.md). Numbers that identify are masked. */
public final class HealthViews {

    private HealthViews() {
    }

    /** A student or a member of staff for the infirmary's search. */
    public record PersonRef(UUID id, PersonKind kind, String name, String admissionNo, String label) {
    }

    public record ItemView(UUID id, ItemKind kind, String name, String detail, AllergySeverity severity,
            LocalDate itemDate, ItemSource source, String addedByName) {
    }

    public record GrowthView(UUID id, LocalDate measuredOn, BigDecimal heightCm, BigDecimal weightKg,
            String recordedByName) {
    }

    public record ConsentView(boolean firstAid, boolean hospital, String byName, Instant at) {
    }

    /** The primary guardian, from the students module: the person to call. */
    public record EmergencyContact(UUID guardianId, String name, String phone, String phoneMasked, String email) {
    }

    /**
     * A student's whole medical record. {@code insurancePolicyNoMasked} shows the last four characters only;
     * {@code visits} are the student's infirmary visits, latest first.
     */
    public record MedicalRecordView(UUID studentId, String studentName, String admissionNo, String className,
            LocalDate dateOfBirth, boolean exists, String bloodGroup, String doctorName, String doctorPhone,
            String insuranceProvider, String insurancePolicyNoMasked, String notes, ConsentView consent,
            EmergencyContact emergencyContact, List<ItemView> allergies, List<ItemView> conditions,
            List<ItemView> medications, List<ItemView> vaccinations, List<GrowthView> growth,
            GrowthChart.Series chart, List<VisitView> visits, Instant updatedAt) {
    }

    /* ------------------------------------------------------------------ infirmary */

    public record MedicineView(UUID id, String name, String unit, int quantity, int reorderLevel,
            LocalDate expiresOn, boolean low, boolean expired) {
    }

    public record GivenView(UUID medicineId, String name, String unit, int quantity) {
    }

    public record VisitView(UUID id, PersonKind personKind, UUID studentId, UUID staffUserId, String personName,
            String className, UUID sectionId, Instant timeIn, Instant timeOut, String complaint,
            BigDecimal temperatureC, String treatment, VisitOutcome outcome, boolean parentInformed,
            List<GivenView> medicines, List<AllergyBadge> allergies, String recordedByName) {
    }

    /** One day's infirmary: the visits (latest first), who is there now, and medicines below reorder level. */
    public record InfirmaryDay(LocalDate date, List<VisitView> visits, int open, int medicinesLow) {
    }

    /* ------------------------------------------------------------------ safety */

    public record DrillView(UUID id, LocalDate drillDate, DrillKind kind, String block, int timeToClearSeconds,
            int attendance, String issues, LocalDate nextDue, Due due, String conductedByName) {
    }

    public record AuditView(UUID id, AuditArea area, LocalDate auditDate, String finding, AuditSeverity severity,
            AuditStatus status, LocalDate dueOn, boolean overdue, LocalDate closedOn, String closureNote,
            String auditedByName) {
    }

    public record IncidentView(UUID id, Instant incidentAt, IncidentKind kind, IncidentSeverity severity,
            UUID studentId, String personName, String className, String place, String description, String actions,
            boolean parentInformed, String reportedByName) {
    }

    public record KitView(UUID id, String location, LocalDate checkedOn, boolean complete, LocalDate earliestExpiry,
            String missingItems, String checkedByName, Due expiry, boolean checkDue) {
    }

    /** The safety screen in one call. */
    public record SafetyOverview(LocalDate today, List<DrillView> drills, List<AuditView> audits,
            List<IncidentView> incidents, List<KitView> kits) {
    }

    /* ------------------------------------------------------------------ POCSO */

    public record MemberView(UUID id, UUID userId, String name, String designation, CommitteeRole role,
            LocalDate appointedOn) {
    }

    public record MeetingView(UUID id, LocalDate meetingDate, String minutes, String recordedByName) {
    }

    /** {@code canReadComplaints}: the signed-in person holds pocso.committee or is a member. */
    public record CommitteeView(LocalDate formedOn, List<MemberView> members, List<MeetingView> meetings,
            boolean canReadComplaints, boolean canManage) {
    }

    /** {@code complainantNote} is given on the detail only. */
    public record ComplaintView(UUID id, LocalDate complaintDate, ComplaintCategory category, String description,
            ComplaintStatus status, String actions, String complainantNote, String recordedByName,
            Instant updatedAt) {
    }

    /* ------------------------------------------------------------------ staff checks */

    public record TrainingView(UUID id, UUID userId, String name, TrainingKind kind, LocalDate trainedOn,
            LocalDate expiresOn, String provider, boolean needsRenewal) {
    }

    /** One staff member's verification (masked reference) and trainings, with what is due. */
    public record VerificationView(UUID userId, String name, String designation, boolean active,
            VerificationStatus status, LocalDate requestedOn, LocalDate clearedOn, String referenceNoMasked,
            LocalDate expiresOn, String note, String updatedByName, Due due, List<TrainingView> trainings,
            boolean pocsoTrainingDue, boolean firstAidTrainingDue) {
    }

    public record StaffChecks(LocalDate asOf, List<VerificationView> rows, int verificationsDue,
            int verificationsMissing, int trainingsDue) {
    }

    /* ------------------------------------------------------------------ reports */

    public record NamedCount(String name, long count) {
    }

    public record MonthCount(String month, long visits, long students, long staff, long sentHome, long hospital) {
    }

    public record InfirmaryReport(LocalDate from, LocalDate to, long total, List<MonthCount> byMonth,
            List<NamedCount> byOutcome, List<NamedCount> byComplaint) {
    }

    /** Counts only: no student is named. */
    public record ClassAllergies(UUID sectionId, String label, long students, long withAllergies, long severe) {
    }

    public record AllergiesReport(LocalDate asOf, long students, long withAllergies, long severe,
            List<ClassAllergies> byClass) {
    }

    public record DrillsReport(LocalDate asOf, LocalDate nextDue, Due due, List<DrillView> rows) {
    }

    public record VerificationsReport(LocalDate asOf, int due, int missing, List<VerificationView> rows) {
    }
}
