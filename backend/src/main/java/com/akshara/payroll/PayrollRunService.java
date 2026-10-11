package com.akshara.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.payroll.PayrollForms.AdjustmentForm;
import com.akshara.payroll.PayrollForms.PaidForm;
import com.akshara.payroll.PayrollForms.TdsOverrideForm;
import com.akshara.payroll.PayrollSettings.Values;
import com.akshara.payroll.PayrollViews.AdjustmentView;
import com.akshara.payroll.PayrollViews.PayslipRow;
import com.akshara.payroll.PayrollViews.RunDetail;
import com.akshara.payroll.PayrollViews.RunList;
import com.akshara.payroll.PayrollViews.RunRow;
import com.akshara.payroll.PayrollViews.RunTotals;
import com.akshara.payroll.PayrollViews.Warning;
import com.akshara.payroll.PayslipCalculator.Input;
import com.akshara.payroll.PayslipCalculator.Line;
import com.akshara.payroll.PayslipCalculator.Result;
import com.akshara.payroll.PayslipCalculator.YearToDate;
import com.akshara.payroll.StatutoryTables.TaxYear;
import com.akshara.payroll.StructureBook.Loaded;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffPayrollInputs;
import com.akshara.staff.StaffPayrollInputs.PayrollPerson;
import com.akshara.staff.WorkingDayCalendar;

/**
 * Monthly payroll runs. A run is created as a DRAFT for every member of staff on the roll that month with a salary
 * structure; it pulls approved loss-of-pay leave from the staff records and takes manual adjustments and TDS overrides,
 * each with a reason, and is recalculated whenever they change. Approving it (payroll.approve) locks it for good and
 * publishes {@link PayrollApproved}; it is then marked paid with the payment date. A correction to a locked month is
 * an adjustment in a later run.
 */
@Service
@Transactional
public class PayrollRunService {

    private final PayrollRunRepository runs;
    private final PayslipRepository payslips;
    private final PayslipLineRepository lines;
    private final PayrollAdjustmentRepository adjustments;
    private final StaffPayProfileRepository profiles;
    private final StructureBook book;
    private final StaffPayrollInputs staff;
    private final WorkingDayCalendar calendar;
    private final PayrollSettingsService settings;
    private final StatutoryTables statutory;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    PayrollRunService(PayrollRunRepository runs, PayslipRepository payslips, PayslipLineRepository lines,
            PayrollAdjustmentRepository adjustments, StaffPayProfileRepository profiles, StructureBook book,
            StaffPayrollInputs staff, WorkingDayCalendar calendar, PayrollSettingsService settings,
            StatutoryTables statutory, AuditService audit, ApplicationEventPublisher events) {
        this.runs = runs;
        this.payslips = payslips;
        this.lines = lines;
        this.adjustments = adjustments;
        this.profiles = profiles;
        this.book = book;
        this.staff = staff;
        this.calendar = calendar;
        this.settings = settings;
        this.statutory = statutory;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ list, create, detail

    @Transactional(readOnly = true)
    public RunList list() {
        TenantContext.require();
        List<PayrollRun> all = runs.findAllNewestFirst();
        YearMonth now = PayrollSettingsService.thisMonth();
        YearMonth suggested = all.isEmpty() ? now : all.getFirst().yearMonth().plusMonths(1);
        if (suggested.isAfter(now)) {
            suggested = now;
        }
        Map<UUID, List<Loaded>> structures = book.all();
        int needStructure = (int) staff.people().stream()
                .filter(p -> p.active() && !structures.containsKey(p.userId())).count();
        return new RunList(all.stream().map(PayrollRunService::row).toList(), suggested.toString(), needStructure);
    }

    /** A draft run for the month, calculated at once (409 when the month has a run, 400 for a future month). */
    public RunDetail create(String monthText, Actor actor) {
        TenantContext.require();
        YearMonth month = PayrollAccess.month(monthText, "month");
        if (month.isAfter(PayrollSettingsService.thisMonth())) {
            throw ApiException.badRequest("Payroll can be run up to the current month.", "month");
        }
        if (runs.findByMonth(month.atDay(1)).isPresent()) {
            throw ApiException.conflict("This month already has a payroll run.", "month");
        }
        Values v = settings.values();
        PayrollRun run;
        try {
            run = runs.saveAndFlush(new PayrollRun(month, v.prorationBasis(),
                    calendar.workingDays(month.atDay(1), month.atEndOfMonth()).size(), actor));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("This month already has a payroll run.", "month");
        }
        calculate(run);
        audit.record(actor, "payroll_run.created", "payroll_run", run.getId(), Map.of("month", month.toString(),
                "staffCount", run.totals().staffCount()));
        return detail(run.getId());
    }

    @Transactional(readOnly = true)
    public RunDetail detail(UUID id) {
        TenantContext.require();
        PayrollRun run = runs.findById(id).orElseThrow(() -> ApiException.notFound("Payroll run"));
        return detail(run);
    }

    /** Recalculates a draft from the staff records, structures, leave, adjustments and settings as they are now. */
    public RunDetail recalculate(UUID id, Actor actor) {
        TenantContext.require();
        PayrollRun run = draft(id);
        calculate(run);
        audit.record(actor, "payroll_run.recalculated", "payroll_run", run.getId(),
                Map.of("month", run.yearMonth().toString(), "staffCount", run.totals().staffCount()));
        return detail(run);
    }

    /** Deletes a draft run with its payslips and adjustments (409 once approved). */
    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        PayrollRun run = draft(id);
        String month = run.yearMonth().toString();
        runs.delete(run);
        runs.flush();
        audit.record(actor, "payroll_run.deleted", "payroll_run", id, Map.of("month", month));
    }

