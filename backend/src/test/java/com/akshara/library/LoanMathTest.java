package com.akshara.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/** Due dates, days late and fines around Sundays and school holidays, and the numbering rules. */
class LoanMathTest {

    /** Thursday 1 October 2026; Friday 2 October is Gandhi Jayanti, a holiday. */
    static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);
    static final Set<LocalDate> HOLIDAYS = Set.of(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 20),
            LocalDate.of(2026, 10, 21));
    static final Predicate<LocalDate> CLOSED = d -> d.getDayOfWeek() == DayOfWeek.SUNDAY || HOLIDAYS.contains(d);

    @Test
    void dueDatesSkipSundaysAndHolidays() {
        // 14 days after Thursday 1 October is Thursday 15 October, an open day.
        assertThat(LoanMath.dueDate(THURSDAY, 14, CLOSED)).isEqualTo(LocalDate.of(2026, 10, 15));
        // 3 days after is Sunday 4 October: due on Monday 5 October.
        assertThat(LoanMath.dueDate(THURSDAY, 3, CLOSED)).isEqualTo(LocalDate.of(2026, 10, 5));
        // 1 day after is the holiday on Friday 2 October: due on Saturday 3 October.
        assertThat(LoanMath.dueDate(THURSDAY, 1, CLOSED)).isEqualTo(LocalDate.of(2026, 10, 3));
        // 19 days after is Tuesday 20 October, the first of two holidays: due on Thursday 22 October.
        assertThat(LoanMath.dueDate(THURSDAY, 19, CLOSED)).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThatThrownBy(() -> LoanMath.dueDate(THURSDAY, 0, CLOSED)).isInstanceOf(IllegalArgumentException.class);
        // A calendar closed for ever does not loop for ever.
        assertThat(LoanMath.nextOpenDay(THURSDAY, d -> true)).isEqualTo(THURSDAY.plusDays(LoanMath.MAX_CLOSED_RUN));
    }

    @Test
    void daysLateCountOnlyOpenDays() {
        LocalDate due = LocalDate.of(2026, 10, 15); // Thursday
        assertThat(LoanMath.daysLate(due, due, CLOSED)).isZero();
        assertThat(LoanMath.daysLate(due, due.minusDays(3), CLOSED)).isZero();
        // Friday and Saturday late.
        assertThat(LoanMath.daysLate(due, LocalDate.of(2026, 10, 17), CLOSED)).isEqualTo(2);
        // Sunday 18 does not count; Monday 19 does; the holidays on 20 and 21 do not.
        assertThat(LoanMath.daysLate(due, LocalDate.of(2026, 10, 18), CLOSED)).isEqualTo(2);
        assertThat(LoanMath.daysLate(due, LocalDate.of(2026, 10, 21), CLOSED)).isEqualTo(3);
        assertThat(LoanMath.daysLate(due, LocalDate.of(2026, 10, 22), CLOSED)).isEqualTo(4);
        // A holiday declared on the due date after the loan: the next open day is still on time.
        LocalDate holidayDue = LocalDate.of(2026, 10, 20);
        assertThat(LoanMath.daysLate(holidayDue, LocalDate.of(2026, 10, 22), CLOSED)).isZero();
        assertThat(LoanMath.daysLate(holidayDue, LocalDate.of(2026, 10, 23), CLOSED)).isEqualTo(1);
    }

    @Test
    void finesAreDaysLateTimesTheRateUpToTheCap() {
        assertThat(LoanMath.fine(0, 100, 5000)).isZero();
        assertThat(LoanMath.fine(4, 100, 5000)).isEqualTo(400);
        assertThat(LoanMath.fine(50, 100, 5000)).isEqualTo(5000);
        assertThat(LoanMath.fine(51, 100, 5000)).isEqualTo(5000);
        // A cap of 0 means no cap; a rate of 0 means no fine.
        assertThat(LoanMath.fine(400, 100, 0)).isEqualTo(40_000);
        assertThat(LoanMath.fine(10, 0, 5000)).isZero();
    }

    @Test
    void holdsWaitTheGivenNumberOfOpenDays() {
        // Returned on Thursday 1 October: Friday is a holiday, so 2 open days end on Monday 5 October.
        assertThat(LoanMath.holdUntil(THURSDAY, 2, CLOSED)).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(LoanMath.holdUntil(THURSDAY, 1, CLOSED)).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void numbersReceiptsByFinancialYearAndCopiesByPrefix() {
        assertThat(LoanMath.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(LoanMath.financialYear(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(LoanMath.financialYear(LocalDate.of(2099, 12, 1))).isEqualTo("2099-00");
        assertThat(LoanMath.receiptNo("2026-27", 42)).isEqualTo("LIBF/2026-27/000042");
        assertThat(LoanMath.accessionNo("LIB", 42)).isEqualTo("LIB00042");
        assertThat(LoanMath.accessionNo("", 123_456)).isEqualTo("123456");
        assertThatThrownBy(() -> LoanMath.accessionNo("LIB", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
