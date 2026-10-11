package com.akshara.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of the payroll API (docs/api/phase-2-payroll.md). Money is in paise, months are "YYYY-MM". Bank
 * account numbers and PAN are masked in every view except {@link PayProfileFull}, which only payroll managers get and
 * which is audited.
 */
public final class PayrollViews {

    private PayrollViews() {
    }

    // ------------------------------------------------------------------ masking

    /** "XXXXXX234F": a PAN with only its last four characters. */
    static String maskPan(String pan) {
        return pan == null ? null : "XXXXXX" + pan.substring(pan.length() - 4);
    }

    /** "XXXXXXXX9012": an account number with only its last four digits. */
    static String maskAccount(String account) {
        return account == null ? null : "X".repeat(Math.max(0, account.length() - 4)) + last4(account);
    }

    static String last4(String account) {
        return account == null ? null : account.substring(Math.max(0, account.length() - 4));
    }

    // ------------------------------------------------------------------ settings and statutory data

    public record RateView(LocalDate effectiveFrom, BigDecimal pfEmployeePercent, BigDecimal pfEmployerPercent,
            long pfWageCeilingPaise, BigDecimal epsPercent, long epsMaxPaise, BigDecimal edliPercent,
            long edliMaxPaise, BigDecimal pfAdminPercent, long pfAdminMinPaise, BigDecimal esiEmployeePercent,
            BigDecimal esiEmployerPercent, long esiWageCeilingPaise, String note) {

        static RateView of(StatutoryRate r) {
            return new RateView(r.getEffectiveFrom(), r.getPfEmployeePercent(), r.getPfEmployerPercent(),
                    r.getPfWageCeilingPaise(), r.getEpsPercent(), r.getEpsMaxPaise(), r.getEdliPercent(),
                    r.getEdliMaxPaise(), r.getPfAdminPercent(), r.getPfAdminMinPaise(), r.getEsiEmployeePercent(),
                    r.getEsiEmployerPercent(), r.getEsiWageCeilingPaise(), r.getNote());
        }
    }

    public record TaxSlabView(long fromPaise, Long toPaise, BigDecimal ratePercent) {
    }

    public record TaxRuleView(String financialYear, LocalDate startsOn, long standardDeductionPaise,
            long rebateIncomeLimitPaise, long rebateMaxPaise, BigDecimal cessPercent, List<TaxSlabView> slabs,
            String note) {
    }

    public record PtSlabView(PtAppliesTo appliesTo, long fromPaise, Long toPaise, long amountPaise,
            List<Integer> months) {

        static PtSlabView of(PtSlabRow r) {
            return new PtSlabView(r.getAppliesTo(), r.getFromPaise(), r.getToPaise(), r.getAmountPaise(),
                    r.monthSet().stream().sorted().toList());
        }
    }

    /** A state's professional tax slabs; {@code custom} when they are the school's own. */
    public record PtSlabsView(String state, boolean custom, LocalDate effectiveFrom, List<PtSlabView> slabs) {
    }

    /**
     * The statutory figures for the current month: the PF and ESI rates in force (and every rate row), the income
     * tax rules used for the current financial year ({@code taxRulesForThisYear} false when they are an earlier
     * year's), and the states with professional tax slabs.
     */
    public record StatutoryView(String month, RateView rate, List<RateView> rates, String financialYear,
            TaxRuleView taxRule, boolean taxRulesForThisYear, List<TaxRuleView> taxRules, List<String> ptStates,
            PtSlabsView ptSlabs) {
    }

    public record SettingsView(boolean saved, int payDay, ProrationBasis prorationBasis, boolean pfEnabled,
            boolean pfOnFullWages, boolean esiEnabled, boolean ptEnabled, String ptState, String pfEstablishmentCode,
            String esiEmployerCode, String ptRegistration, String tan, String bankName, String bankBranch,
            String bankAccountNumber, String bankIfsc, Instant updatedAt, StatutoryView statutory) {
    }

    // ------------------------------------------------------------------ components

    public record ComponentView(UUID id, String code, String name, ComponentKind kind, boolean pfWage,
            boolean esiWage, boolean taxable, boolean prorated, int displayOrder, boolean active, boolean inUse) {
    }

    // ------------------------------------------------------------------ staff pay details and structures

    public record PersonRef(UUID userId, String name, String employeeCode) {
    }

    /** One member of staff with their current structure (from the month given) and what is missing. */
    public record PayStaffRow(UUID userId, String name, String employeeCode, String designation, UUID departmentId,
            String departmentName, boolean active, boolean profileComplete, LocalDate dateOfJoining,
            LocalDate dateOfLeaving, LocalDate structureFrom, long grossPaise, long ctcPaise, boolean hasBank,
            String bankLast4, String panMasked, boolean hasPan, boolean hasUan, boolean hasEsiNumber) {
    }

