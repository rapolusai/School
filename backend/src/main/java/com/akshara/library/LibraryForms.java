package com.akshara.library;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of the library API (docs/api/phase-2-library.md). The demo data uses the same records. */
public final class LibraryForms {

    /** The most copies one request (or one import row) adds. */
    public static final int MAX_COPIES_AT_ONCE = 100;
    /** The highest price or charge a copy can have: ₹10,00,000. */
    public static final long MAX_PRICE_PAISE = 100_000_000L;
    /** An accession number as typed or scanned: letters, digits, / and -. */
    public static final String ACCESSION_PATTERN = "^[A-Za-z0-9/-]{1,30}$";
    public static final String ACCESSION_MESSAGE = "Use up to 30 letters, digits, / and - for the accession number.";

    private LibraryForms() {
    }

    /**
     * A title. {@code isbn} may be an ISBN-10 or ISBN-13 with spaces or hyphens, or empty for books without one. When
     * adding a title, {@code copies} copies (1 when not given, at most 100) are added with the price, source and date.
     * When editing, the copy fields are ignored.
     */
    public record TitleForm(
            @Size(max = 20) String isbn,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 300) String subtitle,
            @Size(max = 500) String authors,
            @Size(max = 200) String publisher,
            @Min(1400) @Max(2100) Integer publicationYear,
            @Size(max = 60) String edition,
            @Size(max = 40) String language,
            @Size(max = 100) String subject,
            @Size(max = 40) String callNumber,
            @Size(max = 60) String shelfLocation,
            @Min(0) @Max(MAX_COPIES_AT_ONCE) Integer copies,
            @Min(0) @Max(MAX_PRICE_PAISE) Long pricePaise,
            CopySource source,
            LocalDate acquiredOn) {
    }

    public record CopiesForm(
            @NotNull @Min(1) @Max(MAX_COPIES_AT_ONCE) Integer count,
            @Min(0) @Max(MAX_PRICE_PAISE) Long pricePaise,
            CopySource source,
            LocalDate acquiredOn) {
    }

    /**
     * A copy's details and where it is. The status can be set to AVAILABLE, DAMAGED, LOST or WITHDRAWN while the copy
     * is not on loan or kept for a hold; ISSUED and RESERVED only come from the desk.
     */
    public record CopyForm(
            @Min(0) @Max(MAX_PRICE_PAISE) Long pricePaise,
            @NotNull CopySource source,
            LocalDate acquiredOn,
            @NotNull CopyStatus status,
            @Size(max = 300) String statusNote) {
    }

    /**
     * Lends a copy to a member. {@code issuedOn} is today unless the loan is being recorded later (a book lent while
     * the system was unavailable): then the day it was lent, at most 60 days back.
     */
    public record IssueForm(
            @NotBlank @Pattern(regexp = ACCESSION_PATTERN, message = ACCESSION_MESSAGE) String accessionNo,
            @NotNull MemberType memberType,
            @NotNull UUID memberId,
            LocalDate issuedOn) {
    }

    /** Takes a copy back. A damaged copy is set aside, with an optional charge to the member. */
    public record ReturnForm(
            @NotBlank @Pattern(regexp = ACCESSION_PATTERN, message = ACCESSION_MESSAGE) String accessionNo,
            Boolean damaged,
            @Min(0) @Max(MAX_PRICE_PAISE) Long chargePaise,
            @Size(max = 300) String note) {
    }

    /** The member reports the copy lost. The charge defaults to the copy's price. */
    public record LostForm(
            @Min(0) @Max(MAX_PRICE_PAISE) Long chargePaise,
            @Size(max = 300) String note) {
    }

    public record HoldForm(
            @NotNull UUID titleId,
            @NotNull MemberType memberType,
            @NotNull UUID memberId) {
    }

    /** Fines of one member paid together, under one receipt. A cheque or bank transfer needs its reference. */
    public record CollectForm(
            @NotEmpty @Size(max = 50) List<@NotNull UUID> fineIds,
            @NotNull LibraryPaymentMode mode,
            @Size(max = 60) String reference) {
    }

    public record WaiveForm(@NotBlank @Size(max = 500) String reason) {
    }

    public record RuleForm(
            @NotNull @Min(1) @Max(20) Integer maxBooks,
            @NotNull @Min(1) @Max(180) Integer loanDays,
            @NotNull @Min(0) @Max(10) Integer maxRenewals,
            @NotNull @Min(0) @Max(100_000) Long finePerDayPaise,
            @NotNull @Min(0) @Max(10_000_000) Long maxFinePaise) {
    }

    /** {@code accessionPrefix}: up to 8 capital letters, digits, / or - (may be empty). */
    public record SettingsForm(
            @NotNull @Pattern(regexp = "^[A-Z0-9/-]{0,8}$",
                    message = "Use up to 8 capital letters, digits, / and - for the prefix.") String accessionPrefix,
            @NotNull @Min(1) @Max(14) Integer holdDays,
            @NotNull Boolean remindersEnabled) {
    }

    public record StockCheckForm(@Size(max = 200) String note) {
    }

    public record ScanForm(
            @NotBlank @Pattern(regexp = ACCESSION_PATTERN, message = ACCESSION_MESSAGE) String accessionNo) {
    }

    public record ImportRequest(@NotNull @Size(max = 2_000_000, message = "The file is too large.") String csv) {
    }
}
