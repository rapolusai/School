package com.akshara.transport;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.akshara.fees.FeeMath;

/**
 * The transport rules as pure functions: document expiry warnings, the bus's capacity, which allocation covers a day
 * and what a student's bus fee comes to per instalment.
 */
public final class TransportRules {

    /** Documents and licences are flagged this many days before they expire. */
    public static final int WARN_DAYS = 30;

    /** Trips, allocations and expiry dates are dated in India. */
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** A period a student travels on a route: from {@code start} to {@code end} (open when null), both included. */
    public record Period(LocalDate start, LocalDate end) {

        public boolean covers(LocalDate day) {
            return !day.isBefore(start) && (end == null || !day.isAfter(end));
        }

        public boolean overlaps(Period other) {
            return (end == null || !end.isBefore(other.start())) && (other.end() == null
                    || !other.end().isBefore(start));
        }
    }

    private TransportRules() {
    }

    /** Today in India. */
    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    /**
     * A document's state on {@code today}: MISSING without an expiry date, EXPIRED after it, EXPIRING when it ends
     * within {@link #WARN_DAYS} days (the expiry day itself still counts as valid), VALID otherwise.
     */
    public static DocumentState documentState(LocalDate expiresOn, LocalDate today) {
        if (expiresOn == null) {
            return DocumentState.MISSING;
        }
        if (expiresOn.isBefore(today)) {
            return DocumentState.EXPIRED;
        }
        return ChronoUnit.DAYS.between(today, expiresOn) <= WARN_DAYS ? DocumentState.EXPIRING : DocumentState.VALID;
    }

    /** Days from today to the expiry date (negative once expired); null without a date. */
    public static Long daysLeft(LocalDate expiresOn, LocalDate today) {
        return expiresOn == null ? null : ChronoUnit.DAYS.between(today, expiresOn);
    }

    /** True for a document that no longer allows the vehicle on the road (or the driver to drive): expired or missing. */
    public static boolean invalid(DocumentState state) {
        return state == DocumentState.EXPIRED || state == DocumentState.MISSING;
    }

    /**
     * True when adding {@code adding} students to a route that already has {@code allocated} would exceed the
     * vehicle's seats. A route without a vehicle has no limit.
     */
    public static boolean overCapacity(long allocated, int adding, Integer seats) {
        return seats != null && allocated + adding > seats;
    }

    /**
     * Of a student's allocations, the one that covers the day; when several do (an allocation ended on the day a new
     * one began), the one that started last.
     */
    public static <T> Optional<T> covering(List<T> allocations, java.util.function.Function<T, Period> period,
            LocalDate day) {
        T best = null;
        for (T a : allocations) {
            Period p = period.apply(a);
            if (p.covers(day) && (best == null || !p.start().isBefore(period.apply(best).start()))) {
                best = a;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The bus fee for one of a fee structure's {@code instalmentCount} instalments: the fee zone's annual fee split
     * evenly over the instalments (the last one takes the rounding), the same way the fees module splits a head.
     */
    public static long instalmentCharge(long annualFeePaise, int instalmentIndex, int instalmentCount) {
        if (annualFeePaise <= 0 || instalmentCount < 1) {
            return 0;
        }
        return FeeMath.splitEvenly(annualFeePaise, instalmentCount)[instalmentIndex];
    }
}