    /** Everyone on the staff for payroll; {@code needStructure} counts active staff without a salary structure. */
    public record PayStaffList(String month, List<PayStaffRow> staff, int needStructure, int needBankDetails) {
    }

    /** Pay details with the account number and PAN masked. {@code saved} is false until first saved. */
    public record PayProfileView(boolean saved, String bankAccountMasked, String bankLast4, String bankIfsc,
            String accountHolderName, String panMasked, String uan, String esiNumber, boolean pfMember,
            boolean ptWomenSlab, Instant updatedAt) {
    }

    /** Pay details in full, for payroll managers only (every view is audited). */
    public record PayProfileFull(String bankAccountNumber, String bankIfsc, String accountHolderName, String pan,
            String uan, String esiNumber, boolean pfMember, boolean ptWomenSlab) {
    }

    public record StructureLineView(UUID componentId, String code, String name, ComponentKind kind,
            long monthlyPaise) {
    }

    /**
     * A salary structure with its monthly gross, the employer's contributions and the CTC (gross plus employer
     * contributions) as worked out with today's rates and settings for a full month. {@code current} for the
     * structure in force this month; {@code locked} once an approved run used it (it can then no longer be deleted).
     */
    public record StructureView(UUID id, String effectiveFrom, String note, String createdByName, Instant createdAt,
            List<StructureLineView> lines, long grossPaise, long fixedDeductionsPaise, long employerPaise,
            long ctcPaise, boolean current, boolean locked) {
    }

    public record PayslipRef(UUID id, String month, RunStatus status, long grossPaise, long netPaise) {
    }

    public record PayStaffDetail(UUID userId, String name, String email, String employeeCode, String designation,
            String departmentName, boolean active, boolean profileComplete, LocalDate dateOfJoining,
            LocalDate dateOfLeaving, PayProfileView payProfile, List<StructureView> structures,
            List<PayslipRef> payslips) {
    }

    // ------------------------------------------------------------------ runs

    public record RunTotals(int staffCount, long grossPaise, long deductionsPaise, long netPaise, long employerPaise,
            long pfEmployeePaise, long pfEmployerPaise, long pfAdminPaise, long edliPaise, long esiEmployeePaise,
            long esiEmployerPaise, long ptPaise, long tdsPaise) {

        static RunTotals of(PayrollRun.Totals t) {
            return new RunTotals(t.staffCount(), t.gross(), t.deductions(), t.net(), t.employer(), t.pfEmployee(),
                    t.pfEmployer(), t.pfAdmin(), t.edli(), t.esiEmployee(), t.esiEmployer(), t.pt(), t.tds());
        }
    }

    public record RunRow(UUID id, String month, RunStatus status, RunTotals totals, Instant calculatedAt,
            String approvedByName, Instant approvedAt, LocalDate paidOn) {
    }

    /** The school's runs, newest first, and the month a new run would most likely be for. */
    public record RunList(List<RunRow> runs, String suggestedMonth, int needStructure) {
    }

    /** One person's figures in the run review. */
    public record PayslipRow(UUID id, UUID userId, String name, String employeeCode, String departmentName,
            BigDecimal payableDays, BigDecimal lopDays, long grossPaise, long pfEmployeePaise, long esiEmployeePaise,
            long ptPaise, long tdsPaise, boolean tdsOverridden, String tdsOverrideReason, long tdsCalculatedPaise,
            long otherDeductionsPaise, long deductionsPaise, long netPaise, long employerPaise, boolean hasBank) {
    }

    public record AdjustmentView(UUID id, UUID userId, String name, AdjustmentKind kind, Long amountPaise,
            BigDecimal days, String reason, String createdByName, Instant createdAt) {
    }

    /**
     * Something to look at before approving. Codes: NO_STRUCTURE, NO_BANK, NO_PAN, NO_UAN, NO_ESI_NUMBER,
     * PROFILE_INCOMPLETE (per person), TAX_RULES_EARLIER_YEAR, NO_TAX_RULES, NO_PT_STATE, NO_PT_SLABS (for the run).
     */
    public record Warning(String code, UUID userId, String name, String detail) {
    }

    public record RunDetail(UUID id, String month, RunStatus status, ProrationBasis prorationBasis, int daysInMonth,
            int workingDays, RunTotals totals, Instant calculatedAt, String createdByName, String approvedByName,
            Instant approvedAt, LocalDate paidOn, String paymentReference, String paidByName,
            String taxFinancialYear, List<PayslipRow> payslips, List<AdjustmentView> adjustments,
            List<Warning> warnings, long version) {
    }

    // ------------------------------------------------------------------ payslips

