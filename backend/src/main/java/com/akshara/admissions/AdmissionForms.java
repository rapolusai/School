package com.akshara.admissions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.akshara.admissions.AdmissionTypes.AssessmentKind;
import com.akshara.admissions.AdmissionTypes.AssessmentMode;
import com.akshara.admissions.AdmissionTypes.FeeStatus;
import com.akshara.admissions.AdmissionTypes.PaymentMethod;
import com.akshara.admissions.AdmissionTypes.YearChoice;
import com.akshara.students.Gender;
import com.akshara.students.GuardianRelation;
import com.akshara.students.Phones;

/** Request bodies of the admissions API. The same records are used by the demo data seeder. */
public final class AdmissionForms {

    /** Largest application fee that can be recorded: ₹10,00,000, in paise. */
    public static final long MAX_FEE_PAISE = 100_000_000L;

    static final String CONSENT_MESSAGE = "Tick the box to let the school use these details for your enquiry.";

    private AdmissionForms() {
    }

    public record GuardianInput(
            @NotBlank @Size(max = 200) String name,
            @NotNull GuardianRelation relation,
            @NotBlank @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String phone,
            @Email @Size(max = 254) String email,
            Boolean primary) {

        /** Leaving out "primary" means false. */
        public GuardianInput {
            primary = Boolean.TRUE.equals(primary);
        }
    }

    /**
     * A new enquiry or application entered by staff, or the edited details of an existing one. {@code stage} and
     * {@code note} are only read when creating: a new record starts as an ENQUIRY (the default) or an APPLICATION.
     */
    public record ApplicationRequest(
            ApplicationStage stage,
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @NotNull @Past LocalDate dateOfBirth,
            Gender gender,
            @Size(max = 200) String previousSchool,
            @NotNull(message = "Pick a class.") UUID classId,
            @NotNull(message = "Pick an academic year.") UUID academicYearId,
            @NotNull(message = "Choose how the family reached the school.") ApplicationSource source,
            UUID assignedToId,
            LocalDate followUpOn,
            @Size(max = 2000) String note,
            @NotEmpty(message = "Add at least one parent or guardian.") @Size(max = 4,
                    message = "Add at most 4 parents or guardians.") List<@NotNull @Valid GuardianInput> guardians) {
    }

    /** Moves an application. Moving to OFFERED may set the offer dates (default: today, valid for 14 days). */
    public record StageRequest(
            @NotNull(message = "Choose a stage.") ApplicationStage stage,
            @Size(max = 2000) String note,
            LocalDate offeredOn,
            LocalDate offerValidUntil) {
    }

    public record NoteRequest(@NotBlank @Size(max = 2000) String note) {
    }

    public record SlotRequest(
            @NotNull(message = "Choose a test or an interview.") AssessmentKind kind,
            @NotNull(message = "Pick a date and time.") Instant scheduledAt,
            @NotNull(message = "Choose in person or online.") AssessmentMode mode,
            @Size(max = 200) String location,
            @Size(max = 500) String meetingLink,
            UUID interviewerId) {
    }

    public record OutcomeRequest(@NotBlank @Size(max = 2000) String notes) {
    }

    /** The application fee: PAID with an amount and method, or WAIVED. {@code paidOn} is the day it was settled. */
    public record FeeRequest(
            @NotNull(message = "Choose paid or waived.") FeeStatus status,
            @Min(value = 1, message = "Enter the amount paid.") @Max(value = MAX_FEE_PAISE,
                    message = "That amount is too large.") Long amountPaise,
            PaymentMethod method,
            @Size(max = 100) String reference,
            @NotNull(message = "Enter the date.") LocalDate paidOn,
            @Size(max = 2000) String note) {
    }

    public record OfferRequest(
            @NotNull(message = "Enter the offer date.") LocalDate offeredOn,
            LocalDate validUntil) {
    }

    /**
     * Turns an offered application into a student in a section of the applied class and year. The admission number
     * follows the students module's rules; the child's gender must be known (from the application or given here).
     */
    public record AdmitRequest(
            @NotNull(message = "Pick a section.") UUID sectionId,
            @NotBlank @Size(max = 30) String admissionNo,
            @Min(1) @Max(999) Integer rollNo,
            LocalDate admissionDate,
            Gender gender) {
    }

    /**
     * What a family sends from a school's public enquiry page. {@code website} is a honeypot that people never see:
     * a form that fills it in was sent by a bot and is quietly dropped.
     */
    public record PublicEnquiry(
            @NotBlank @Size(max = 200) String parentName,
            @NotNull(message = "Choose your relation to the child.") GuardianRelation relation,
            @NotBlank @Size(max = 20) @Pattern(regexp = Phones.INPUT_PATTERN, message = Phones.MESSAGE) String mobile,
            @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String childFirstName,
            @Size(max = 100) String childLastName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotBlank(message = "Pick a class.") @Size(max = 40) String className,
            @NotNull(message = "Pick an academic year.") YearChoice academicYear,
            @Size(max = 1000) String message,
            @NotNull(message = CONSENT_MESSAGE) @AssertTrue(message = CONSENT_MESSAGE) Boolean consent,
            @Size(max = 40) String consentVersion,
            @Size(max = 200) String website) {

        @Override
        public String toString() {
            // Never let a parent's contact details reach a log line.
            return "PublicEnquiry[className=" + className + ", academicYear=" + academicYear + "]";
        }
    }
}
