package com.akshara.payroll;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** A professional tax slab as stored, whether a state's default or a school's own. */
interface PtSlabRow {

    String getStateCode();

    LocalDate getEffectiveFrom();

    PtAppliesTo getAppliesTo();

    long getFromPaise();

    Long getToPaise();

    long getAmountPaise();

    /** "2", "3,9" or null for every month. */
    String getMonths();

    default Set<Integer> monthSet() {
        return parseMonths(getMonths());
    }

    default PayrollMath.PtSlab toMath() {
        return new PayrollMath.PtSlab(getAppliesTo(), getFromPaise(), getToPaise(), getAmountPaise(), monthSet());
    }

    static Set<Integer> parseMonths(String months) {
        if (months == null || months.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(months.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
