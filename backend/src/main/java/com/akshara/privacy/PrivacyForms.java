package com.akshara.privacy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of the privacy API (docs/api/phase-1-privacy.md). The demo data uses the same records. */
public final class PrivacyForms {

    /** A school phone number: 8 to 15 digits with an optional +, spaces, brackets or hyphens (as on the profile). */
    static final String PHONE = "^\\+?[0-9][0-9 ()-]{6,18}[0-9]$";

    private PrivacyForms() {
    }

    public record OfficerForm(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 20) @Pattern(regexp = PHONE, message = "Enter a phone number with 8 to 15 digits.")
            String phone) {
    }

    /** A new version of the notice. {@code changeSummary} tells parents what changed (optional for the first). */
    public record NoticeForm(
            @NotBlank @Size(max = 20000) String bodyEn,
            @NotBlank @Size(max = 20000) String bodyHi,
            @Size(max = 500) String changeSummary) {
    }

    /** A signed paper consent form, entered by staff: essential processing plus the two optional purposes. */
    public record PaperConsentForm(
            @NotBlank @Size(max = 200) String givenByName,
            @NotNull LocalDate signedOn,
            @Size(max = 100) String paperReference,
            @NotNull Boolean photos,
            @NotNull Boolean whatsapp) {
    }

    /** A parent's choices for one child. */
    public record ConsentChoice(
            @NotNull UUID studentId,
            @NotNull Boolean photos,
            @NotNull Boolean whatsapp) {
    }

    /** A parent accepts the current notice: essential processing for every listed child, and the optional choices. */
    public record AcceptForm(
            @NotNull @Min(1) Integer noticeVersion,
            @NotNull @AssertTrue(message = "Accept the essential school records to continue.") Boolean acceptEssential,
            @NotEmpty @Size(max = 20) List<@NotNull @Valid ConsentChoice> choices) {
    }

    public record ConsentChange(@NotNull Boolean given) {
    }

    public record RequestForm(
            @NotNull RequestType type,
            @NotNull RequestSubject subject,
            UUID studentId,
            @Size(max = 2000) String details) {
    }

    public record ReplyForm(@NotBlank @Size(max = 2000) String body) {
    }

    public record AssignForm(@NotNull UUID assigneeId) {
    }

    /** The second confirmation of an erasure: the student's admission number, typed again. */
    public record EraseForm(@NotBlank @Size(max = 30) String confirmAdmissionNo) {
    }

    public record CloseForm(
            @NotNull RequestResolution resolution,
            @Size(max = 2000) String note) {
    }

    public static final int MAX_PAGE_SIZE = 100;

    /** Page size for the request queue and the consent list: 1 to 100. */
    static int pageSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
