package com.akshara.fees;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies of the fees API. Amounts are in paise. The demo data seeder uses the same records. */
public final class FeeForms {

    /** ₹1 crore: more than any single fee head or payment a school charges. */
    public static final long MAX_AMOUNT_PAISE = 1_000_000_000L;

    public static final int MAX_INSTALMENTS = 12;

    private FeeForms() {
    }

    public record HeadForm(
            @NotBlank @Size(max = 60) String name,
            @NotNull FeeHeadKind kind,
            Boolean oneTime,
            Boolean active) {

        /** Leaving out "oneTime" means false; leaving out "active" means true. */
        public HeadForm {
            oneTime = Boolean.TRUE.equals(oneTime);
            active = active == null || active;
        }
    }

    public record HeadAmount(
            @NotNull UUID headId,
            @NotNull @Min(0) @Max(MAX_AMOUNT_PAISE) Long amountPaise) {
    }

    /**
     * An instalment with its due date. {@code shares} says how much of each head falls in it; leave it out on every
     * instalment to split each head evenly (one-time heads go wholly into the first instalment).
     */
    public record InstalmentForm(
            @Size(max = 40) String label,
            @NotNull LocalDate dueDate,
            @Size(max = 30) List<@NotNull @Valid HeadAmount> shares) {
    }

    public record StructureForm(
            @NotNull UUID academicYearId,
            @NotNull UUID classId,
            @NotEmpty(message = "Add at least one fee head.") @Size(max = 30) List<@NotNull @Valid HeadAmount> heads,
            @NotEmpty(message = "Add at least one instalment.") @Size(max = MAX_INSTALMENTS,
                    message = "Use at most 12 instalments.") List<@NotNull @Valid InstalmentForm> instalments) {
    }

    public record LateFeeRuleForm(
            @NotNull LateFeeMode mode,
            @Min(0) @Max(365) Integer graceDays,
            @Min(0) @Max(MAX_AMOUNT_PAISE) Long flatPaise,
            @Min(0) @Max(MAX_AMOUNT_PAISE) Long perDayPaise,
            @Min(0) @Max(MAX_AMOUNT_PAISE) Long capPaise) {
    }

    /**
     * A concession for a student in an academic year (the current year when left out). PERCENT takes {@code percent}
     * (up to two decimals), FIXED takes {@code fixedPaise}. RTE ignores mode, value and heads: it is always 100% of the
     * school's tuition heads.
     */
    public record ConcessionForm(
            @NotNull UUID studentId,
            UUID academicYearId,
            @NotNull ConcessionType type,
            ConcessionMode mode,
            @DecimalMin(value = "0.01", message = "Use a percentage from 0.01 to 100.") @DecimalMax(value = "100",
                    message = "Use a percentage from 0.01 to 100.") @Digits(integer = 3, fraction = 2,
                            message = "Use at most two decimals.") BigDecimal percent,
            @Min(1) @Max(MAX_AMOUNT_PAISE) Long fixedPaise,
            @Size(max = 30) List<@NotNull UUID> headIds,
            @NotBlank @Size(max = 500) String reason) {
    }

    /** A reason for revoking, cancelling or waiving something. */
    public record ReasonForm(@NotBlank @Size(max = 500) String reason) {
    }

    /**
     * A payment taken at the counter. Without {@code instalmentIds} it pays the oldest dues first; with them, only
     * those instalments (oldest first). Late fees of overdue instalments are collected first unless
     * {@code includeLateFee} is false.
     */
    public record PaymentForm(
            @NotNull @Min(1) @Max(MAX_AMOUNT_PAISE) Long amountPaise,
            @NotNull PaymentMode mode,
            @Size(max = 20) String chequeNo,
            @Size(max = 100) String bankName,
            @Size(max = 100) String reference,
            @Size(max = MAX_INSTALMENTS * 4) List<@NotNull UUID> instalmentIds,
            Boolean includeLateFee,
            @Size(max = 200) String remarks) {

        public PaymentForm {
            includeLateFee = includeLateFee == null || includeLateFee;
        }
    }

    public record WaiverForm(@NotNull UUID instalmentId, @NotBlank @Size(max = 500) String reason) {
    }

    /** Instalments to pay online. The order is for their balance plus any late fee due today. */
    public record OrderForm(
            @NotEmpty(message = "Pick at least one instalment.") @Size(max = MAX_INSTALMENTS * 4)
            List<@NotNull UUID> instalmentIds) {
    }

    /** What the gateway's checkout hands back to the browser after a successful payment. */
    public record VerifyForm(
            @NotBlank @Size(max = 64) String gatewayPaymentId,
            @NotBlank @Size(max = 200) String signature) {
    }

    public record ReminderForm(@NotEmpty @Size(max = 2000) List<@NotNull UUID> studentIds) {
    }
}
