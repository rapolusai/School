package com.akshara.accounts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request bodies of the accounts API (docs/api/phase-2-accounts.md). The demo data uses the same records. */
public final class AccountsForms {

    static final String CODE = "^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$";
    static final String CODE_MESSAGE = "Use up to 20 letters, digits, _ and -.";
    static final String GSTIN = "^[0-9]{2}[A-Za-z]{5}[0-9]{4}[A-Za-z][1-9A-Za-z][Zz][0-9A-Za-z]$";
    static final String GSTIN_MESSAGE = "A GSTIN is 15 characters, like 36ABCDE1234F1Z5.";
    static final String PAN = "^[A-Za-z]{5}[0-9]{4}[A-Za-z]$";
    static final String PAN_MESSAGE = "A PAN is 5 letters, 4 digits and a letter, like ABCDE1234F.";
    static final String PHONE = "^\\+?[0-9]{8,15}$";
    static final String ACCOUNT = "^[0-9]{6,18}$";
    static final String IFSC = "^[A-Za-z]{4}0[A-Za-z0-9]{6}$";
    static final String IFSC_MESSAGE = "An IFSC is 11 characters, like SBIN0001234.";
    /** An amount is at most ₹10 crore. */
    static final long MAX_PAISE = 10_000_000_000L;

    private AccountsForms() {
    }

    public record SettingsForm(
            @NotNull BudgetMode budgetMode,
            @NotNull Boolean donation80gRegistered,
            @Size(max = 60) String donation80gNumber,
            @NotNull @PositiveOrZero @Max(MAX_PAISE) Long pettyCashFloatPaise) {
    }

    public record ApproverInput(
            @NotNull UUID userId,
            @Positive @Max(MAX_PAISE) Long limitPaise) {
    }

    /** The whole list of approvers with limits; it replaces the previous one. */
    public record ApproversForm(@NotNull @Size(max = 20) List<@NotNull @Valid ApproverInput> approvers) {
    }

    public record HeadForm(
            @NotBlank @Pattern(regexp = CODE, message = CODE_MESSAGE) String code,
            @NotBlank @Size(max = 80) String name,
            @NotNull HeadKind kind,
            UUID parentId,
            @NotNull Boolean gstApplicable,
            @NotNull Boolean active,
            @NotNull @Min(0) @Max(999) Integer displayOrder) {
    }

    public record CostCentreForm(
            @NotBlank @Pattern(regexp = CODE, message = CODE_MESSAGE) String code,
            @NotBlank @Size(max = 80) String name,
            @NotNull Boolean active) {
    }

    public record VendorForm(
            @NotBlank @Size(max = 120) String name,
            @Pattern(regexp = GSTIN, message = GSTIN_MESSAGE) String gstin,
            @Pattern(regexp = PAN, message = PAN_MESSAGE) String pan,
            @Pattern(regexp = PHONE, message = "Enter a phone number of 8 to 15 digits.") String phone,
            @Size(max = 200) String email,
            @Size(max = 300) String address,
            @Pattern(regexp = ACCOUNT, message = "An account number has 6 to 18 digits.") String bankAccountNumber,
            @Pattern(regexp = IFSC, message = IFSC_MESSAGE) String bankIfsc,
            @Pattern(regexp = "^(194C|194J|194H|194I|194Q)$", message = "Pick a TDS section.") String tdsSection,
            @DecimalMin("0") @DecimalMax("30") BigDecimal tdsRatePercent,
            @NotNull Boolean active) {
    }

