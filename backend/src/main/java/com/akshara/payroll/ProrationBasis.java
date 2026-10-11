package com.akshara.payroll;

/** What a month's salary is for, and so how part months and loss of pay are prorated. */
public enum ProrationBasis {
    /** All the days of the month (the default). */
    CALENDAR_DAYS,
    /** Its working days: Monday to Saturday less the school's whole-school holidays. */
    WORKING_DAYS
}
