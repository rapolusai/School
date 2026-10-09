package com.akshara.notifications;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/** Quiet hours in Indian time, such as 21:00 to 07:00: messages due then wait until the window ends. */
final class QuietHours {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private QuietHours() {
    }

    /**
     * When the quiet window that {@code now} falls in ends, or empty when {@code now} is outside it (or the window is
     * switched off). A window whose start is after its end runs over midnight.
     */
    static Optional<Instant> holdUntil(Instant now, MessageSettings settings) {
        if (!settings.quietHoursEnabled()) {
            return Optional.empty();
        }
        return holdUntil(now, settings.quietHoursStart(), settings.quietHoursEnd());
    }

    static Optional<Instant> holdUntil(Instant now, LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            return Optional.empty();
        }
        ZonedDateTime local = now.atZone(INDIA);
        LocalDate day = local.toLocalDate();
        LocalTime time = local.toLocalTime();
        if (start.isBefore(end)) {
            boolean inside = !time.isBefore(start) && time.isBefore(end);
            return inside ? Optional.of(day.atTime(end).atZone(INDIA).toInstant()) : Optional.empty();
        }
        if (!time.isBefore(start)) {
            return Optional.of(day.plusDays(1).atTime(end).atZone(INDIA).toInstant());
        }
        if (time.isBefore(end)) {
            return Optional.of(day.atTime(end).atZone(INDIA).toInstant());
        }
        return Optional.empty();
    }
}
