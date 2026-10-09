package com.akshara.billing;

import java.time.LocalDate;

/**
 * How often a school is invoiced. Plan prices are per student per year; yearly billing gets two months free, so a
 * month costs a tenth of the yearly price (twelve monthly invoices cost 1.2 years' worth).
 */
public enum BillingCycle {

    MONTHLY(1, 10),
    YEARLY(12, 1);

    private final int months;
    private final int yearlyDivisor;

    BillingCycle(int months, int yearlyDivisor) {
        this.months = months;
        this.yearlyDivisor = yearlyDivisor;
    }

    public int months() {
        return months;
    }

    /** The price per student for one period of this cycle, from the yearly price, rounded half up to whole paise. */
    public long unitPricePaise(long pricePerStudentPerYearPaise) {
        return (pricePerStudentPerYearPaise + yearlyDivisor / 2) / yearlyDivisor;
    }

    /** The last day of a period that starts on {@code start} (inclusive): calendar months from the start day. */
    public LocalDate periodEnd(LocalDate start) {
        return start.plusMonths(months).minusDays(1);
    }
}
