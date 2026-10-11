package com.akshara.library;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Predicate;

/**
 * The library's date and money rules, free of the database so they can be tested on their own. {@code closed} says
 * whether the library is shut on a day: Sundays and the school's whole-school holidays (see {@link LibraryCalendar}).
 * <ul>
 * <li>A loan is due {@code loanDays} after it is issued; when that day is closed, the next open day.</li>
 * <li>Days late are the open days after the due date up to and including the return date. A copy brought back on the
 * first open day after a holiday that fell on its due date is not late.</li>
 * <li>The fine is days late times the daily rate, never more than the cap (a cap of 0 means no cap).</li>
 * <li>A copy kept for a reservation waits until the {@code holdDays}-th open day after it came back.</li>
 * </ul>
 */
public final class LoanMath {

    /** Far enough to cross any run of holidays a school could declare; stops a calendar that is closed forever. */
    static final int MAX_CLOSED_RUN = 120;

    /** School days are days in India. */
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private LoanMath() {
    }

    /** Today in India. */
    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    /** The first open day on or after {@code day}. */
    public static LocalDate nextOpenDay(LocalDate day, Predicate<LocalDate> closed) {
        LocalDate d = day;
        for (int i = 0; i < MAX_CLOSED_RUN && closed.test(d); i++) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** The due date of a loan issued (or renewed) on {@code from} for {@code loanDays} days. */
    public static LocalDate dueDate(LocalDate from, int loanDays, Predicate<LocalDate> closed) {
        if (loanDays < 1) {
            throw new IllegalArgumentException("A loan lasts at least one day");
        }
        return nextOpenDay(from.plusDays(loanDays), closed);
    }

    /**
     * Open days after the due date up to and including {@code returnedOn}; 0 when returned on time. When the due date
     * itself turned out to be closed (a holiday declared after the loan), the next open day still counts as on time.
     */
    public static int daysLate(LocalDate dueOn, LocalDate returnedOn, Predicate<LocalDate> closed) {
        int days = 0;
        for (LocalDate d = nextOpenDay(dueOn, closed).plusDays(1); !d.isAfter(returnedOn); d = d.plusDays(1)) {
            if (!closed.test(d)) {
                days++;
            }
        }
        return days;
    }

    /** The overdue fine in paise: days late times the daily rate, at most {@code maxFinePaise} unless that is 0. */
    public static long fine(int daysLate, long finePerDayPaise, long maxFinePaise) {
        if (daysLate <= 0 || finePerDayPaise <= 0) {
            return 0;
        }
        long amount = Math.multiplyExact((long) daysLate, finePerDayPaise);
        return maxFinePaise > 0 ? Math.min(amount, maxFinePaise) : amount;
    }

    /** The last day a returned copy is kept for the member who reserved it: the {@code holdDays}-th open day after. */
    public static LocalDate holdUntil(LocalDate today, int holdDays, Predicate<LocalDate> closed) {
        LocalDate d = today;
        for (int i = 0; i < Math.max(holdDays, 1); i++) {
            d = nextOpenDay(d.plusDays(1), closed);
        }
        return d;
    }

    /** The Indian financial year (April to March) of a day: "2026-27". */
    public static String financialYear(LocalDate date) {
        int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** A library fine receipt number: "LIBF/2026-27/000042". */
    public static String receiptNo(String financialYear, int seq) {
        return "LIBF/" + financialYear + "/" + String.format("%06d", seq);
    }

    /** An accession number: the school's prefix and the running number, at least five digits ("LIB00042"). */
    public static String accessionNo(String prefix, long seq) {
        if (seq < 1) {
            throw new IllegalArgumentException("Accession numbers start at 1");
        }
        return (prefix == null ? "" : prefix) + String.format("%05d", seq);
    }
}
