package com.akshara.communication;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.communication.CommunicationTypes.CalendarAudience;
import com.akshara.communication.CommunicationTypes.EntryKind;
import com.akshara.shared.TenantContext;

/**
 * The current school's declared holidays, for other modules (attendance refuses to mark them and leaves them out of
 * school-day counts). Only whole-school HOLIDAY entries count: a holiday for some classes or for staff only does not
 * close the school.
 */
@Service
@Transactional(readOnly = true)
public class SchoolCalendar {

    /** The longest range {@link #holidaysBetween} and {@link #schoolDaysBetween} accept. */
    public static final int MAX_RANGE_DAYS = 800;

    private final CalendarEntryRepository entries;

    SchoolCalendar(CalendarEntryRepository entries) {
        this.entries = entries;
    }

    public boolean isHoliday(LocalDate date) {
        return holidayOn(date).isPresent();
    }

    /** The title of the whole-school holiday on the date, if there is one. */
    public Optional<String> holidayOn(LocalDate date) {
        return Optional.ofNullable(holidaysBetween(date, date).get(date));
    }

    /** Every day between the two dates (both included) that is a whole-school holiday, with its title. */
    public Map<LocalDate, String> holidaysBetween(LocalDate from, LocalDate to) {
        TenantContext.require();
        checkRange(from, to);
        Map<LocalDate, String> days = new TreeMap<>();
        for (CalendarEntry e : entries.findKindBetween(EntryKind.HOLIDAY, CalendarAudience.SCHOOL, from, to)) {
            LocalDate first = e.getStartsOn().isBefore(from) ? from : e.getStartsOn();
            LocalDate last = e.getEndsOn().isAfter(to) ? to : e.getEndsOn();
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                days.putIfAbsent(d, e.getTitle());
            }
        }
        return days;
    }

    /** School days between the two dates (both included): Monday to Saturday, less whole-school holidays. */
    public int schoolDaysBetween(LocalDate from, LocalDate to) {
        Map<LocalDate, String> holidays = holidaysBetween(from, to);
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.containsKey(d)) {
                days++;
            }
        }
        return days;
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Give a range of at most " + MAX_RANGE_DAYS + " days");
        }
    }
}
