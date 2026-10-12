package com.akshara.frontoffice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.akshara.students.GuardianRelation;
import com.akshara.students.Phones;

/** Request bodies of the front office API (docs/api/phase-2-frontoffice.md). The demo data uses the same records. */
public final class FrontOfficeForms {

    private FrontOfficeForms() {
    }

    /**
     * A visitor as the office or the gate records them. With {@code expectedOn} the visitor is pre-approved for that
     * day instead of checked in now. {@code override} lets the office admit someone on the blocklist (audited).
     */
    public record VisitorForm(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String phone,
            @NotNull(message = "Choose the purpose.") VisitorPurpose purpose,
            @Size(max = 300) String purposeNote,
            UUID toMeetUserId,
            @Size(max = 200) String toMeetName,
            @Min(1) @Max(50) Integer peopleCount,
            IdType idType,
            @Size(max = 20) String badgeNo,
            LocalDate expectedOn,
            Boolean override) {
    }

    public record BlockForm(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String phone,
            @NotBlank(message = "Give the reason.") @Size(max = 500) String reason) {
    }

    /** A parent's early pickup request for the child in the path. */
    public record PickupRequest(
            @NotNull(message = "Give the pickup time.") Instant pickupAt,
            @NotBlank(message = "Give the reason.") @Size(max = 500) String reason,
            @NotNull(message = "Say who collects the child.") CollectorKind collectorKind,
            @Size(max = 200) String collectorName,
            @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String collectorPhone) {
    }

    /** The office raises a pass on a parent's phone call. */
    public record OfficePassForm(
            @NotNull(message = "Pick the student.") UUID studentId,
            @NotNull(message = "Give the pickup time.") Instant pickupAt,
            @NotBlank(message = "Give the reason.") @Size(max = 500) String reason,
            @NotNull(message = "Say who collects the child.") CollectorKind collectorKind,
            @Size(max = 200) String collectorName,
            @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String collectorPhone) {
    }

    public record DecisionForm(@Size(max = 500) String note) {
    }

    /** The gate releases the child: the OTP the collector reads out and who collected. */
    public record ReleaseForm(
            @NotBlank(message = "Enter the OTP.") @Size(max = 12) String otp,
            @NotBlank(message = "Say who collected the child.") @Size(max = 200) String collectedByName) {
    }

    public record PickupPersonForm(
            @NotBlank @Size(max = 200) String name,
            @NotBlank(message = "Give the relation.") @Size(max = 60) String relation,
            @NotBlank @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String phone) {
    }

    public record CallForm(
            @NotNull(message = "Incoming or outgoing?") CallDirection direction,
            @NotBlank @Size(max = 200) String callerName,
            @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String phone,
            @NotBlank(message = "Say what the call was about.") @Size(max = 500) String about,
            @Size(max = 500) String outcome,
            LocalDate followUpOn,
            UUID assignedToUserId,
            Instant calledAt) {
    }

    public record CloseForm(@Size(max = 500) String outcome) {
    }

    public record EnquiryForm(
            @NotNull(message = "Walk-in or phone?") EnquirySource source,
            @NotNull(message = "Choose the topic.") EnquiryTopic topic,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String phone,
            @Size(max = 200) String childName,
            @Size(max = 40) String className,
            @NotBlank(message = "Write what was asked.") @Size(max = 2000) String details,
            LocalDate followUpOn,
            UUID assignedToUserId,
            Instant enquiredAt) {
    }

    /**
     * Converts an admission enquiry into an application in the admissions module. The child's name and the parent's
     * phone come from the enquiry unless given here.
     */
    public record ConvertForm(
            @NotNull(message = "Pick a class.") UUID classId,
            @NotNull(message = "Pick an academic year.") UUID academicYearId,
            @NotNull(message = "Give the child's date of birth.") LocalDate dateOfBirth,
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            GuardianRelation relation,
            @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String phone) {
    }

    public record CourierForm(
            @NotNull(message = "Inward or outward?") CourierDirection direction,
            @NotBlank(message = "Say what the item is.") @Size(max = 300) String item,
            @NotBlank @Size(max = 200) String sender,
            @NotBlank @Size(max = 200) String receiver,
            @NotNull(message = "Give the date.") LocalDate itemDate,
            @Size(max = 100) String carrier,
            @Size(max = 60) String trackingNo,
            @Size(max = 200) String receivedBy,
            @Size(max = 500) String note) {
    }
}
