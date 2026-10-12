package com.akshara.certificates;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of the certificates API (docs/api/phase-2-certificates.md). The demo data uses the same records. */
public final class CertificateForms {

    /** The most placeholder values a certificate carries. */
    public static final int MAX_FIELDS = 60;
    static final String BLOOD_GROUP_PATTERN = "^$|^(A|B|AB|O)[+-]$";
    static final String PHONE_PATTERN = "^$|^(\\+91|91|0)?[\\s-]*[6-9](?:[\\s-]*[0-9]){9}$";

    private CertificateForms() {
    }

    /** The printed header, the signatory and the module's two settings. Every text may be empty. */
    public record SettingsForm(
            @Size(max = 200) String headerName,
            @Size(max = 500) String headerAddress,
            @Size(max = 60) String affiliationNo,
            @Size(max = 12) String logoText,
            @Size(max = 200) String signatoryName,
            @Size(max = 100) String signatoryDesignation,
            @NotNull Boolean tcDuesCheck,
            @NotNull @Min(1) @Max(60) Integer cardValidMonths,
            @NotNull @Min(0) @Max(10_000_000) Long lostCardFeePaise) {
    }

    /** A certificate type's wording. {@code serialPrefix}: 1–8 capital letters, digits or hyphens. */
    public record TemplateForm(
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 10_000) String body,
            @NotBlank @Pattern(regexp = "^[A-Z0-9-]{1,8}$",
                    message = "Use 1 to 8 capital letters, digits or hyphens for the prefix.") String serialPrefix) {
    }

    /**
     * The office prepares a certificate: the type, whom it is for, why, and (optionally) placeholder values that
     * replace the prefilled ones. {@code from} and {@code to} bound a fee certificate's period.
     */
    public record PrepareForm(
            @NotNull CertificateType type,
            UUID studentId,
            UUID staffUserId,
            @Size(max = 300) String purpose,
            LocalDate from,
            LocalDate to,
            @Size(max = MAX_FIELDS) Map<@NotBlank @Size(max = 40) String, @Size(max = 2000) String> fields) {
    }

    /** Edits to a certificate that is not yet signed: the title, the purpose and the placeholder values. */
    public record EditForm(
            @NotBlank @Size(max = 120) String title,
            @Size(max = 300) String purpose,
            @NotNull @Size(max = MAX_FIELDS) Map<@NotBlank @Size(max = 40) String, @Size(max = 2000) String> fields) {
    }

    public record ReasonForm(@NotBlank @Size(max = 500) String reason) {
    }

    /** A parent asks for a bonafide or fee certificate for one of their children. */
    public record FamilyRequestForm(
            @NotNull UUID studentId,
            @NotNull CertificateType type,
            @NotBlank @Size(max = 300) String purpose,
            LocalDate from,
            LocalDate to) {
    }

    /**
     * An ID card for a student or a member of staff. The blood group and emergency number are taken from the
     * student record (blood group) and the primary guardian (number) when left empty; staff give both.
     */
    public record CardForm(
            @NotNull SubjectType subjectType,
            UUID studentId,
            UUID staffUserId,
            @Pattern(regexp = BLOOD_GROUP_PATTERN, message = "Pick a blood group from the list.") String bloodGroup,
            @Pattern(regexp = PHONE_PATTERN, message = "Enter a 10-digit Indian mobile number.")
            String emergencyPhone,
            LocalDate validUntil) {
    }

    /** Cards for everyone in a section (students) or on the staff who has no card in use. */
    public record CardBatchForm(UUID sectionId, Boolean staff, LocalDate validUntil) {
    }

    /** Replaces a card: the reason (lost, damaged, details changed) and, for a lost card, the fee noted. */
    public record ReissueForm(
            @NotBlank @Size(max = 300) String reason,
            @Min(0) @Max(10_000_000) Long lostFeePaise,
            @Size(max = 300) String feeNote,
            @Pattern(regexp = BLOOD_GROUP_PATTERN, message = "Pick a blood group from the list.") String bloodGroup,
            @Pattern(regexp = PHONE_PATTERN, message = "Enter a 10-digit Indian mobile number.")
            String emergencyPhone) {
    }
}
