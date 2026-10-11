package com.akshara.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request bodies of the payroll API (docs/api/phase-2-payroll.md). The demo data uses the same records. */
public final class PayrollForms {

    /** "2026-09". */
    static final String MONTH = "^[0-9]{4}-(0[1-9]|1[0-2])$";
    static final String MONTH_MESSAGE = "Give the month as YYYY-MM, for example 2026-09.";
    static final String ACCOUNT = "^[0-9]{6,18}$";
    static final String IFSC = "^[A-Za-z]{4}0[A-Za-z0-9]{6}$";
    static final String PAN = "^[A-Za-z]{5}[0-9]{4}[A-Za-z]$";
    static final String TAN = "^[A-Za-z]{4}[0-9]{5}[A-Za-z]$";
    static final String CODE = "^[A-Za-z0-9/ -]{1,30}$";
    /** A monthly amount is at most ₹10 crore. */
    static final long MAX_PAISE = 10_000_000_000L;

    private PayrollForms() {
    }

    /** The school's payroll settings. Professional tax needs a state. */
    public record SettingsForm(
            @NotNull @Min(1) @Max(31) Integer payDay,
            @NotNull ProrationBasis prorationBasis,
            @NotNull Boolean pfEnabled,
            @NotNull Boolean pfOnFullWages,
            @NotNull Boolean esiEnabled,
            @NotNull Boolean ptEnabled,
            @Pattern(regexp = "^[A-Za-z]{2}$", message = "Pick a state.") String ptState,
            @Pattern(regexp = CODE, message = "Use letters, digits, / and - only.") String pfEstablishmentCode,
            @Pattern(regexp = CODE, message = "Use letters, digits, / and - only.") String esiEmployerCode,
            @Pattern(regexp = CODE, message = "Use letters, digits, / and - only.") String ptRegistration,
            @Pattern(regexp = TAN, message = "A TAN is 4 letters, 5 digits and a letter, like HYDA12345B.") String tan,
            @Size(max = 100) String bankName,
            @Size(max = 100) String bankBranch,
            @Pattern(regexp = ACCOUNT, message = "An account number has 6 to 18 digits.") String bankAccountNumber,
            @Pattern(regexp = IFSC, message = "An IFSC is 11 characters, like SBIN0001234.") String bankIfsc) {
    }

    public record PtSlabInput(
            @NotNull PtAppliesTo appliesTo,
            @NotNull @PositiveOrZero @Max(MAX_PAISE) Long fromPaise,
            @PositiveOrZero @Max(MAX_PAISE) Long toPaise,
            @NotNull @PositiveOrZero @Max(10_000_00L) Long amountPaise,
            @Size(max = 12) List<@NotNull @Min(1) @Max(12) Integer> months) {
    }

    /** A school's own professional tax slabs for a state; they replace the published ones for it. */
    public record PtSlabsForm(
            @NotNull LocalDate effectiveFrom,
            @NotEmpty @Size(max = 40) List<@NotNull @Valid PtSlabInput> slabs) {
    }

    /** The editable part of a salary component. */
    public record ComponentFields(
            @NotBlank @Size(max = 60) String name,
            @NotNull Boolean pfWage,
            @NotNull Boolean esiWage,
            @NotNull Boolean taxable,
            @NotNull Boolean prorated,
            @NotNull @Min(0) @Max(999) Integer displayOrder,
            @NotNull Boolean active) {
    }

    /** A new component: its code and kind never change afterwards. */
    public record NewComponent(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,19}$",
                    message = "Use up to 20 letters, digits and _ starting with a letter.") String code,
            @NotNull ComponentKind kind,
            @NotNull @Valid ComponentFields fields) {
    }

    /** What payroll keeps about a staff member. An account number and its IFSC go together. Never Aadhaar. */
    public record PayProfileForm(
            @Pattern(regexp = ACCOUNT, message = "An account number has 6 to 18 digits.") String bankAccountNumber,
            @Pattern(regexp = IFSC, message = "An IFSC is 11 characters, like SBIN0001234.") String bankIfsc,
            @Size(max = 200) String accountHolderName,
            @Pattern(regexp = PAN, message = "A PAN is 5 letters, 4 digits and a letter, like ABCDE1234F.") String pan,
            @Pattern(regexp = "^[0-9]{12}$", message = "A UAN has 12 digits.") String uan,
            @Pattern(regexp = "^[0-9]{10}$", message = "An ESI number has 10 digits.") String esiNumber,
            @NotNull Boolean pfMember,
            @NotNull Boolean ptWomenSlab) {
    }

    public record StructureLineInput(
            @NotNull UUID componentId,
            @NotNull @Positive @Max(MAX_PAISE) Long monthlyPaise) {
    }

    /** A salary structure from a month on; a revision is a new structure. */
    public record StructureForm(
            @NotBlank @Pattern(regexp = MONTH, message = MONTH_MESSAGE) String effectiveFrom,
            @Size(max = 500) String note,
            @NotEmpty @Size(max = 30) List<@NotNull @Valid StructureLineInput> lines) {
    }

    public record RunForm(@NotBlank @Pattern(regexp = MONTH, message = MONTH_MESSAGE) String month) {
    }

    /**
     * Arrears, a bonus or a deduction ({@code amountPaise}), or a loss-of-pay correction ({@code days}, positive adds
     * days, negative removes them, in whole or half days).
     */
    public record AdjustmentForm(
            @NotNull UUID userId,
            @NotNull AdjustmentKind kind,
            @Positive @Max(MAX_PAISE) Long amountPaise,
            BigDecimal days,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record TdsOverrideForm(
            @NotNull @PositiveOrZero @Max(MAX_PAISE) Long amountPaise,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record PaidForm(
            @NotNull LocalDate paidOn,
            @Size(max = 100) String reference) {
    }

    /** Trimmed, null when blank. */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return v.isEmpty() ? null : v;
    }

    /** Trimmed and upper case, null when blank. */
    static String upper(String value) {
        String v = clean(value);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }
}
