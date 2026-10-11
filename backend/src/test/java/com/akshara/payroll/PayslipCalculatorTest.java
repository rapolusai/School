package com.akshara.payroll;

import static com.akshara.payroll.PayrollMathTest.ESI_CEILING;
import static com.akshara.payroll.PayrollMathTest.ESI_EE;
import static com.akshara.payroll.PayrollMathTest.ESI_ER;
import static com.akshara.payroll.PayrollMathTest.FY_2025_26;
import static com.akshara.payroll.PayrollMathTest.PF;
import static com.akshara.payroll.PayrollMathTest.TELANGANA;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.akshara.payroll.PayslipCalculator.Adjustment;
import com.akshara.payroll.PayslipCalculator.ComponentLine;
import com.akshara.payroll.PayslipCalculator.Input;
import com.akshara.payroll.PayslipCalculator.Line;
import com.akshara.payroll.PayslipCalculator.Result;
import com.akshara.payroll.PayslipCalculator.YearToDate;

/** One payslip from its inputs: days, proration, the order of deductions, ESI's period rule and TDS. */
class PayslipCalculatorTest {

    static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    /** Display order follows creation, so lines print in the order a test lists them. */
    static final AtomicInteger ORDER = new AtomicInteger();

    static ComponentLine earning(String code, long rupees, boolean pf) {
        return new ComponentLine(UUID.randomUUID(), code, code, ComponentKind.EARNING, rupees * 100, pf, true, true,
                true, ORDER.incrementAndGet());
    }

    static ComponentLine deduction(String code, long rupees) {
        return new ComponentLine(UUID.randomUUID(), code, code, ComponentKind.DEDUCTION, rupees * 100, false, false,
                false, false, 1);
    }

    /** Basic ₹15,000 (PF wages), HRA ₹6,000 and Special ₹4,000: ₹25,000 gross. */
    static List<ComponentLine> standard() {
        return List.of(earning("BASIC", 15_000, true), earning("HRA", 6_000, false), earning("SPECIAL", 4_000, false));
    }

    /** A builder over the inputs with the defaults of a plain September in Telangana. */
    static final class In {
        List<ComponentLine> lines = standard();
        BigDecimal basis = BigDecimal.valueOf(30);
        BigDecimal onRoll = BigDecimal.valueOf(30);
        BigDecimal lop = BigDecimal.ZERO;
        List<Adjustment> adjustments = new ArrayList<>();
        boolean pf = true;
        boolean pfFull = false;
        Long esiAtStart = null;
        PayrollMath.TaxRules tax = FY_2025_26;
        YearToDate ytd = YearToDate.NONE;
        int future = 6;
        Long override = null;

        Result calculate() {
            return PayslipCalculator.calculate(new Input(SEPTEMBER, LocalDate.of(2026, 4, 1), lines, basis, onRoll,
                    lop, adjustments, pf, pfFull, PF, true, esiAtStart, ESI_CEILING, ESI_EE, ESI_ER, TELANGANA, false,
                    tax, ytd, future, override));
        }
    }

    static long amount(Result r, LineKind kind, String code) {
        return r.lines().stream().filter(l -> l.kind() == kind && l.code().equals(code)).mapToLong(Line::amountPaise)
                .sum();
    }

    @Test
    void aFullMonth() {
        Result r = new In().calculate();
        assertThat(r.payableDays()).isEqualByComparingTo("30");
        assertThat(r.gross()).isEqualTo(25_000_00);
        assertThat(amount(r, LineKind.DEDUCTION, "PF")).isEqualTo(1_800_00);
        // ₹25,000 is above the ESI ceiling.
        assertThat(r.esi().covered()).isFalse();
        assertThat(amount(r, LineKind.DEDUCTION, "PT")).isEqualTo(200_00);
        // ₹25,000 a month for the whole year is ₹3 lakh: no tax.
        assertThat(r.tds()).isZero();
        assertThat(r.deductions()).isEqualTo(2_000_00);
        assertThat(r.net()).isEqualTo(23_000_00);
        assertThat(r.employer()).isEqualTo(1_800_00 + 75_00 + 75_00);
        assertThat(amount(r, LineKind.EMPLOYER, "EPS")).isEqualTo(1_250_00);
        assertThat(amount(r, LineKind.EMPLOYER, "EPF_ER")).isEqualTo(550_00);
        assertThat(r.lines().stream().filter(l -> l.kind() == LineKind.EARNING).map(Line::fullPaise))
                .containsExactly(15_000_00L, 6_000_00L, 4_000_00L);
    }

