package com.akshara.accounts;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;

import com.akshara.shared.ApiException;

/**
 * An Indian financial year, April to March, written "2026-27". Accounts dates are calendar days in India
 * (Asia/Kolkata), like fee receipts.
 */
public record FinancialYear(int startYear) implements Comparable<FinancialYear> {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String MESSAGE = "Give the financial year as YYYY-YY, for example 2026-27.";

    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    public static FinancialYear of(LocalDate date) {
        return new FinancialYear(date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1);
    }

    public static FinancialYear of(YearMonth month) {
        return of(month.atDay(1));
    }

    public static FinancialYear current() {
        return of(today());
    }

    /** "2026-27" → the year; 400 with a field error otherwise. */
    public static FinancialYear parse(String value, String field) {
        if (value == null || !value.strip().matches("^[0-9]{4}-[0-9]{2}$")) {
            throw ApiException.badRequest(MESSAGE, field);
        }
        int start = Integer.parseInt(value.strip().substring(0, 4));
        int end = Integer.parseInt(value.strip().substring(5));
        if (start < 2000 || start > 2100 || (start + 1) % 100 != end) {
            throw ApiException.badRequest(MESSAGE, field);
        }
        return new FinancialYear(start);
    }

    /** The year, or the current one when the value is blank. */
    public static FinancialYear parseOrCurrent(String value, String field) {
        return value == null || value.isBlank() ? current() : parse(value, field);
    }

    /** "2026-27". */
    public String label() {
        return startYear + "-" + String.format("%02d", (startYear + 1) % 100);
    }

    public LocalDate start() {
        return LocalDate.of(startYear, 4, 1);
    }

    public LocalDate end() {
        return LocalDate.of(startYear + 1, 3, 31);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(start()) && !date.isAfter(end());
    }

    /** April to March. */
    public List<YearMonth> months() {
        return IntStream.range(0, 12).mapToObj(i -> YearMonth.of(startYear, 4).plusMonths(i)).toList();
    }

    /** 1 for April … 12 for March; 0 when the date is outside this year. */
    public int monthIndex(LocalDate date) {
        if (!contains(date)) {
            return 0;
        }
        return (int) YearMonth.of(startYear, 4).until(YearMonth.from(date), ChronoUnit.MONTHS) + 1;
    }

    public FinancialYear previous() {
        return new FinancialYear(startYear - 1);
    }

    @Override
    public int compareTo(FinancialYear o) {
        return Integer.compare(startYear, o.startYear);
    }

    @Override
    public String toString() {
        return label();
    }
}
