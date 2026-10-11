package com.akshara.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/**
 * The statutory arithmetic of payroll, in paise and without floating point. Every rate and limit comes in as data
 * (see V14__payroll.sql); only the rounding rules are code:
 * <ul>
 * <li>prorated earnings: to the nearest rupee, halves up;</li>
 * <li>PF, EPS, EDLI and EPF administration charges: each to the nearest rupee, halves up, as the EPFO's ECR does;
 * the employer's EPF share is its total less EPS, so the two always add up to the total;</li>
 * <li>ESI: each share up to the next rupee, as ESIC does;</li>
 * <li>income tax: taxable income and the year's tax to the nearest ten rupees (sections 288A and 288B), the
 * month's TDS to the nearest rupee.</li>
 * </ul>
 */
final class PayrollMath {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal RUPEE = BigDecimal.valueOf(100);
    private static final BigDecimal TEN_RUPEES = BigDecimal.valueOf(1000);

    private PayrollMath() {
    }

    // ------------------------------------------------------------------ rounding

    /** {@code paise × percent / 100}, exact. */
    static BigDecimal percentOf(long paise, BigDecimal percent) {
        return BigDecimal.valueOf(paise).multiply(percent).divide(HUNDRED);
    }

    /** To the nearest rupee, halves up. */
    static long nearestRupee(BigDecimal paise) {
        return paise.divide(RUPEE).setScale(0, RoundingMode.HALF_UP).multiply(RUPEE).longValueExact();
    }

    /** Up to the next whole rupee (ESIC's rule). */
    static long nextRupee(BigDecimal paise) {
        return paise.divide(RUPEE).setScale(0, RoundingMode.CEILING).multiply(RUPEE).longValueExact();
    }

    /** To the nearest ten rupees, halves up (sections 288A and 288B of the Income Tax Act). */
    static long nearestTenRupees(BigDecimal paise) {
        return paise.divide(TEN_RUPEES).setScale(0, RoundingMode.HALF_UP).multiply(TEN_RUPEES).longValueExact();
    }

    // ------------------------------------------------------------------ proration

    /**
     * The part of a monthly amount earned for {@code payableDays} out of {@code basisDays}, to the nearest rupee. A
     * full month is the monthly amount exactly; no basis days pays nothing.
     */
    static long prorate(long monthlyPaise, BigDecimal payableDays, BigDecimal basisDays) {
        if (basisDays.signum() <= 0 || payableDays.signum() <= 0) {
            return 0;
        }
        if (payableDays.compareTo(basisDays) >= 0) {
            return monthlyPaise;
        }
        BigDecimal exact = BigDecimal.valueOf(monthlyPaise).multiply(payableDays).divide(basisDays, 6,
                RoundingMode.HALF_UP);
        return nearestRupee(exact);
    }

    // ------------------------------------------------------------------ provident fund

    /** PF rates and limits in force for a month (payroll.statutory_rate). */
    record PfRates(BigDecimal employeePercent, BigDecimal employerPercent, long wageCeilingPaise,
            BigDecimal epsPercent, long epsMaxPaise, BigDecimal edliPercent, long edliMaxPaise,
            BigDecimal adminPercent) {
    }

    /**
     * One person's PF for a month. {@code pfWages} is basic + DA earned; {@code epfWages} what contributions are
     * worked out on (capped at the ceiling unless the school contributes on full wages); EPS and EDLI wages are always
     * capped. {@code epfEmployer} is the employer's share left after EPS (account 1).
     */
    record Pf(long pfWages, long epfWages, long epsWages, long edliWages, long employee, long eps, long epfEmployer,
            long edli, long admin) {

        static final Pf NONE = new Pf(0, 0, 0, 0, 0, 0, 0, 0, 0);

        /** The employer's 12%: EPF plus EPS. */
        long employerShare() {
            return eps + epfEmployer;
        }
    }

    static Pf pf(long pfWagesPaise, boolean onFullWages, PfRates r) {
        if (pfWagesPaise <= 0) {
            return Pf.NONE;
        }
        long capped = Math.min(pfWagesPaise, r.wageCeilingPaise());
        long epfWages = onFullWages ? pfWagesPaise : capped;
        long employee = nearestRupee(percentOf(epfWages, r.employeePercent()));
        long employerTotal = nearestRupee(percentOf(epfWages, r.employerPercent()));
        long eps = Math.min(nearestRupee(percentOf(capped, r.epsPercent())), r.epsMaxPaise());
        eps = Math.min(eps, employerTotal);
        long edli = Math.min(nearestRupee(percentOf(capped, r.edliPercent())), r.edliMaxPaise());
        long admin = nearestRupee(percentOf(epfWages, r.adminPercent()));
        return new Pf(pfWagesPaise, epfWages, capped, capped, employee, eps, employerTotal - eps, edli, admin);
    }

    // ------------------------------------------------------------------ ESI

    /** One person's ESI for a month: covered or not, the wages contributed on and the two shares. */
    record Esi(boolean covered, long wages, long employee, long employer) {

        static final Esi NONE = new Esi(false, 0, 0, 0);
    }

    /**
     * ESI's contribution periods run April to September and October to March. Someone covered at the start of a
     * period (or when they join during it) stays covered until it ends, whatever their wages do meanwhile.
     */
    static YearMonth esiPeriodStart(YearMonth month) {
        int m = month.getMonthValue();
        if (m >= 4 && m <= 9) {
            return YearMonth.of(month.getYear(), 4);
        }
        return m >= 10 ? YearMonth.of(month.getYear(), 10) : YearMonth.of(month.getYear() - 1, 10);
    }