    public record BankAccountForm(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 100) String bankName,
            @NotBlank @Pattern(regexp = ACCOUNT, message = "An account number has 6 to 18 digits.") String accountNumber,
            @NotBlank @Pattern(regexp = IFSC, message = IFSC_MESSAGE) String ifsc,
            @NotNull Boolean active) {
    }

    public record OpeningBalanceInput(UUID bankAccountId, @NotNull @Max(MAX_PAISE) @Min(-MAX_PAISE) Long amountPaise) {
    }

    /** Opening balances of a financial year: cash (bankAccountId null) and each bank account. */
    public record OpeningBalancesForm(@NotNull @Size(max = 30) List<@NotNull @Valid OpeningBalanceInput> balances) {
    }

    /** An expense request. GST is given as an amount (the bill says it); TDS follows the vendor unless overridden. */
    public record ExpenseForm(
            @NotNull UUID headId,
            UUID costCentreId,
            UUID vendorId,
            @Size(max = 120) String vendorName,
            @NotNull LocalDate expenseDate,
            @NotNull @Positive @Max(MAX_PAISE) Long amountPaise,
            @PositiveOrZero @Max(MAX_PAISE) Long gstPaise,
            @NotNull Boolean deductTds,
            @NotBlank @Size(max = 500) String description,
            @Size(max = 60) String billNumber) {
    }

    public record RejectForm(@NotBlank @Size(max = 500) String reason) {
    }

    public record ApproveForm(@Size(max = 500) String note) {
    }

    public record PaidForm(
            @NotNull LocalDate paidOn,
            @NotNull PayMode mode,
            UUID bankAccountId,
            @Size(max = 100) String reference) {
    }

    public record RecurringForm(
            @NotBlank @Size(max = 120) String name,
            @NotNull UUID headId,
            UUID costCentreId,
            UUID vendorId,
            @NotNull @Positive @Max(MAX_PAISE) Long amountPaise,
            @PositiveOrZero @Max(MAX_PAISE) Long gstPaise,
            @NotNull @Min(1) @Max(28) Integer dayOfMonth,
            @Size(max = 500) String description,
            @NotNull Boolean active) {
    }

    public record GenerateForm(@NotBlank @Pattern(regexp = "^[0-9]{4}-(0[1-9]|1[0-2])$",
            message = "Give the month as YYYY-MM, for example 2026-09.") String month) {
    }

    public record PettyCashForm(
            @NotNull LocalDate entryDate,
            @NotNull PettyKind kind,
            @NotNull @Positive @Max(MAX_PAISE) Long amountPaise,
            UUID headId,
            @NotBlank @Size(max = 300) String description) {
    }

    public record IncomeForm(
            @NotNull IncomeKind kind,
            @NotNull UUID headId,
            @NotNull LocalDate receivedOn,
            @NotNull @Positive @Max(MAX_PAISE) Long amountPaise,
            @NotBlank @Size(max = 200) String payerName,
            @Pattern(regexp = PAN, message = PAN_MESSAGE) String payerPan,
            @Size(max = 300) String payerAddress,
            @NotNull PayMode mode,
            UUID bankAccountId,
            @Size(max = 100) String reference,
            @Size(max = 500) String description) {
    }

    public record CancelForm(@NotBlank @Size(max = 500) String reason) {
    }

    public record BudgetForm(
            @NotNull UUID headId,
            @NotNull @PositiveOrZero @Max(MAX_PAISE) Long annualPaise,
            @NotNull BudgetSpread spread,
            @Size(min = 12, max = 12) List<@NotNull @PositiveOrZero @Max(MAX_PAISE) Long> months) {
    }

    /** A statement line as previewed, with the accountant's final choice of what it clears. */
    public record StatementLineInput(
            @NotNull LocalDate date,
            @NotBlank @Size(max = 300) String description,
            @Size(max = 100) String reference,
            @NotNull @PositiveOrZero @Max(MAX_PAISE) Long debitPaise,
            @NotNull @PositiveOrZero @Max(MAX_PAISE) Long creditPaise,
            UUID expenseId,
            UUID incomeId) {
    }

    public record StatementImportForm(
            @NotNull LocalDate statementDate,
            @NotNull @Size(max = 2000) List<@NotNull @Valid StatementLineInput> lines) {
    }

    /** Ticks items as cleared against a statement date without a statement file. */
    public record ClearForm(
            @NotNull LocalDate statementDate,
            @Size(max = 500) List<@NotNull UUID> expenseIds,
            @Size(max = 500) List<@NotNull UUID> incomeIds) {
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
