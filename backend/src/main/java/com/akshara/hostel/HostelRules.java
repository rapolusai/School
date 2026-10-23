package com.akshara.hostel;

import java.time.Instant;
import java.time.LocalDate;

/** The hostel's rules as pure functions: pass dates, lateness, occupancy. Unit tested. */
public final class HostelRules {

    /** How far ahead a pass may be asked for. */
    public static final int MAX_DAYS_AHEAD = 60;
    /** The longest home leave, in days. */
    public static final int MAX_LEAVE_DAYS = 30;

    private HostelRules() {
    }

    /**
     * What is wrong with a pass's dates (the field at fault), or null when they are fine: not in the past, at most
     * {@link #MAX_DAYS_AHEAD} ahead; an outing is one day, home leave at most {@link #MAX_LEAVE_DAYS} days.
     */
    public static String passDatesProblem(PassKind kind, LocalDate from, LocalDate to, LocalDate today) {
        if (from == null || from.isBefore(today) || from.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            return "fromDate";
        }
        if (kind == PassKind.OUTING) {
            return to != null && !to.equals(from) ? "toDate" : null;
        }
        if (to == null || to.isBefore(from) || to.isAfter(from.plusDays(MAX_LEAVE_DAYS))) {
            return "toDate";
        }
        return null;
    }

    /** When the boarder must be back: 6 pm of an outing's day, 8 pm of home leave's last day. */
    public static Instant returnBy(PassKind kind, LocalDate from, LocalDate to) {
        return kind == PassKind.OUTING ? HostelText.at(from, HostelText.OUTING_RETURN)
                : HostelText.at(to, HostelText.LEAVE_RETURN);
    }

    /** A return after the return time is late. */
    public static boolean isLate(Instant returnBy, Instant inAt) {
        return inAt.isAfter(returnBy);
    }

    /** True when the pass covers the night (a boarder away that night is ON_LEAVE at roll call). */
    public static boolean coversNight(LocalDate from, LocalDate to, LocalDate night) {
        return !night.isBefore(from) && !night.isAfter(to);
    }

    /** Two passes of the same boarder may not overlap. */
    public static boolean overlaps(LocalDate from, LocalDate to, LocalDate otherFrom, LocalDate otherTo) {
        return !to.isBefore(otherFrom) && !otherTo.isBefore(from);
    }

    /** Occupancy as a whole percentage of the beds, 0 when there are none. */
    public static int occupancyPercent(long occupied, long beds) {
        if (beds <= 0) {
            return 0;
        }
        return (int) Math.round(occupied * 100.0 / beds);
    }

    /** An allotment may not start after it is asked for by more than a term, nor before the student was admitted. */
    public static boolean allotmentDateOk(LocalDate from, LocalDate today) {
        return from != null && !from.isBefore(today.minusDays(365)) && !from.isAfter(today.plusDays(120));
    }
}
