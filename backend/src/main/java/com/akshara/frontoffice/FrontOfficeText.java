package com.akshara.frontoffice;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small text helpers of the front office: masking phone numbers, cleaning input and India's clock. */
public final class FrontOfficeText {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    static final String DOTS = "•••";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private FrontOfficeText() {
    }

    /**
     * Hides the middle of a phone number for lists: "9876543201" becomes "98765•••01". Fewer than 8 digits become
     * "•••"; nothing becomes "". Enough to recognise a number, not enough to dial it.
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 8) {
            return DOTS;
        }
        return digits.substring(0, 5) + DOTS + digits.substring(digits.length() - 2);
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
    static String time(Instant at) {
        return TIME.format(LocalDateTime.ofInstant(at, INDIA)).replace("AM", "am").replace("PM", "pm");
    }

    /** Trimmed text, or null when blank. */
    static String clean(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** True when two names are the same ignoring case, punctuation and spacing. */
    static boolean sameName(String a, String b) {
        return a != null && b != null && !key(a).isEmpty() && key(a).equals(key(b));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