    // ------------------------------------------------------------------ adjustments and TDS overrides

    public RunDetail addAdjustment(UUID runId, AdjustmentForm form, Actor actor) {
        TenantContext.require();
        PayrollRun run = draft(runId);
        if (payslips.findByRunAndUser(runId, form.userId()).isEmpty()) {
            throw ApiException.badRequest("This person is not in this month's payroll.", "userId");
        }
        Long amount = null;
        BigDecimal days = null;
        if (form.kind().money()) {
            if (form.amountPaise() == null) {
                throw ApiException.badRequest("Enter the amount.", "amountPaise");
            }
            if (form.days() != null) {
                throw ApiException.badRequest("Days apply to loss-of-pay corrections only.", "days");
            }
            amount = form.amountPaise();
        } else {
            BigDecimal d = form.days();
            if (d == null || d.signum() == 0 || d.abs().compareTo(BigDecimal.valueOf(31)) > 0
                    || d.multiply(BigDecimal.TWO).stripTrailingZeros().scale() > 0) {
                throw ApiException.badRequest("Enter whole or half days between -31 and 31, not 0.", "days");
            }
            if (form.amountPaise() != null) {
                throw ApiException.badRequest("A loss-of-pay correction is in days, not an amount.", "amountPaise");
            }
            days = d.setScale(1, RoundingMode.UNNECESSARY);
        }
        PayrollAdjustment a = adjustments.save(new PayrollAdjustment(runId, form.userId(), form.kind(), amount, days,
                form.reason().strip(), actor));
        adjustments.flush();
        calculate(run);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", runId.toString());
        details.put("month", run.yearMonth().toString());
        details.put("userId", form.userId().toString());
        details.put("kind", form.kind().name());
        details.put("reason", a.getReason());
        audit.record(actor, "payroll_adjustment.added", "payroll_adjustment", a.getId(), details);
        return detail(run);
    }

