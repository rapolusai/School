package com.akshara.staff;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Leave arithmetic in whole and half days, exact (BigDecimal with one decimal place, never floating point). Which days
 * are working days is decided by {@link WorkingDayCalendar}; this class only counts and balances.
 */
final class LeaveMath {

    static final BigDecimal ZERO = BigDecimal.ZERO.setScale(1);
    static final BigDecimal HALF = new BigDecimal("0.5");

    /**
     * What a balance consists of. {@code available} = opening + accrued − taken, or null for loss of pay (no limit).
     * {@code pending} days are waiting for approval and are not yet taken.
     */
    record Balance(BigDecimal opening, BigDecimal accrued, BigDecimal taken, BigDecimal pending, BigDecimal available) {

        /** What is left once the pending requests are approved too; null for loss of pay. */
        BigDecimal availableAfterPending() {
            return available == null ? null : available.subtract(pending);
        }

        /** Balance at the end of the year, carried into the next one (before the cap). */
        BigDecimal closing() {
            return opening.add(accrued).subtract(taken);
        }
    }

    private LeaveMath() {
    }

    /** Days a request costs: 0.5 for a half day, otherwise the number of working days in the range. */
    static BigDecimal days(int workingDays, boolean halfDay) {
        if (workingDays <= 0) {
            return ZERO;
        }
        return halfDay ? HALF.setScale(1) : BigDecimal.valueOf(workingDays).setScale(1);
    }

    /** The part of last year's closing balance that carries forward: never negative, never above the cap. */
    static BigDecimal carryForward(BigDecimal closing, BigDecimal cap) {
        if (closing == null || closing.signum() <= 0 || cap == null || cap.signum() <= 0) {
            return ZERO;
        }
        return scale(closing.min(cap));
    }

    static Balance balance(BigDecimal opening, BigDecimal accrued, BigDecimal taken, BigDecimal pending,
            boolean lossOfPay) {
        if (lossOfPay) {
            return new Balance(ZERO, ZERO, scale(taken), scale(pending), null);
        }
        BigDecimal available = opening.add(accrued).subtract(taken);
        return new Balance(scale(opening), scale(accrued), scale(taken), scale(pending), scale(available));
    }

    /** True when the requested days fit in what is left after other pending requests (always for loss of pay). */
    static boolean fits(Balance balance, BigDecimal requested) {
        BigDecimal left = balance.availableAfterPending();
        return left == null || requested.compareTo(left) <= 0;
    }

    static BigDecimal sum(Collection<BigDecimal> values) {
        return scale(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Whole or half days: 0, 0.5, 1, 1.5… */
    static boolean isWholeOrHalf(BigDecimal value) {
        if (value == null) {
            return false;
        }
        BigDecimal doubled = value.multiply(BigDecimal.TWO);
        return doubled.stripTrailingZeros().scale() <= 0;
    }

    static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.UNNECESSARY);
    }
}
