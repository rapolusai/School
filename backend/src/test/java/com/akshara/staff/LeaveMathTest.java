package com.akshara.staff;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Leave day counting and balance arithmetic, in exact whole and half days. */
class LeaveMathTest {

    private final WorkingDayCalendar calendar = new SundayOffCalendar();

    @Test
    void sundaysAreNotCountedAsLeaveDays() {
        // Monday 5 October to Sunday 11 October 2026: six working days.
        List<LocalDate> week = calendar.workingDays(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11));
        assertThat(week).hasSize(6).doesNotContain(LocalDate.of(2026, 10, 11));
        // Saturday to Monday is two days, not three.
        assertThat(calendar.workingDays(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12)))
                .containsExactly(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12));
        // A Sunday alone is no working day at all.
        assertThat(calendar.workingDays(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 11))).isEmpty();
        // Two full weeks.
        assertThat(calendar.workingDays(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 18))).hasSize(12);
    }

    @Test
    void aHalfDayCostsHalfADay() {
        assertThat(LeaveMath.days(1, true)).isEqualByComparingTo("0.5");
        assertThat(LeaveMath.days(1, false)).isEqualByComparingTo("1");
        assertThat(LeaveMath.days(6, false)).isEqualByComparingTo("6");
        assertThat(LeaveMath.days(0, false)).isEqualByComparingTo("0");
        assertThat(LeaveMath.days(3, false).scale()).isEqualTo(1);
    }

    @Test
    void balanceIsOpeningPlusAccruedMinusTakenWithPendingKeptApart() {
        LeaveMath.Balance b = LeaveMath.balance(new BigDecimal("4"), new BigDecimal("12"), new BigDecimal("3.5"),
                new BigDecimal("2"), false);
        assertThat(b.available()).isEqualByComparingTo("12.5");
        assertThat(b.availableAfterPending()).isEqualByComparingTo("10.5");
        assertThat(b.closing()).isEqualByComparingTo("12.5");
        assertThat(LeaveMath.fits(b, new BigDecimal("10.5"))).isTrue();
        assertThat(LeaveMath.fits(b, new BigDecimal("11"))).isFalse();
    }

    @Test
    void lossOfPayHasNoLimit() {
        LeaveMath.Balance b = LeaveMath.balance(LeaveMath.ZERO, LeaveMath.ZERO, new BigDecimal("40"),
                new BigDecimal("5"), true);
        assertThat(b.available()).isNull();
        assertThat(b.availableAfterPending()).isNull();
        assertThat(LeaveMath.fits(b, new BigDecimal("100"))).isTrue();
        assertThat(b.taken()).isEqualByComparingTo("40");
    }

    @Test
    void carryForwardIsCappedAndNeverNegative() {
        assertThat(LeaveMath.carryForward(new BigDecimal("25"), new BigDecimal("20"))).isEqualByComparingTo("20");
        assertThat(LeaveMath.carryForward(new BigDecimal("7.5"), new BigDecimal("20"))).isEqualByComparingTo("7.5");
        assertThat(LeaveMath.carryForward(new BigDecimal("-2"), new BigDecimal("20"))).isEqualByComparingTo("0");
        // A type that does not carry forward (casual leave) starts every year afresh.
        assertThat(LeaveMath.carryForward(new BigDecimal("9"), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void yearAfterYear() {
        // Earned leave: 15 a year, at most 30 carried. Year 1: 15 accrued, 2 taken -> 13 carried.
        BigDecimal quota = new BigDecimal("15");
        BigDecimal cap = new BigDecimal("30");
        LeaveMath.Balance first = LeaveMath.balance(LeaveMath.ZERO, quota, new BigDecimal("2"), LeaveMath.ZERO,
                false);
        LeaveMath.Balance second = LeaveMath.balance(LeaveMath.carryForward(first.closing(), cap), quota,
                LeaveMath.ZERO, LeaveMath.ZERO, false);
        assertThat(second.opening()).isEqualByComparingTo("13");
        assertThat(second.available()).isEqualByComparingTo("28");
        // Year 3: 28 + 15 unused would be 43, but only 30 carry.
        LeaveMath.Balance third = LeaveMath.balance(LeaveMath.carryForward(second.available()
                .add(quota), cap), quota, LeaveMath.ZERO, LeaveMath.ZERO, false);
        assertThat(third.opening()).isEqualByComparingTo("30");
        assertThat(third.available()).isEqualByComparingTo("45");
    }

    @Test
    void onlyWholeAndHalfDays() {
        assertThat(LeaveMath.isWholeOrHalf(new BigDecimal("1.5"))).isTrue();
        assertThat(LeaveMath.isWholeOrHalf(new BigDecimal("2"))).isTrue();
        assertThat(LeaveMath.isWholeOrHalf(new BigDecimal("0"))).isTrue();
        assertThat(LeaveMath.isWholeOrHalf(new BigDecimal("0.25"))).isFalse();
        assertThat(LeaveMath.isWholeOrHalf(new BigDecimal("1.3"))).isFalse();
        assertThat(LeaveMath.isWholeOrHalf(null)).isFalse();
        assertThat(LeaveMath.sum(List.of(new BigDecimal("0.5"), new BigDecimal("2"), new BigDecimal("1.5"))))
                .isEqualByComparingTo("4");
    }
}
