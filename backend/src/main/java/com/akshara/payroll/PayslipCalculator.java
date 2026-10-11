package com.akshara.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.akshara.payroll.PayrollMath.Esi;
import com.akshara.payroll.PayrollMath.Pf;
import com.akshara.payroll.PayrollMath.PfRates;
import com.akshara.payroll.PayrollMath.PtSlab;
import com.akshara.payroll.PayrollMath.TaxRules;
import com.akshara.payroll.PayrollMath.TaxWorking;

/**
 * Works out one person's payslip for a month from plain inputs: no database and no clock, so every rule is unit
 * tested. The order is fixed:
 * <ol>
 * <li>days: loss of pay is the approved loss-of-pay leave plus corrections, never below zero or above the days on
 * the roll; payable days are the days on the roll less loss of pay;</li>
 * <li>earnings: prorated components are paid for the payable days out of the month's basis days (to the nearest
 * rupee), others in full; arrears and bonus are added;</li>
 * <li>PF on the PF wages earned, ESI on the ESI wages earned when covered, professional tax on the gross, TDS from
 * the projected year;</li>
 * <li>deductions are taken in that order, then fixed deductions and one-off deductions; none is taken beyond what is
 * left, so net pay is never negative.</li>
 * </ol>
 */
final class PayslipCalculator {

    static final BigDecimal ZERO_DAYS = BigDecimal.ZERO.setScale(1);

    /** One line of the salary structure with its component's flags. */
    record ComponentLine(UUID componentId, String code, String name, ComponentKind kind, long monthlyPaise,
            boolean pfWage, boolean esiWage, boolean taxable, boolean prorated, int displayOrder) {
    }

    /** A manual adjustment in the run. */
    record Adjustment(AdjustmentKind kind, Long amountPaise, BigDecimal days) {
    }

    /** Taxable pay and TDS of the earlier months of the financial year (approved or paid runs only). */
    record YearToDate(long taxablePaise, long tdsPaise) {

        static final YearToDate NONE = new YearToDate(0, 0);
    }

    /**
     * Everything one payslip depends on.
     *
     * @param esiWagesAtPeriodStart full monthly ESI wages when the ESI contribution period began (or when the person
     *        joined it), which decide whether they stay covered for the whole period; null when unknown
     * @param ptSlabs the slabs in force (empty when professional tax does not apply)
     * @param taxRules the year's income tax rules, or null for no TDS
     * @param futureMonths months after this one in the financial year the person is expected to be paid for
     * @param tdsOverridePaise the payroll manager's TDS for the month, or null to use the calculated amount
     */
    record Input(YearMonth month, LocalDate structureFrom, List<ComponentLine> lines, BigDecimal basisDays,
            BigDecimal daysOnRoll, BigDecimal lopLeaveDays, List<Adjustment> adjustments, boolean pfApplies,
            boolean pfOnFullWages, PfRates pfRates, boolean esiEnabled, Long esiWagesAtPeriodStart,
            long esiCeilingPaise, BigDecimal esiEmployeePercent, BigDecimal esiEmployerPercent, List<PtSlab> ptSlabs,
            boolean ptWomen, TaxRules taxRules, YearToDate yearToDate, int futureMonths, Long tdsOverridePaise) {
    }

    /** A payslip line; {@code fullPaise} is a component's full monthly amount (null for other lines). */
    record Line(LineKind kind, String code, String name, Long fullPaise, long amountPaise) {
    }

    record Result(LocalDate structureFrom, BigDecimal basisDays, BigDecimal daysOnRoll, BigDecimal lopLeaveDays,
            BigDecimal lopAdjustmentDays, BigDecimal lopDays, BigDecimal payableDays, List<Line> lines, long gross,
            long deductions, long net, long employer, long taxable, boolean pfMember, Pf pf, Esi esi, long pt,
            long tdsCalculated, long tds, TaxWorking tdsWorking) {
    }

