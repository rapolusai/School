package com.akshara.fees;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** Fee dates are calendar days in India: "today", due dates and receipt dates all use Asia/Kolkata. */
final class SchoolDay {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private SchoolDay() {
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    static LocalDate of(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    /** The first instant of a day in India. */
    static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    /** A time on a past day, for payments recorded with an earlier date (the demo data). */
    static Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(ZONE).toInstant();
    }
}
