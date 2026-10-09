package com.akshara.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.fees.FeeMath.Cell;
import com.akshara.fees.FeeMath.ConcessionRule;
import com.akshara.fees.FeeMath.LateFeeRule;

/** The fee arithmetic: splitting, concessions, late fees, allocation, status and receipt numbers. */
class FeeMathTest {

    static final UUID TUITION = UUID.randomUUID();
    static final UUID ANNUAL = UUID.randomUUID();
    static final UUID EXAM = UUID.randomUUID();
    static final LocalDate DUE = LocalDate.of(2026, 9, 10);

    // ------------------------------------------------------------------ instalment splitting

    @Test
    void anEvenSplitPutsTheRoundingInTheLastInstalment() {
        assertThat(FeeMath.splitEvenly(10_000_00, 4)).containsExactly(2_500_00, 2_500_00, 2_500_00, 2_500_00);
        // ₹1,000.01 in three: 333.33, 333.33, 333.35.
        assertThat(FeeMath.splitEvenly(1_000_01, 3)).containsExactly(333_33, 333_33, 333_35);
        assertThat(FeeMath.splitEvenly(7, 12)).containsExactly(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7);
        assertThat(FeeMath.splitEvenly(5_000_00, 1)).containsExactly(5_000_00);
        assertThat(FeeMath.splitEvenly(0, 4)).containsExactly(0, 0, 0, 0);
    }

    @Test
    void sharesAlwaysAddUpToTheTotal() {
        for (long total : new long[] {1, 99, 1_000_01, 12_345_67, 9_999_999_99L}) {
            for (int parts = 1; parts <= 12; parts++) {
                assertThat(Arrays.stream(FeeMath.splitEvenly(total, parts)).sum()).isEqualTo(total);
            }
            long[] proportional = FeeMath.splitProportionally(total, new long[] {3, 0, 7, 11});
            assertThat(Arrays.stream(proportional).sum()).isEqualTo(total);
            assertThat(proportional[1]).isZero();
        }
    }