    public record SchoolView(String name, String address, String city, String phone, String email,
            String pfEstablishmentCode, String esiEmployerCode, String tan) {
    }

    public record PayslipPerson(UUID userId, String name, String employeeCode, String designation,
            String departmentName, LocalDate dateOfJoining, LocalDate dateOfLeaving, String panMasked,
            String bankLast4, String bankIfsc, String uan, String esiNumber) {
    }

    public record LineView(String code, String name, Long fullPaise, long amountPaise) {
    }

    /** One payslip as printed. {@code netInWords} reads like a receipt: "Rupees ... Only". */
    public record PayslipView(UUID id, UUID runId, String month, RunStatus status, LocalDate paidOn,
            SchoolView school, PayslipPerson person, ProrationBasis prorationBasis, BigDecimal basisDays,
            BigDecimal daysOnRoll, BigDecimal lopDays, BigDecimal payableDays, List<LineView> earnings,
            List<LineView> deductions, List<LineView> employer, long grossPaise, long deductionsPaise,
            long netPaise, long employerPaise, String netInWords, long taxableYearProjectedPaise,
            long taxYearPaise) {
    }

    /** The signed-in person's payslips, newest month first (approved and paid months only). */
    public record MyPayslipRow(UUID id, String month, RunStatus status, LocalDate paidOn, long grossPaise,
            long deductionsPaise, long netPaise) {
    }

    // ------------------------------------------------------------------ exports and summaries

    public record BankAdviceRow(String name, String employeeCode, String accountMasked, String ifsc,
            long amountPaise) {
    }

    /** The covering letter for the bank file: totals in figures and words, staff accounts masked. */
    public record BankAdvice(String month, RunStatus status, LocalDate paidOn, SchoolView school, String bankName,
            String bankBranch, String debitAccountNumber, String debitIfsc, int count, long totalPaise,
            String totalInWords, List<BankAdviceRow> rows, List<PersonRef> withoutBank) {
    }

    public record PtSlabTotal(long amountPaise, int count, long totalPaise) {
    }

    public record PtRow(String name, String employeeCode, long grossPaise, long ptPaise) {
    }

    public record PtSummary(String month, String state, List<PtSlabTotal> slabs, List<PtRow> rows,
            long totalPaise) {
    }

    public record TdsRow(String name, String employeeCode, String panMasked, long taxablePaise,
            long projectedIncomePaise, long annualTaxPaise, long calculatedPaise, long tdsPaise,
            boolean overridden) {
    }

    public record TdsSummary(String month, String financialYear, List<TdsRow> rows, long totalPaise) {
    }

    /** A file to download. */
    public record Download(String fileName, String contentType, byte[] content) {
    }

    // ------------------------------------------------------------------ reports

    public record RegisterRow(UUID userId, String name, String employeeCode, String designation,
            BigDecimal payableDays, BigDecimal lopDays, long grossPaise, long pfEmployeePaise, long esiEmployeePaise,
            long ptPaise, long tdsPaise, long otherDeductionsPaise, long deductionsPaise, long netPaise,
            long employerPaise) {
    }

    public record RegisterTotals(int count, long grossPaise, long pfEmployeePaise, long esiEmployeePaise,
            long ptPaise, long tdsPaise, long otherDeductionsPaise, long deductionsPaise, long netPaise,
            long employerPaise) {
    }

    public record RegisterDepartment(UUID departmentId, String departmentName, List<RegisterRow> rows,
            RegisterTotals totals) {
    }

    /** The payroll register of a month, by department. {@code runId} null when the month has no run. */
    public record Register(String month, UUID runId, RunStatus status, UUID departmentId,
            List<RegisterDepartment> departments, RegisterTotals totals) {
    }

    public record YtdRow(UUID userId, String name, String employeeCode, String departmentName, int months,
            long grossPaise, long pfEmployeePaise, long esiEmployeePaise, long ptPaise, long tdsPaise,
            long otherDeductionsPaise, long netPaise, long employerPaise) {
    }

    /** Year to date per member of staff, from the approved and paid months of a financial year. */
    public record YearToDate(String financialYear, List<String> months, List<YtdRow> rows, YtdRow totals) {
    }

    public record StatutoryMonth(String month, RunStatus status, int staffCount, long pfEmployeePaise,
            long epfEmployerPaise, long epsPaise, long edliPaise, long pfAdminPaise, long esiEmployeePaise,
            long esiEmployerPaise, long ptPaise, long tdsPaise, long totalPaise) {
    }

    /** PF, ESI, professional tax and TDS by month for a financial year (approved and paid months). */
    public record StatutorySummary(String financialYear, List<StatutoryMonth> months, StatutoryMonth totals) {
    }
}
