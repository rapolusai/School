package com.akshara.library;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import com.akshara.communication.SchoolCalendar;

/**
 * When the library is closed: Sundays and the school's whole-school holidays (communication's calendar). Holidays are
 * read once per range, so working out a due date or a fine does not ask the database day by day.
 */
@Component
class LibraryCalendar {

    /** How far ahead (or back) one lookup reaches; longer loans and fines simply read another range. */
    static final int WINDOW_DAYS = 400;

    private final SchoolCalendar calendar;

    LibraryCalendar(SchoolCalendar calendar) {
        this.calendar = calendar;
    }

    /** Closed days between the two dates, as a test usable with {@link LoanMath}. Days outside the range: Sundays only. */
    Predicate<LocalDate> closedBetween(LocalDate from, LocalDate to) {
        LocalDate start = from.isAfter(to) ? to : from;
        LocalDate end = from.isAfter(to) ? from : to;
        if (end.toEpochDay() - start.toEpochDay() > SchoolCalendar.MAX_RANGE_DAYS) {
            end = start.plusDays(SchoolCalendar.MAX_RANGE_DAYS);
        }
        Map<LocalDate, String> holidays = calendar.holidaysBetween(start, end);
        return day -> day.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.containsKey(day);
    }

    /** Closed days from {@code from} for the next {@link #WINDOW_DAYS} days. */
    Predicate<LocalDate> closedFrom(LocalDate from) {
        return closedBetween(from, from.plusDays(WINDOW_DAYS));
    }

    boolean isClosed(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SUNDAY || calendar.isHoliday(day);
    }
}
