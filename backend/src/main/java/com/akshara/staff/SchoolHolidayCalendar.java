package com.akshara.staff;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.akshara.communication.SchoolCalendar;

/**
 * Working days for leave and staff attendance: Monday to Saturday, less the whole-school holidays declared in the
 * school calendar. Replaces {@link SundayOffCalendar} now that the calendar exists.
 */
@Primary
@Component
class SchoolHolidayCalendar implements WorkingDayCalendar {

    private final SchoolCalendar schoolCalendar;

    SchoolHolidayCalendar(SchoolCalendar schoolCalendar) {
        this.schoolCalendar = schoolCalendar;
    }

    @Override
    public boolean isWorkingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SUNDAY && !schoolCalendar.isHoliday(date);
    }

    /** One calendar query per stretch of {@link SchoolCalendar#MAX_RANGE_DAYS} days, not one per day. */
    @Override
    public List<LocalDate> workingDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate start = from;
        while (!start.isAfter(to)) {
            LocalDate end = start.plusDays(SchoolCalendar.MAX_RANGE_DAYS);
            if (end.isAfter(to)) {
                end = to;
            }
            Map<LocalDate, String> holidays = schoolCalendar.holidaysBetween(start, end);
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.containsKey(d)) {
                    days.add(d);
                }
            }
            start = end.plusDays(1);
        }
        return days;
    }
}
