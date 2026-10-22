package com.akshara.health;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Small helpers of the health module: masking policy and reference numbers, cleaning input, India's clock, and
 * the due rules for verifications, trainings, drills and expiries.
 */
public final class HealthText {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** Verifications, trainings, drills and kit items due within this many days count as "due soon". */
    public static final int DUE_SOON_DAYS = 30;

    static final String DOTS = "••••";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private HealthText() {
    }

    /**
     * Hides all but the last four characters of a policy or reference number: "POL-2026-778812" becomes
     * "••••8812". Four characters or fewer become "••••"; nothing becomes null.
     */
    public static String maskNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return DOTS;
        }
        return DOTS + trimmed.substring(trimmed.length() - 4);
    }

    /** Hides the middle of a phone number for lists: "9876543201" becomes "98765•••01". */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 8) {
            return "•••";
        }
        return digits.substring(0, 5) + "•••" + digits.substring(digits.length() - 2);
    }

    /** Trimmed text, or null when blank. */
    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    /** The India day an instant falls on. */
    public static LocalDate dayOf(Instant at) {
        return at.atZone(INDIA).toLocalDate();
    }

    /** The instant a day starts at in India. */
    public static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(INDIA).toInstant();
    }

    /** "11 Oct 2026, 2:30 pm" in India time, for messages. */
    public static String time(Instant at) {
        return TIME.format(LocalDateTime.ofInstant(at, INDIA)).replace("AM", "am").replace("PM", "pm");
    }

    /* ------------------------------------------------------------------ due rules */

    /** What a dated requirement looks like today: OK, due soon (within {@link #DUE_SOON_DAYS}), overdue or missing. */
    public enum Due {
        OK, DUE_SOON, OVERDUE, MISSING
    }

    /** The state of something that must happen again by {@code dueOn} (a drill, a kit check, a training expiry). */
    public static Due dueState(LocalDate dueOn, LocalDate today) {
        if (dueOn == null) {
            return Due.MISSING;
        }
        if (dueOn.isBefore(today)) {
            return Due.OVERDUE;
        }
        return dueOn.isAfter(today.plusDays(DUE_SOON_DAYS)) ? Due.OK : Due.DUE_SOON;
    }

    /**
     * The state of a staff member's police verification: missing when there is no row, overdue when it is pending,
     * requested or flagged, or cleared but expired; due soon when its expiry is within {@link #DUE_SOON_DAYS}; OK
     * when cleared and valid (a cleared verification without an expiry never expires).
     */
    public static Due verificationState(VerificationStatus status, LocalDate expiresOn, LocalDate today) {
        if (status == null) {
            return Due.MISSING;
        }
        if (status != VerificationStatus.CLEARED) {
            return Due.OVERDUE;
        }
        if (expiresOn == null) {
            return Due.OK;
        }
        return dueState(expiresOn, today);
    }

    /** True when a training's expiry has passed or is within {@link #DUE_SOON_DAYS}; a training without expiry is fine. */
    public static boolean trainingNeedsRenewal(LocalDate expiresOn, LocalDate today) {
        return expiresOn != null && dueState(expiresOn, today) != Due.OK;
    }
}
