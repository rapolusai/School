package com.akshara.staff;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Which days are school working days. This is the only place leave days, staff attendance reports and the demo data
 * ask; today every day except Sunday counts ({@link SundayOffCalendar}). When the school calendar (holidays) is built,
 * it provides its own bean of this type marked {@code @Primary}, and leave counting and reports follow it.
 */
public interface WorkingDayCalendar {

    boolean isWorkingDay(LocalDate date);

    /** The working days from {@code from} to {@code to}, both included, oldest first. */
    default List<LocalDate> workingDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWorkingDay(d)) {
                days.add(d);
            }
        }
        return days;
    }
}