    public RunDetail removeAdjustment(UUID runId, UUID adjustmentId, Actor actor) {
        TenantContext.require();
        PayrollAdjustment a = adjustments.findById(adjustmentId).filter(x -> x.getRunId().equals(runId))
                .orElseThrow(() -> ApiException.notFound("Adjustment"));
        PayrollRun run = draft(runId);
        adjustments.delete(a);
        adjustments.flush();
        calculate(run);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", runId.toString());
        details.put("month", run.yearMonth().toString());
        details.put("userId", a.getUserId().toString());
        details.put("kind", a.getKind().name());
        audit.record(actor, "payroll_adjustment.removed", "payroll_adjustment", a.getId(), details);
        return detail(run);
    }

    /** Sets the month's TDS for one person instead of the calculated amount; the reason is audited. */
    public RunDetail overrideTds(UUID runId, UUID payslipId, TdsOverrideForm form, Actor actor) {
        TenantContext.require();
        PayrollRun run = draft(runId);
        Payslip slip = slipOf(runId, payslipId);
        String reason = form.reason().strip();
        slip.overrideTds(form.amountPaise(), reason);
        payslips.flush();
        calculate(run);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", runId.toString());
        details.put("month", run.yearMonth().toString());
        details.put("userId", slip.getUserId().toString());
        details.put("reason", reason);
        audit.record(actor, "payroll_tds.overridden", "payslip", slip.getId(), details);
        return detail(run);
    }

    public RunDetail clearTdsOverride(UUID runId, UUID payslipId, Actor actor) {
        TenantContext.require();
        PayrollRun run = draft(runId);
        Payslip slip = slipOf(runId, payslipId);
        if (slip.getTdsOverridePaise() == null) {
            return detail(run);
        }
        slip.overrideTds(null, null);
        payslips.flush();
        calculate(run);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", runId.toString());
        details.put("month", run.yearMonth().toString());
        details.put("userId", slip.getUserId().toString());
        audit.record(actor, "payroll_tds.override_cleared", "payslip", slip.getId(), details);
        return detail(run);
    }

    // ------------------------------------------------------------------ approve and pay

