package com.akshara.staff;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

/**
 * Monday to Saturday are working days; Sundays are not. The application uses {@link SchoolHolidayCalendar}, which also
 * leaves out the school's declared holidays; this plain rule remains for unit tests.
 */
@Component
public class SundayOffCalendar implements WorkingDayCalendar {

    @Override
    public boolean isWorkingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