    @Test
    void aSplitNeedsAtLeastOnePart() {
        assertThatThrownBy(() -> FeeMath.splitEvenly(100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeeMath.splitEvenly(-1, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProportionalSplitGivesTheRemainderToTheLastWeightedItem() {
        // ₹100 over 1:1:1 is 33.33, 33.33, 33.34; a trailing zero weight gets nothing.
        assertThat(FeeMath.splitProportionally(100_00, new long[] {1, 1, 1, 0}))
                .containsExactly(33_33, 33_33, 33_34, 0);
        assertThat(FeeMath.splitProportionally(100_00, new long[] {0, 0})).containsExactly(0, 0);
        // Large amounts do not overflow.
        assertThat(FeeMath.splitProportionally(Long.MAX_VALUE / 2, new long[] {Long.MAX_VALUE / 4, 1}))
                .satisfies(s -> assertThat(s[0] + s[1]).isEqualTo(Long.MAX_VALUE / 2));
    }

    // ------------------------------------------------------------------ concessions

    @Test
    void aPercentageConcessionAppliesToEachCellOfTheChosenHeads() {
        List<Cell> cells = List.of(new Cell(TUITION, 10_500_00), new Cell(ANNUAL, 6_000_00),
                new Cell(TUITION, 10_500_00));
        long[] concession = FeeMath.concessions(cells, List.of(percent(1_000, TUITION)));
        assertThat(concession).containsExactly(1_050_00, 0, 1_050_00);
    }

    @Test
    void percentagesRoundHalfUpToWholePaise() {
        // 12.5% of ₹333.33 is ₹41.66625: 4167 paise.
        long[] concession = FeeMath.concessions(List.of(new Cell(TUITION, 333_33)), List.of(percent(1_250, TUITION)));
        assertThat(concession).containsExactly(41_67);
    }

    @Test
    void rteIsTheWholeTuitionAndNothingElse() {
        List<Cell> cells = List.of(new Cell(TUITION, 10_500_00), new Cell(ANNUAL, 6_000_00),
                new Cell(TUITION, 10_500_00), new Cell(EXAM, 1_000_00));
        long[] concession = FeeMath.concessions(cells, List.of(percent(FeeMath.FULL_PERCENT_BP, TUITION)));
        assertThat(concession).containsExactly(10_500_00, 0, 10_500_00, 0);
    }

    @Test
    void aFixedConcessionIsSpreadInProportionAndCappedAtTheHeads() {
        List<Cell> cells = List.of(new Cell(TUITION, 10_000_00), new Cell(ANNUAL, 6_000_00),
                new Cell(TUITION, 10_000_00), new Cell(TUITION, 10_000_00));
        long[] concession = FeeMath.concessions(cells, List.of(fixed(1_000_00, TUITION)));
        // ₹1,000 over three equal tuition cells: 333.33, 333.33, 333.34.
        assertThat(concession).containsExactly(333_33, 0, 333_33, 333_34);

        long[] capped = FeeMath.concessions(cells, List.of(fixed(50_000_00, ANNUAL)));
        assertThat(capped).containsExactly(0, 6_000_00, 0, 0);
    }

    @Test
    void concessionsAddUpButNeverTakeADueBelowZero() {
        List<Cell> cells = List.of(new Cell(TUITION, 10_000_00), new Cell(ANNUAL, 6_000_00));
        long[] two = FeeMath.concessions(cells, List.of(percent(1_000, TUITION), fixed(500_00, TUITION)));
        assertThat(two).containsExactly(1_500_00, 0);

        long[] tooMuch = FeeMath.concessions(cells, List.of(percent(8_000, TUITION, ANNUAL),
                fixed(9_000_00, TUITION, ANNUAL)));
        assertThat(tooMuch[0]).isEqualTo(10_000_00);
        assertThat(tooMuch[1]).isEqualTo(6_000_00);
    }

    @Test
    void noConcessionMeansNothingOff() {
        assertThat(FeeMath.concessions(List.of(new Cell(TUITION, 5_000_00)), List.of())).containsExactly(0);
    }

    // ------------------------------------------------------------------ late fees

    @Test
    void aFlatLateFeeStartsAfterTheGraceDays() {
        LateFeeRule rule = new LateFeeRule(LateFeeMode.FLAT, 7, 200_00, 0, 0);
        assertThat(FeeMath.lateFee(rule, DUE, DUE)).isZero();
        assertThat(FeeMath.lateFee(rule, DUE, DUE.plusDays(7))).isZero();
        assertThat(FeeMath.lateFee(rule, DUE, DUE.plusDays(8))).isEqualTo(200_00);
        assertThat(FeeMath.lateFee(rule, DUE, DUE.plusDays(200))).isEqualTo(200_00);
        assertThat(FeeMath.lateFee(rule, DUE, DUE.minusDays(3))).isZero();
    }

    @Test
    void aPerDayLateFeeCountsDaysAfterGraceUpToTheCap() {
        LateFeeRule rule = new LateFeeRule(LateFeeMode.PER_DAY, 7, 0, 10_00, 500_00);
        assertThat(FeeMath.daysLate(rule, DUE, DUE.plusDays(7))).isZero();
        assertThat(FeeMath.lateFee(rule, DUE, DUE.plusDays(8))).isEqualTo(10_00);
        assertThat(FeeMath.lateFee(rule, DUE, DUE.plusDays(29))).isEqualTo(220_00);
        assertThat(FeeMath.lateFee(rule, DUE, DUE.plusDays(57))).isEqualTo(500_00);
        assertThat(FeeMath.lateFee(rule, DUE, DUE.plusDays(400))).isEqualTo(500_00);
    }

    @Test
    void aPerDayLateFeeWithoutCapKeepsGrowingAndNoRuleChargesNothing() {
        LateFeeRule uncapped = new LateFeeRule(LateFeeMode.PER_DAY, 0, 0, 5_00, 0);
        assertThat(FeeMath.lateFee(uncapped, DUE, DUE.plusDays(100))).isEqualTo(500_00);
        assertThat(FeeMath.lateFee(LateFeeRule.NONE, DUE, DUE.plusDays(100))).isZero();
    }

    // ------------------------------------------------------------------ allocation

    @Test
    void paymentsFillTheOldestDueFirst() {
        assertThat(FeeMath.allocate(15_000_00, new long[] {10_000_00, 8_000_00, 8_000_00}))
                .containsExactly(10_000_00, 5_000_00, 0);
    }

    @Test
    void aPartialPaymentLeavesOnlyTheNewestPartPaid() {
        assertThat(FeeMath.allocate(3_000_00, new long[] {2_000_00, 2_000_00})).containsExactly(2_000_00, 1_000_00);
        assertThat(FeeMath.allocate(50_00, new long[] {2_000_00})).containsExactly(50_00);
    }

    @Test
    void moneyBeyondWhatIsOwedIsLeftForTheCaller() {
        long[] allocated = FeeMath.allocate(5_000_00, new long[] {1_000_00, 0, 2_000_00});
        assertThat(allocated).containsExactly(1_000_00, 0, 2_000_00);
        assertThat(5_000_00 - Arrays.stream(allocated).sum()).isEqualTo(2_000_00);
        assertThatThrownBy(() -> FeeMath.allocate(-1, new long[] {1})).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ status

    @Test
    void statusFollowsBalanceAndDueDate() {
        LocalDate today = LocalDate.of(2026, 10, 9);
        assertThat(FeeMath.status(10_000_00, 10_000_00, DUE, today)).isEqualTo(DueStatus.PAID);
        assertThat(FeeMath.status(0, 0, DUE, today)).isEqualTo(DueStatus.PAID);
        assertThat(FeeMath.status(10_000_00, 0, DUE, today)).isEqualTo(DueStatus.OVERDUE);
        assertThat(FeeMath.status(10_000_00, 4_000_00, DUE, today)).isEqualTo(DueStatus.OVERDUE);
        LocalDate december = LocalDate.of(2026, 12, 10);
        assertThat(FeeMath.status(10_000_00, 4_000_00, december, today)).isEqualTo(DueStatus.PARTIAL);
        assertThat(FeeMath.status(10_000_00, 0, december, today)).isEqualTo(DueStatus.UPCOMING);
        assertThat(FeeMath.status(10_000_00, 0, LocalDate.of(2026, 11, 8), today)).isEqualTo(DueStatus.DUE);
        assertThat(FeeMath.status(10_000_00, 0, today, today)).isEqualTo(DueStatus.DUE);
    }

    // ------------------------------------------------------------------ receipts

    @Test
    void financialYearsRunFromAprilToMarch() {
        assertThat(FeeMath.financialYear(LocalDate.of(2026, 10, 9))).isEqualTo("2026-27");
        assertThat(FeeMath.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(FeeMath.financialYear(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(FeeMath.financialYear(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
        assertThat(FeeMath.financialYear(LocalDate.of(2099, 6, 1))).isEqualTo("2099-00");
    }

    @Test
    void receiptNumbersArePaddedPerFinancialYear() {
        assertThat(FeeMath.receiptNo("2026-27", 123)).isEqualTo("RCPT/2026-27/000123");
        assertThat(FeeMath.receiptNo("2026-27", 1)).isEqualTo("RCPT/2026-27/000001");
    }

    @Test
    void indianDigitGroupingInMessages() {
        assertThat(Fees.rupees(1_84_300_00)).isEqualTo("₹1,84,300");
        assertThat(Fees.rupees(1_23_45_678_50L)).isEqualTo("₹1,23,45,678.50");
        assertThat(Fees.rupees(999_00)).isEqualTo("₹999");
        assertThat(Fees.rupees(1_000_00)).isEqualTo("₹1,000");
        assertThat(Fees.rupees(5)).isEqualTo("₹0.05");
    }

    @Test
    void csvCellsCannotBecomeFormulas() {
        assertThat(Csv.text("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(Csv.text("+91 98765")).isEqualTo("'+91 98765");
        assertThat(Csv.text("Rao, Lata")).isEqualTo("\"Rao, Lata\"");
        assertThat(new Csv("A", "B").row(new Csv.Money(-450_50), "-x").toString())
                .isEqualTo("A,B\r\n-450.50,'-x\r\n");
    }

    private static ConcessionRule percent(int basisPoints, UUID... heads) {
        return new ConcessionRule(ConcessionMode.PERCENT, basisPoints, 0, Set.of(heads));
    }

    private static ConcessionRule fixed(long paise, UUID... heads) {
        return new ConcessionRule(ConcessionMode.FIXED, 0, paise, Set.of(heads));
    }
}
