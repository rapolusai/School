package com.akshara.staff;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

/** Monday to Saturday are working days; Sundays are not. Holidays are not known yet (no school calendar). */
@Component
public class SundayOffCalendar implements WorkingDayCalendar {

    @Override
    public boolean isWorkingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
