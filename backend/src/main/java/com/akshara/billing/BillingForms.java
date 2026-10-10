package com.akshara.billing;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.akshara.platform.Plan;
import com.akshara.platform.TenantStatus;

/** Request bodies of the billing API (docs/api/phase-1-billing.md). Amounts are in paise. */
public final class BillingForms {

    public static final int MAX_STUDENTS = 100_000;

    /** ₹10 crore: far more than any one invoice. */
    public static final long MAX_AMOUNT_PAISE = 10_000_000_000L;

    private BillingForms() {
    }

    /**
     * Converts a trial (or a school added without one) to a paid subscription and issues its first invoice.
     * {@code periodStart} defaults to today in India.
     */
    public record StartForm(
            @NotNull Plan plan,
            @NotNull BillingCycle billingCycle,
            @NotNull @Min(1) @Max(MAX_STUDENTS) Integer billedStudents,
            LocalDate periodStart) {
    }

    /**
     * Changes a school's plan. For a paying school {@code billingCycle} and {@code billedStudents} are required too, and
     * every change applies from its next invoice (no proration). A school on trial changes only its plan.
     */
    public record ChangeForm(
            @NotNull Plan plan,
            BillingCycle billingCycle,
            @Min(1) @Max(MAX_STUDENTS) Integer billedStudents) {
    }

    /**
     * The school as the buyer on its invoices. {@code legalName} defaults to the school's name. {@code stateCode} is
     * the two-digit GST state code (the place of supply); {@code gstin} is optional. Empty strings clear a field.
     */
    public record DetailsForm(
            @Size(max = 200) String legalName,
            @Size(max = 500) String address,
            @NotBlank(message = "Choose the state.") @Pattern(regexp = "^[0-9]{2}$", message = "Choose the state.")
            String stateCode,
            @Size(max = 15, message = "A GSTIN has 15 characters.") String gstin) {
    }

    /** A payment that reached Akshara's account. {@code amountPaise} defaults to what is still due. */
    public record PaymentForm(
            @Min(1) @Max(MAX_AMOUNT_PAISE) Long amountPaise,
            @NotNull PaymentMode mode,
            @NotBlank(message = "Enter the bank or UPI reference.") @Size(max = 100) String reference,
            @NotNull(message = "Enter the date the money arrived.") LocalDate paidOn) {
    }

    public record ReasonForm(@NotBlank(message = "Give a reason.") @Size(max = 500) String reason) {
    }

    /** A manual status change: ACTIVE or PAST_DUE. Suspension has its own endpoints. */
    public record StatusForm(@NotNull TenantStatus status) {
    }
}