    /** Covered when the gross wages that decide it (at the start of the period) are within the ceiling. */
    static boolean esiCovered(long wagesAtPeriodStartPaise, long ceilingPaise) {
        return wagesAtPeriodStartPaise > 0 && wagesAtPeriodStartPaise <= ceilingPaise;
    }

    static Esi esi(boolean covered, long esiWagesPaise, BigDecimal employeePercent, BigDecimal employerPercent) {
        if (!covered) {
            return Esi.NONE;
        }
        if (esiWagesPaise <= 0) {
            return new Esi(true, 0, 0, 0);
        }
        return new Esi(true, esiWagesPaise, nextRupee(percentOf(esiWagesPaise, employeePercent)),
                nextRupee(percentOf(esiWagesPaise, employerPercent)));
    }

    // ------------------------------------------------------------------ professional tax

    /**
     * One slab: monthly gross from {@code fromPaise} to {@code toPaise} (both included; no upper limit when null).
     * {@code months} lists the months it applies in (empty: every month).
     */
    record PtSlab(PtAppliesTo appliesTo, long fromPaise, Long toPaise, long amountPaise, Set<Integer> months) {

        boolean matches(long grossPaise) {
            return grossPaise >= fromPaise && (toPaise == null || grossPaise <= toPaise);
        }
    }

    /**
     * Professional tax on a month's gross wages. Where the state has slabs for women, women use those and everyone
     * else the OTHERS slabs. A slab for specific months (February in Maharashtra and Karnataka, March and September for
     * Tamil Nadu's half-yearly tax) replaces the every-month slab in those months.
     */
    static long professionalTax(List<PtSlab> slabs, long grossPaise, Month month, boolean women) {
        if (grossPaise <= 0 || slabs.isEmpty()) {
            return 0;
        }
        boolean womenSlabs = slabs.stream().anyMatch(s -> s.appliesTo() == PtAppliesTo.WOMEN);
        List<PtSlab> mine = slabs.stream()
                .filter(s -> s.appliesTo() == PtAppliesTo.ALL
                        || (womenSlabs && women ? s.appliesTo() == PtAppliesTo.WOMEN
                                : s.appliesTo() == PtAppliesTo.OTHERS))
                .toList();
        int m = month.getValue();
        for (PtSlab s : mine) {
            if (!s.months().isEmpty() && s.months().contains(m) && s.matches(grossPaise)) {
                return s.amountPaise();
            }
        }
        for (PtSlab s : mine) {
            if (s.months().isEmpty() && s.matches(grossPaise)) {
                return s.amountPaise();
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ income tax (new regime)

    /** Income above {@code fromPaise} and up to {@code toPaise} (no upper limit when null) is taxed at the rate. */
    record TaxBand(long fromPaise, Long toPaise, BigDecimal ratePercent) {
    }

    /** A financial year's new-regime rules (payroll.tax_rule and its slabs). */
    record TaxRules(String financialYear, long standardDeductionPaise, long rebateLimitPaise, long rebateMaxPaise,
            BigDecimal cessPercent, List<TaxBand> bands) {
    }

    /**
     * How a year's tax was worked out: the income, the taxable income after the standard deduction (to the nearest
     * ten rupees), tax by slabs, the 87A rebate, marginal relief, cess, and the year's tax (to the nearest ten rupees).
     */
    record TaxWorking(long income, long taxable, long slabTax, long rebate, long marginalRelief, long cess,
            long total) {
    }

    static TaxWorking annualTax(long incomePaise, TaxRules rules) {
        long income = Math.max(0, incomePaise);
        long taxable = nearestTenRupees(BigDecimal.valueOf(Math.max(0, income - rules.standardDeductionPaise())));
        BigDecimal tax = BigDecimal.ZERO;
        for (TaxBand band : rules.bands()) {
            long top = band.toPaise() == null ? taxable : Math.min(taxable, band.toPaise());
            long portion = top - band.fromPaise();
            if (portion > 0) {
                tax = tax.add(percentOf(portion, band.ratePercent()));
            }
        }
        BigDecimal slabTax = tax;
        BigDecimal rebate = BigDecimal.ZERO;
        BigDecimal relief = BigDecimal.ZERO;
        if (taxable <= rules.rebateLimitPaise()) {
            rebate = tax.min(BigDecimal.valueOf(rules.rebateMaxPaise()));
            tax = tax.subtract(rebate);
        } else {
            // Marginal relief: the tax may not be more than the income above the rebate limit.
            BigDecimal excess = BigDecimal.valueOf(taxable - rules.rebateLimitPaise());
            if (tax.compareTo(excess) > 0) {
                relief = tax.subtract(excess);
                tax = excess;
            }
        }
        BigDecimal cess = tax.multiply(rules.cessPercent()).divide(HUNDRED);
        long total = nearestTenRupees(tax.add(cess));
        return new TaxWorking(income, taxable, nearestRupee(slabTax), nearestRupee(rebate), nearestRupee(relief),
                nearestRupee(cess), total);
    }

    /** This month's share of the tax still to deduct, over the months left (this one included). */
    static long monthlyTds(long remainingTaxPaise, int monthsLeft) {
        if (remainingTaxPaise <= 0 || monthsLeft <= 0) {
            return 0;
        }
        return nearestRupee(BigDecimal.valueOf(remainingTaxPaise).divide(BigDecimal.valueOf(monthsLeft), 6,
                RoundingMode.HALF_UP));
    }

    /** April of the financial year the month falls in. */
    static YearMonth financialYearStart(YearMonth month) {
        return month.getMonthValue() >= 4 ? YearMonth.of(month.getYear(), 4) : YearMonth.of(month.getYear() - 1, 4);
    }

    /** "2026-27" for any month from April 2026 to March 2027. */
    static String financialYear(YearMonth month) {
        int start = financialYearStart(month).getYear();
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }
}