    static final String ARREARS = "ARREARS";
    static final String BONUS = "BONUS";
    static final String PF = "PF";
    static final String ESI = "ESI";
    static final String PT = "PT";
    static final String TDS = "TDS";
    static final String DEDUCTION = "DEDUCTION";
    static final String EPF_ER = "EPF_ER";
    static final String EPS = "EPS";
    static final String EDLI = "EDLI";
    static final String PF_ADMIN = "PF_ADMIN";
    static final String ESI_ER = "ESI_ER";

    private static final TaxWorking NO_TAX = new TaxWorking(0, 0, 0, 0, 0, 0, 0);

    private PayslipCalculator() {
    }

    static Result calculate(Input in) {
        // ------------------------------------------------------------ days
        BigDecimal onRoll = days(in.daysOnRoll());
        BigDecimal lopLeave = days(in.lopLeaveDays());
        BigDecimal lopAdjust = in.adjustments().stream().filter(a -> a.kind() == AdjustmentKind.LOP_CORRECTION)
                .map(Adjustment::days).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lop = lopLeave.add(lopAdjust).max(BigDecimal.ZERO).min(onRoll).setScale(1, RoundingMode.HALF_UP);
        BigDecimal payable = onRoll.subtract(lop).setScale(1, RoundingMode.HALF_UP);
        BigDecimal basis = days(in.basisDays());

        // ------------------------------------------------------------ earnings
        List<Line> lines = new ArrayList<>();
        List<ComponentLine> sorted = in.lines().stream()
                .sorted(Comparator.comparingInt(ComponentLine::displayOrder).thenComparing(ComponentLine::name))
                .toList();
        long gross = 0;
        long pfWages = 0;
        long esiWages = 0;
        long taxable = 0;
        long fullTaxable = 0;
        long fullEsiWages = 0;
        boolean onRollAtAll = onRoll.signum() > 0;
        for (ComponentLine c : sorted) {
            if (c.kind() != ComponentKind.EARNING) {
                continue;
            }
            long earned = !onRollAtAll ? 0
                    : c.prorated() ? PayrollMath.prorate(c.monthlyPaise(), payable, basis) : c.monthlyPaise();
            lines.add(new Line(LineKind.EARNING, c.code(), c.name(), c.monthlyPaise(), earned));
            gross += earned;
            if (c.pfWage()) {
                pfWages += earned;
            }
            if (c.esiWage()) {
                esiWages += earned;
                fullEsiWages += c.monthlyPaise();
            }
            if (c.taxable()) {
                taxable += earned;
                fullTaxable += c.monthlyPaise();
            }
        }
        long arrears = sum(in.adjustments(), AdjustmentKind.ARREARS);
        if (arrears > 0) {
            lines.add(new Line(LineKind.EARNING, ARREARS, "Arrears", null, arrears));
            gross += arrears;
            esiWages += arrears;
            taxable += arrears;
        }
        long bonus = sum(in.adjustments(), AdjustmentKind.BONUS);
        if (bonus > 0) {
            lines.add(new Line(LineKind.EARNING, BONUS, "Bonus", null, bonus));
            gross += bonus;
            taxable += bonus;
        }

        // ------------------------------------------------------------ statutory figures
        Pf pf = in.pfApplies() ? PayrollMath.pf(pfWages, in.pfOnFullWages(), in.pfRates()) : Pf.NONE;
        boolean covered = in.esiEnabled() && onRollAtAll
                && (PayrollMath.esiCovered(fullEsiWages, in.esiCeilingPaise())
                        || (in.esiWagesAtPeriodStart() != null
                                && PayrollMath.esiCovered(in.esiWagesAtPeriodStart(), in.esiCeilingPaise())));
        Esi esi = PayrollMath.esi(covered, esiWages, in.esiEmployeePercent(), in.esiEmployerPercent());
        long pt = PayrollMath.professionalTax(in.ptSlabs(), gross, in.month().getMonth(), in.ptWomen());

        TaxWorking working = NO_TAX;
        long tdsCalculated = 0;
        if (in.taxRules() != null) {
            int future = Math.max(0, in.futureMonths());
            long projected = in.yearToDate().taxablePaise() + taxable + fullTaxable * future;
            working = PayrollMath.annualTax(projected, in.taxRules());
            tdsCalculated = PayrollMath.monthlyTds(working.total() - in.yearToDate().tdsPaise(), future + 1);
        }

        // ------------------------------------------------------------ deductions, never beyond what is left
        long left = gross;
        long pfEmployee = Math.min(pf.employee(), left);
        left -= pfEmployee;
        long esiEmployee = Math.min(esi.employee(), left);
        left -= esiEmployee;
        long ptTaken = Math.min(pt, left);
        left -= ptTaken;
        long tds = Math.min(in.tdsOverridePaise() != null ? in.tdsOverridePaise() : tdsCalculated, left);
        left -= tds;
        if (pfEmployee > 0) {
            lines.add(new Line(LineKind.DEDUCTION, PF, "Provident fund", null, pfEmployee));
        }
        if (esiEmployee > 0) {
            lines.add(new Line(LineKind.DEDUCTION, ESI, "ESI", null, esiEmployee));
        }
        if (ptTaken > 0) {
            lines.add(new Line(LineKind.DEDUCTION, PT, "Professional tax", null, ptTaken));
        }
        if (tds > 0) {
            lines.add(new Line(LineKind.DEDUCTION, TDS, "Income tax (TDS)", null, tds));
        }
        for (ComponentLine c : sorted) {
            if (c.kind() != ComponentKind.DEDUCTION) {
                continue;
            }
            long taken = Math.min(c.monthlyPaise(), left);
            left -= taken;
            lines.add(new Line(LineKind.DEDUCTION, c.code(), c.name(), c.monthlyPaise(), taken));
        }
        long oneOff = Math.min(sum(in.adjustments(), AdjustmentKind.DEDUCTION), left);
        left -= oneOff;
        if (oneOff > 0) {
            lines.add(new Line(LineKind.DEDUCTION, DEDUCTION, "Other deduction", null, oneOff));
        }
        long deductions = gross - left;
        Pf pfTaken = pfEmployee == pf.employee() ? pf
                : new Pf(pf.pfWages(), pf.epfWages(), pf.epsWages(), pf.edliWages(), pfEmployee, pf.eps(),
                        pf.epfEmployer(), pf.edli(), pf.admin());
        Esi esiTaken = esiEmployee == esi.employee() ? esi
                : new Esi(esi.covered(), esi.wages(), esiEmployee, esi.employer());

        // ------------------------------------------------------------ employer contributions (shown, not deducted)
        addEmployer(lines, EPF_ER, "PF employer share (EPF)", pf.epfEmployer());
        addEmployer(lines, EPS, "Pension fund (EPS)", pf.eps());
        addEmployer(lines, EDLI, "EDLI", pf.edli());
        addEmployer(lines, PF_ADMIN, "PF administration charges", pf.admin());
        addEmployer(lines, ESI_ER, "ESI employer share", esi.employer());
        long employer = pf.employerShare() + pf.edli() + pf.admin() + esi.employer();

        return new Result(in.structureFrom(), basis, onRoll, lopLeave, lopAdjust.setScale(1, RoundingMode.HALF_UP),
                lop, payable, List.copyOf(lines), gross, deductions, left, employer, taxable, in.pfApplies(), pfTaken,
                esiTaken, ptTaken, tdsCalculated, tds, working);
    }

    private static void addEmployer(List<Line> lines, String code, String name, long paise) {
        if (paise > 0) {
            lines.add(new Line(LineKind.EMPLOYER, code, name, null, paise));
        }
    }

    private static long sum(List<Adjustment> adjustments, AdjustmentKind kind) {
        return adjustments.stream().filter(a -> a.kind() == kind && a.amountPaise() != null)
                .mapToLong(Adjustment::amountPaise).sum();
    }

    private static BigDecimal days(BigDecimal value) {
        return value == null ? ZERO_DAYS : value.max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
    }
}
