package com.akshara.hostel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small helpers of the hostel module: India's clock, masking phone numbers and cleaning input. */
public final class HostelText {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** A day outing ends at 6 pm; home leave ends at 8 pm on its last day. */
    public static final LocalTime OUTING_RETURN = LocalTime.of(18, 0);
    public static final LocalTime LEAVE_RETURN = LocalTime.of(20, 0);

    static final String DOTS = "•••";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private HostelText() {
    }

    /** "9876543201" becomes "98765•••01"; fewer than 8 digits become "•••"; nothing becomes "". */
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

    public static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(INDIA).toInstant();
    }

    public static Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(INDIA).toInstant();
    }

    /** "11 Oct 2026, 2:30 pm" in India time, for messages and CSV files. */
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
}
