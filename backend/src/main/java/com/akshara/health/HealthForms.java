package com.akshara.health;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.akshara.students.Phones;

/** Request bodies of the health and safety API (docs/api/phase-3-health.md). The demo data uses the same records. */
public final class HealthForms {

    private HealthForms() {
    }

    /** The head of a medical record, kept by staff. The policy number is stored in full and shown masked. */
    public record RecordForm(
            @Pattern(regexp = "^(A|B|AB|O)[+-]$", message = "Choose a blood group.") String bloodGroup,
            @Size(max = 200) String doctorName,
            @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String doctorPhone,
            @Size(max = 200) String insuranceProvider,
            @Size(max = 40) String insurancePolicyNo,
            @Size(max = 1000) String notes) {
    }

    /** A parent's (or the office's, on paper) consent for first aid and for a hospital transfer. */
    public record ConsentForm(boolean firstAid, boolean hospital) {
    }

    /** An allergy (with a severity), a chronic condition, a regular medication or a vaccination. */
    public record ItemForm(
            @NotNull(message = "Say what kind of entry this is.") ItemKind kind,
            @NotBlank(message = "Give a name.") @Size(max = 120) String name,
            @Size(max = 500) String detail,
            AllergySeverity severity,
            LocalDate itemDate) {
    }

    public record GrowthForm(
            @NotNull(message = "Give the date.") LocalDate measuredOn,
            @DecimalMin(value = "30.0", message = "Height is 30 to 250 cm.")
            @DecimalMax(value = "250.0", message = "Height is 30 to 250 cm.") BigDecimal heightCm,
            @DecimalMin(value = "1.0", message = "Weight is 1 to 250 kg.")
            @DecimalMax(value = "250.0", message = "Weight is 1 to 250 kg.") BigDecimal weightKg) {
    }

    /* ------------------------------------------------------------------ infirmary */

    public record MedicineForm(
            @NotBlank(message = "Give the medicine's name.") @Size(max = 120) String name,
            @NotBlank(message = "Give the unit (tablet, ml, sachet).") @Size(max = 20) String unit,
            @NotNull @Min(0) @Max(100000) Integer quantity,
            @Min(0) @Max(100000) Integer reorderLevel,
            LocalDate expiresOn) {
    }

    public record GivenForm(
            @NotNull(message = "Pick the medicine.") UUID medicineId,
            @NotNull @Min(1) @Max(1000) Integer quantity) {
    }

    /** A visit as the nurse records it when someone comes in. A staff visit names the staff member's sign-in. */
    public record VisitForm(
            @NotNull(message = "Student or staff?") PersonKind personKind,
            UUID studentId,
            UUID staffUserId,
            Instant timeIn,
            @NotBlank(message = "Say what the complaint is.") @Size(max = 500) String complaint,
            @DecimalMin(value = "30.0", message = "Temperature is 30 to 45 °C.")
            @DecimalMax(value = "45.0", message = "Temperature is 30 to 45 °C.") BigDecimal temperatureC,
            @Size(max = 1000) String treatment,
            @Valid @Size(max = 10) List<GivenForm> medicines) {
    }

    /** Closes a visit: how it ended and when. A parent is informed through the outbox when sent home or to hospital. */
    public record CloseForm(
            @NotNull(message = "Say how the visit ended.") VisitOutcome outcome,
            Instant timeOut,
            @Size(max = 1000) String treatment,
            Boolean parentInformed) {
    }

    /* ------------------------------------------------------------------ safety */

    public record DrillForm(
            @NotNull(message = "Give the date.") LocalDate drillDate,
            @NotNull(message = "Choose the kind of drill.") DrillKind kind,
            @NotBlank(message = "Say which block.") @Size(max = 100) String block,
            @NotNull(message = "Give the time to clear, in seconds.") @Min(1) @Max(7200) Integer timeToClearSeconds,
            @NotNull(message = "Give the attendance.") @Min(0) @Max(100000) Integer attendance,
            @Size(max = 1000) String issues,
            LocalDate nextDue) {
    }

    public record AuditForm(
            @NotNull(message = "Choose the area.") AuditArea area,
            @NotNull(message = "Give the date.") LocalDate auditDate,
            @NotBlank(message = "Write the finding.") @Size(max = 1000) String finding,
            @NotNull(message = "Choose the severity.") AuditSeverity severity,
            LocalDate dueOn) {
    }

    public record CloseAuditForm(
            LocalDate closedOn,
            @Size(max = 1000) String closureNote) {
    }

    public record IncidentForm(
            Instant incidentAt,
            @NotNull(message = "Choose the kind of incident.") IncidentKind kind,
            @NotNull(message = "Choose the severity.") IncidentSeverity severity,
            UUID studentId,
            @Size(max = 200) String personName,
            @Size(max = 200) String place,
            @NotBlank(message = "Write what happened.") @Size(max = 2000) String description,
            @Size(max = 2000) String actions,
            Boolean parentInformed) {
    }

    public record KitForm(@NotBlank(message = "Give the location.") @Size(max = 120) String location) {
    }

    public record KitCheckForm(
            LocalDate checkedOn,
            boolean complete,
            LocalDate earliestExpiry,
            @Size(max = 500) String missingItems) {
    }

    /* ------------------------------------------------------------------ POCSO */

    public record CommitteeForm(LocalDate formedOn) {
    }

    public record MemberForm(
            @NotNull(message = "Pick a member of staff.") UUID userId,
            @NotNull(message = "Choose the role.") CommitteeRole role,
            LocalDate appointedOn) {
    }

    public record MeetingForm(
            @NotNull(message = "Give the date.") LocalDate meetingDate,
            @NotBlank(message = "Write the minutes.") @Size(max = 8000) String minutes) {
    }

    public record ComplaintForm(
            @NotNull(message = "Give the date.") LocalDate complaintDate,
            @NotNull(message = "Choose the category.") ComplaintCategory category,
            @NotBlank(message = "Describe the complaint.") @Size(max = 4000) String description,
            ComplaintStatus status,
            @Size(max = 4000) String actions,
            @Size(max = 1000) String complainantNote) {
    }

    /* ------------------------------------------------------------------ staff checks */

    public record VerificationForm(
            @NotNull(message = "Choose the status.") VerificationStatus status,
            LocalDate requestedOn,
            LocalDate clearedOn,
            @Size(max = 60) String referenceNo,
            LocalDate expiresOn,
            @Size(max = 500) String note) {
    }

    public record TrainingForm(
            @NotNull(message = "Pick a member of staff.") UUID userId,
            @NotNull(message = "Choose the training.") TrainingKind kind,
            @NotNull(message = "Give the date.") LocalDate trainedOn,
            LocalDate expiresOn,
            @Size(max = 200) String provider) {
    }
}
