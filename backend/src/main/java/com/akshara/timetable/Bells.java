package com.akshara.timetable;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.akshara.timetable.TimetableViews.BellSchedule;
import com.akshara.timetable.TimetableViews.PeriodView;

/** A school's bell schedule as loaded for one request: working days and the periods of each kind of day. */
final class Bells {

    static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final List<DayOfWeek> workingDays;
    private final boolean saturdaySchedule;
    private final List<Period> weekday;
    private final List<Period> saturday;

    Bells(List<DayOfWeek> workingDays, boolean saturdaySchedule, List<Period> periods) {
        this.workingDays = List.copyOf(workingDays);
        this.saturdaySchedule = saturdaySchedule;
        this.weekday = periods.stream().filter(p -> p.getSchedule() == Period.Schedule.WEEKDAY)
                .sorted(Comparator.comparingInt(Period::getPosition)).toList();
        this.saturday = periods.stream().filter(p -> p.getSchedule() == Period.Schedule.SATURDAY)
                .sorted(Comparator.comparingInt(Period::getPosition)).toList();
    }

    List<DayOfWeek> workingDays() {
        return workingDays;
    }

    boolean isWorking(DayOfWeek day) {
        return workingDays.contains(day);
    }

    /** The periods and breaks of the day in time order (empty when the school is closed that day). */
    List<Period> periods(DayOfWeek day) {
        if (!isWorking(day)) {
            return List.of();
        }
        return day == DayOfWeek.SATURDAY && saturdaySchedule ? saturday : weekday;
    }

    /** The number of teaching periods on the day. */
    int teachingPeriods(DayOfWeek day) {
        return (int) periods(day).stream().filter(p -> !p.isBreak()).count();
    }

    Optional<Period> period(DayOfWeek day, int number) {
        return periods(day).stream().filter(p -> !p.isBreak() && p.getNumber() == number).findFirst();
    }

    /** Teaching periods in a week: the cells of a full section timetable. */
    int weeklyCells() {
        return workingDays.stream().mapToInt(this::teachingPeriods).sum();
    }

    boolean isEmpty() {
        return weekday.isEmpty();
    }

    BellSchedule view() {
        return new BellSchedule(workingDays, saturdaySchedule, weekday.stream().map(Bells::view).toList(),
                saturdaySchedule ? saturday.stream().map(Bells::view).toList() : List.of(),
                (int) weekday.stream().filter(p -> !p.isBreak()).count(),
                saturdaySchedule ? (int) saturday.stream().filter(p -> !p.isBreak()).count() : 0);
    }

    static PeriodView view(Period p) {
        return new PeriodView(p.getNumber(), p.getLabel(), time(p.getStartsAt()), time(p.getEndsAt()), p.isBreak());
    }

    static String time(LocalTime t) {
        return t.format(HH_MM);
    }
}