    @Test
    void aJoinerIsPaidForTheDaysOnTheRoll() {
        In in = new In();
        in.onRoll = BigDecimal.valueOf(15);
        Result r = in.calculate();
        assertThat(r.payableDays()).isEqualByComparingTo("15");
        assertThat(r.gross()).isEqualTo(12_500_00);
        // PF on the basic earned (₹7,500). ESI coverage looks at the full month's wages (₹25,000): not covered.
        assertThat(r.pf().employee()).isEqualTo(900_00);
        assertThat(r.esi().covered()).isFalse();
        // Professional tax follows the gross earned: ₹12,500 is below Telangana's ₹15,000.
        assertThat(r.pt()).isZero();
    }

    @Test
    void lossOfPayAndCorrections() {
        In in = new In();
        in.lop = new BigDecimal("2");
        in.adjustments.add(new Adjustment(AdjustmentKind.LOP_CORRECTION, null, new BigDecimal("-0.5")));
        Result r = in.calculate();
        assertThat(r.lopLeaveDays()).isEqualByComparingTo("2");
        assertThat(r.lopAdjustmentDays()).isEqualByComparingTo("-0.5");
        assertThat(r.lopDays()).isEqualByComparingTo("1.5");
        assertThat(r.payableDays()).isEqualByComparingTo("28.5");
        // ₹15,000 × 28.5 / 30 = ₹14,250; ₹6,000 → ₹5,700; ₹4,000 → ₹3,800.
        assertThat(r.gross()).isEqualTo(23_750_00);
        // Loss of pay never goes below zero or beyond the days on the roll.
        in.adjustments.add(new Adjustment(AdjustmentKind.LOP_CORRECTION, null, new BigDecimal("-5")));
        assertThat(in.calculate().lopDays()).isEqualByComparingTo("0");
        in.adjustments.clear();
        in.lop = BigDecimal.valueOf(40);
        Result none = in.calculate();
        assertThat(none.payableDays()).isEqualByComparingTo("0");
        assertThat(none.gross()).isZero();
        assertThat(none.net()).isZero();
    }

    @Test
    void arrearsBonusAndDeductions() {
        In in = new In();
        in.lines = new ArrayList<>(standard());
        in.lines.add(deduction("LOAN", 1_000));
        in.adjustments.add(new Adjustment(AdjustmentKind.ARREARS, 2_000_00L, null));
        in.adjustments.add(new Adjustment(AdjustmentKind.BONUS, 5_000_00L, null));
        in.adjustments.add(new Adjustment(AdjustmentKind.DEDUCTION, 300_00L, null));
        Result r = in.calculate();
        assertThat(r.gross()).isEqualTo(32_000_00);
        assertThat(amount(r, LineKind.EARNING, "ARREARS")).isEqualTo(2_000_00);
        assertThat(amount(r, LineKind.EARNING, "BONUS")).isEqualTo(5_000_00);
        // Arrears and bonus are not PF wages.
        assertThat(r.pf().employee()).isEqualTo(1_800_00);
        assertThat(amount(r, LineKind.DEDUCTION, "LOAN")).isEqualTo(1_000_00);
        assertThat(amount(r, LineKind.DEDUCTION, "DEDUCTION")).isEqualTo(300_00);
        assertThat(r.taxable()).isEqualTo(32_000_00);
        assertThat(r.net()).isEqualTo(32_000_00 - 1_800_00 - 200_00 - 1_000_00 - 300_00 - r.tds());
    }