    /**
     * Approves and locks a draft (payroll.approve). The run is recalculated first; when anything changed since it was
     * last calculated (leave, structures, pay details), nothing is approved and the reviewer is asked to recalculate
     * and look again (409). Publishes {@link PayrollApproved}.
     */
    public RunDetail approve(UUID id, Actor actor) {
        TenantContext.require();
        PayrollRun run = draft(id);
        PayrollRun.Totals before = run.totals();
        calculate(run);
        if (!before.equals(run.totals())) {
            throw new ApiException(HttpStatus.CONFLICT, "Figures changed",
                    "The figures changed since they were last calculated. Recalculate, review them and approve again.");
        }
        if (run.totals().staffCount() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Nobody to pay",
                    "Nobody is in this month's payroll. Add salary structures first.");
        }
        Instant now = Instant.now();
        run.approve(actor, now);
        runs.flush();
        PayrollRun.Totals t = run.totals();
        audit.record(actor, "payroll_run.approved", "payroll_run", run.getId(),
                Map.of("month", run.yearMonth().toString(), "staffCount", t.staffCount()));
        events.publishEvent(new PayrollApproved(TenantContext.require(), run.getId(), run.yearMonth(),
                t.staffCount(), t.gross(), t.deductions(), t.net(), t.employer(), t.pfEmployee(), t.pfEmployer(),
                t.edli(), t.pfAdmin(), t.esiEmployee(), t.esiEmployer(), t.pt(), t.tds(), actor.id(), now));
        return detail(run);
    }

    /** Records the salary payment of an approved run (409 for a draft or a run already paid). */
    public RunDetail markPaid(UUID id, PaidForm form, Actor actor) {
        TenantContext.require();
        PayrollRun run = runs.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Payroll run"));
        if (run.getStatus() != RunStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, run.getStatus() == RunStatus.PAID ? "Already paid"
                    : "Not approved", run.getStatus() == RunStatus.PAID ? "This month is already marked paid."
                            : "Approve the month's payroll before marking it paid.");
        }
        if (form.paidOn().isBefore(run.getMonth())) {
            throw ApiException.badRequest("The payment date cannot be before the month starts.", "paidOn");
        }
        if (form.paidOn().isAfter(PayrollSettingsService.today())) {
            throw ApiException.badRequest("The payment date cannot be in the future.", "paidOn");
        }
        run.paid(form.paidOn(), PayrollForms.clean(form.reference()), actor, Instant.now());
        runs.flush();
        audit.record(actor, "payroll_run.paid", "payroll_run", run.getId(),
                Map.of("month", run.yearMonth().toString(), "paidOn", form.paidOn().toString()));
        return detail(run);
    }

    // ------------------------------------------------------------------ calculation

    /**
     * Works out every payslip of a draft from scratch: who is on the roll with a structure, their days, loss of pay,
     * adjustments and the statutory figures. Payslips of people no longer in the run are removed with their
     * adjustments; TDS overrides are kept.
     */
    void calculate(PayrollRun run) {
        YearMonth month = run.yearMonth();
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        Values v = settings.values();
        List<LocalDate> working = calendar.workingDays(start, end);
        StatutoryRate rate = statutory.rateFor(month);
        Optional<TaxYear> taxYear = statutory.taxYearFor(month);
        List<PayrollMath.PtSlab> pt = v.ptEnabled() ? statutory.ptSlabsFor(v.ptState(), month) : List.of();
        Map<UUID, List<Loaded>> structures = book.all();
        Map<UUID, StaffPayProfile> profileByUser = profiles.findAll().stream()
                .collect(Collectors.toMap(StaffPayProfile::getUserId, Function.identity()));
        Map<UUID, BigDecimal> lop = staff.lossOfPayDays(start, end);
        Map<UUID, List<PayslipCalculator.Adjustment>> adjustmentsByUser = adjustments.findByRun(run.getId()).stream()
                .collect(Collectors.groupingBy(PayrollAdjustment::getUserId, Collectors.mapping(
                        a -> new PayslipCalculator.Adjustment(a.getKind(), a.getAmountPaise(), a.getDays()),
                        Collectors.toList())));
        Map<UUID, YearToDate> ytd = yearToDate(month);
        Map<UUID, Payslip> existing = payslips.findByRun(run.getId()).stream()
                .collect(Collectors.toMap(Payslip::getUserId, Function.identity()));
        LocalDate esiPeriodStart = PayrollMath.esiPeriodStart(month).atDay(1);

        Map<UUID, List<Line>> newLines = new LinkedHashMap<>();
        List<Payslip> kept = new ArrayList<>();
        for (PayrollPerson p : staff.people()) {
            List<Loaded> mine = structures.get(p.userId());
            Optional<Loaded> structure = StructureBook.inForce(mine, start);
            if (structure.isEmpty() || !onRoll(p, start, end)) {
                continue;
            }
            LocalDate from = p.dateOfJoining() != null && p.dateOfJoining().isAfter(start) ? p.dateOfJoining()
                    : start;
            LocalDate to = p.dateOfLeaving() != null && p.dateOfLeaving().isBefore(end) ? p.dateOfLeaving() : end;
            BigDecimal basis;
            BigDecimal onRoll;
            if (v.prorationBasis() == ProrationBasis.WORKING_DAYS) {
                basis = BigDecimal.valueOf(working.size());
                onRoll = BigDecimal.valueOf(working.stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).count());
            } else {
                basis = BigDecimal.valueOf(month.lengthOfMonth());
                onRoll = BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to) + 1);
            }
            LocalDate decideOn = p.dateOfJoining() != null && p.dateOfJoining().isAfter(esiPeriodStart)
                    ? p.dateOfJoining().withDayOfMonth(1) : esiPeriodStart;
            Long esiAtStart = StructureBook.inForce(mine, decideOn).map(Loaded::esiWages).orElse(null);
            StaffPayProfile profile = profileByUser.get(p.userId());
            Payslip slip = existing.remove(p.userId());
            boolean fresh = slip == null;
            if (fresh) {
                slip = new Payslip(run.getId(), p.userId());
            }
            Result r = PayslipCalculator.calculate(new Input(month, structure.get().from(), structure.get().lines(),
                    basis, onRoll, lop.getOrDefault(p.userId(), BigDecimal.ZERO),
                    adjustmentsByUser.getOrDefault(p.userId(), List.of()),
                    v.pfEnabled() && (profile == null || profile.isPfMember()), v.pfOnFullWages(), rate.pfRates(),
                    v.esiEnabled(), esiAtStart, rate.getEsiWageCeilingPaise(), rate.getEsiEmployeePercent(),
                    rate.getEsiEmployerPercent(), pt, profile != null && profile.isPtWomenSlab(),
                    taxYear.map(TaxYear::rules).orElse(null), ytd.getOrDefault(p.userId(), YearToDate.NONE),
                    futureMonths(month, p.dateOfLeaving()), slip.getTdsOverridePaise()));
            slip.apply(person(p, profile), r);
            if (fresh) {
                payslips.save(slip);
            }
            kept.add(slip);
            newLines.put(slip.getId(), r.lines());
        }
        for (Payslip gone : existing.values()) {
            adjustments.deleteByRunAndUser(run.getId(), gone.getUserId());
            lines.deleteByPayslip(gone.getId());
            payslips.delete(gone);
        }
        payslips.flush();
        lines.deleteByRun(run.getId());
        newLines.forEach((payslipId, list) -> {
            int order = 0;
            for (Line l : list) {
                lines.save(new PayslipLine(payslipId, l, order++));
            }
        });
        lines.flush();
        run.calculated(v.prorationBasis(), working.size(), totals(kept, rate));
        runs.flush();
    }

    /** On the roll for at least one day of the month: joined by its end, and not left before it began. */
    static boolean onRoll(PayrollPerson p, LocalDate start, LocalDate end) {
        if (p.dateOfJoining() != null && p.dateOfJoining().isAfter(end)) {
            return false;
        }
        if (p.dateOfLeaving() != null) {
            return !p.dateOfLeaving().isBefore(start);
        }
        return p.active();
    }

    /** Months after this one in its financial year that the person is expected to be paid for. */
    static int futureMonths(YearMonth month, LocalDate dateOfLeaving) {
        YearMonth last = PayrollMath.financialYearStart(month).plusMonths(11);
        if (dateOfLeaving != null && YearMonth.from(dateOfLeaving).isBefore(last)) {
            last = YearMonth.from(dateOfLeaving);
        }
        return (int) Math.max(0, month.until(last, ChronoUnit.MONTHS));
    }

    /** Taxable pay and TDS per person in the approved and paid months of the financial year before this month. */
    private Map<UUID, YearToDate> yearToDate(YearMonth month) {
        YearMonth fyStart = PayrollMath.financialYearStart(month);
        if (!month.isAfter(fyStart)) {
            return Map.of();
        }
        Map<UUID, YearToDate> ytd = new HashMap<>();
        for (Payslip s : payslips.findLockedBetween(fyStart.atDay(1), month.minusMonths(1).atDay(1))) {
            ytd.merge(s.getUserId(), new YearToDate(s.getTaxablePaise(), s.getTdsPaise()),
                    (a, b) -> new YearToDate(a.taxablePaise() + b.taxablePaise(), a.tdsPaise() + b.tdsPaise()));
        }
        return ytd;
    }

    private static Payslip.Person person(PayrollPerson p, StaffPayProfile profile) {
        return new Payslip.Person(p.name(), p.employeeCode(), p.designation(), p.departmentId(), p.departmentName(),
                p.dateOfJoining(), p.dateOfLeaving(),
                profile == null ? null : PayrollViews.maskPan(profile.getPan()),
                profile == null ? null : PayrollViews.last4(profile.getBankAccountNumber()),
                profile == null ? null : profile.getBankIfsc(), profile == null ? null : profile.getUan(),
                profile == null ? null : profile.getEsiNumber());
    }

    /** Sums over the payslips. EPF administration charges are at least the monthly minimum when anyone is in PF. */
    static PayrollRun.Totals totals(List<Payslip> slips, StatutoryRate rate) {
        long gross = 0;
        long deductions = 0;
        long net = 0;
        long employer = 0;
        long pfEmployee = 0;
        long pfEmployer = 0;
        long pfAdmin = 0;
        long edli = 0;
        long esiEmployee = 0;
        long esiEmployer = 0;
        long pt = 0;
        long tds = 0;
        boolean anyPf = false;
        for (Payslip s : slips) {
            gross += s.getGrossPaise();
            deductions += s.getDeductionsPaise();
            net += s.getNetPaise();
            employer += s.getEmployerPaise();
            pfEmployee += s.getPfEmployeePaise();
            pfEmployer += s.getEpsPaise() + s.getEpfEmployerPaise();
            pfAdmin += s.getPfAdminPaise();
            edli += s.getEdliPaise();
            esiEmployee += s.getEsiEmployeePaise();
            esiEmployer += s.getEsiEmployerPaise();
            pt += s.getPtPaise();
            tds += s.getTdsPaise();
            anyPf |= s.getEpfWagesPaise() > 0;
        }
        if (anyPf && pfAdmin < rate.getPfAdminMinPaise()) {
            employer += rate.getPfAdminMinPaise() - pfAdmin;
            pfAdmin = rate.getPfAdminMinPaise();
        }
        return new PayrollRun.Totals(slips.size(), gross, deductions, net, employer, pfEmployee, pfEmployer, pfAdmin,
                edli, esiEmployee, esiEmployer, pt, tds);
    }

    // ------------------------------------------------------------------ views

    RunDetail detail(PayrollRun run) {
        List<Payslip> slips = payslips.findByRun(run.getId());
        Map<UUID, String> names = slips.stream()
                .collect(Collectors.toMap(Payslip::getUserId, Payslip::getEmployeeName));
        List<PayslipRow> rows = slips.stream().map(PayrollRunService::payslipRow).toList();
        List<AdjustmentView> adjustmentViews = adjustments.findByRun(run.getId()).stream()
                .map(a -> new AdjustmentView(a.getId(), a.getUserId(), names.get(a.getUserId()), a.getKind(),
                        a.getAmountPaise(), a.getDays(), a.getReason(), a.getCreatedByName(), a.getCreatedAt()))
                .toList();
        Optional<TaxYear> taxYear = statutory.taxYearFor(run.yearMonth());
        return new RunDetail(run.getId(), run.yearMonth().toString(), run.getStatus(), run.getProrationBasis(),
                run.getDaysInMonth(), run.getWorkingDays(), RunTotals.of(run.totals()), run.getCalculatedAt(),
                run.getCreatedByName(), run.getApprovedByName(), run.getApprovedAt(), run.getPaidOn(),
                run.getPaymentReference(), run.getPaidByName(),
                taxYear.map(y -> y.rule().getFinancialYear()).orElse(null), rows, adjustmentViews,
                warnings(run, slips, taxYear), run.getVersion());
    }

    static PayslipRow payslipRow(Payslip s) {
        long other = s.getDeductionsPaise() - s.getPfEmployeePaise() - s.getEsiEmployeePaise() - s.getPtPaise()
                - s.getTdsPaise();
        return new PayslipRow(s.getId(), s.getUserId(), s.getEmployeeName(), s.getEmployeeCode(),
                s.getDepartmentName(), s.getPayableDays(), s.getLopDays(), s.getGrossPaise(), s.getPfEmployeePaise(),
                s.getEsiEmployeePaise(), s.getPtPaise(), s.getTdsPaise(), s.getTdsOverridePaise() != null,
                s.getTdsOverrideReason(), s.getTdsCalculatedPaise(), other, s.getDeductionsPaise(), s.getNetPaise(),
                s.getEmployerPaise(), s.getBankLast4() != null);
    }

    private List<Warning> warnings(PayrollRun run, List<Payslip> slips, Optional<TaxYear> taxYear) {
        List<Warning> warnings = new ArrayList<>();
        YearMonth month = run.yearMonth();
        if (taxYear.isEmpty()) {
            warnings.add(new Warning("NO_TAX_RULES", null, null, PayrollMath.financialYear(month)));
        } else if (!taxYear.get().forThisYear()) {
            warnings.add(new Warning("TAX_RULES_EARLIER_YEAR", null, null, taxYear.get().rule().getFinancialYear()));
        }
        if (!run.getStatus().locked()) {
            Values v = settings.values();
            if (v.ptEnabled() && v.ptState() == null) {
                warnings.add(new Warning("NO_PT_STATE", null, null, null));
            } else if (v.ptEnabled() && statutory.ptSlabsFor(v.ptState(), month).isEmpty()) {
                warnings.add(new Warning("NO_PT_SLABS", null, null, v.ptState()));
            }
            Map<UUID, List<Loaded>> structures = book.all();
            LocalDate start = month.atDay(1);
            for (PayrollPerson p : staff.people()) {
                if (onRoll(p, start, month.atEndOfMonth())
                        && StructureBook.inForce(structures.get(p.userId()), start).isEmpty()) {
                    warnings.add(new Warning("NO_STRUCTURE", p.userId(), p.name(), null));
                }
            }
        }
        for (Payslip s : slips) {
            if (s.getEmployeeCode() == null) {
                warnings.add(new Warning("PROFILE_INCOMPLETE", s.getUserId(), s.getEmployeeName(), null));
            }
            if (s.getBankLast4() == null && s.getNetPaise() > 0) {
                warnings.add(new Warning("NO_BANK", s.getUserId(), s.getEmployeeName(), null));
            }
            if (s.getPanMasked() == null && s.getTdsAnnualTaxPaise() > 0) {
                warnings.add(new Warning("NO_PAN", s.getUserId(), s.getEmployeeName(), null));
            }
            if (s.getUan() == null && s.getPfEmployeePaise() > 0) {
                warnings.add(new Warning("NO_UAN", s.getUserId(), s.getEmployeeName(), null));
            }
            if (s.getEsiNumber() == null && s.isEsiCovered()) {
                warnings.add(new Warning("NO_ESI_NUMBER", s.getUserId(), s.getEmployeeName(), null));
            }
        }
        return warnings;
    }

    static RunRow row(PayrollRun r) {
        return new RunRow(r.getId(), r.yearMonth().toString(), r.getStatus(), RunTotals.of(r.totals()),
                r.getCalculatedAt(), r.getApprovedByName(), r.getApprovedAt(), r.getPaidOn());
    }

    // ------------------------------------------------------------------ helpers

    /** The run, locked for this transaction, which must still be a draft (409 once approved). */
    private PayrollRun draft(UUID id) {
        PayrollRun run = runs.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Payroll run"));
        if (run.getStatus().locked()) {
            throw new ApiException(HttpStatus.CONFLICT, "Payroll locked",
                    "This month's payroll is approved and cannot change. Make the correction as an adjustment in a "
                            + "later month.");
        }
        return run;
    }

    private Payslip slipOf(UUID runId, UUID payslipId) {
        return payslips.findById(payslipId).filter(s -> s.getRunId().equals(runId))
                .orElseThrow(() -> ApiException.notFound("Payslip"));
    }
}