    @Test
    void netPayIsNeverNegative() {
        In in = new In();
        in.lines = new ArrayList<>(standard());
        in.lines.add(deduction("LOAN", 50_000));
        Result r = in.calculate();
        assertThat(r.net()).isZero();
        assertThat(amount(r, LineKind.DEDUCTION, "LOAN")).isEqualTo(25_000_00 - 1_800_00 - 200_00);
        assertThat(r.deductions()).isEqualTo(r.gross());
    }

    @Test
    void esiCoversLowWagesAndStaysForTheContributionPeriod() {
        In in = new In();
        in.lines = List.of(earning("BASIC", 12_000, true), earning("HRA", 6_000, false));
        Result covered = in.calculate();
        assertThat(covered.esi().covered()).isTrue();
        assertThat(covered.esi().wages()).isEqualTo(18_000_00);
        assertThat(covered.esi().employee()).isEqualTo(135_00);
        assertThat(covered.esi().employer()).isEqualTo(585_00);
        assertThat(amount(covered, LineKind.EMPLOYER, "ESI_ER")).isEqualTo(585_00);

        // A raise to ₹24,000 in September: covered at the start of the period (April) on ₹18,000, so still covered.
        in.lines = List.of(earning("BASIC", 16_000, true), earning("HRA", 8_000, false));
        in.esiAtStart = 18_000_00L;
        Result stays = in.calculate();
        assertThat(stays.esi().covered()).isTrue();
        assertThat(stays.esi().employee()).isEqualTo(180_00);
        // In the next period the wages at its start decide: not covered.
        in.esiAtStart = 24_000_00L;
        assertThat(in.calculate().esi().covered()).isFalse();
    }

    @Test
    void tdsFromTheProjectedYearWithOverrides() {
        In in = new In();
        // ₹1,50,000 a month: ₹18 lakh a year, nothing deducted yet, seven months left with this one.
        in.lines = List.of(earning("BASIC", 75_000, true), earning("SPECIAL", 75_000, false));
        in.ytd = new YearToDate(5 * 1_50_000_00L, 0);
        Result r = in.calculate();
        assertThat(r.tdsWorking().income()).isEqualTo(18_00_000_00L);
        // Taxable ₹17,25,000: 20,000 + 40,000 + 60,000 + 20% of ₹1,25,000 = ₹1,45,000; + 4% = ₹1,50,800.
        assertThat(r.tdsWorking().total()).isEqualTo(1_50_800_00);
        assertThat(r.tdsCalculated()).isEqualTo(21_543_00);
        assertThat(r.tds()).isEqualTo(21_543_00);
        // TDS already deducted counts.
        in.ytd = new YearToDate(5 * 1_50_000_00L, 1_00_000_00L);
        assertThat(in.calculate().tds()).isEqualTo(7_257_00);
        // The payroll manager's override wins; the calculated figure is kept beside it.
        in.override = 5_000_00L;
        Result overridden = in.calculate();
        assertThat(overridden.tds()).isEqualTo(5_000_00);
        assertThat(overridden.tdsCalculated()).isEqualTo(7_257_00);
        // No rules for the year: no TDS.
        in.override = null;
        in.tax = null;
        assertThat(in.calculate().tds()).isZero();
    }

    @Test
    void pfCanBeSwitchedOffAndContributedOnFullWages() {
        In in = new In();
        in.lines = List.of(earning("BASIC", 30_000, true));
        in.pfFull = true;
        assertThat(in.calculate().pf().employee()).isEqualTo(3_600_00);
        in.pf = false;
        Result none = in.calculate();
        assertThat(none.pfMember()).isFalse();
        assertThat(none.pf().employee()).isZero();
        assertThat(none.employer()).isZero();
    }
}
