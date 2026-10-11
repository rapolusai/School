package com.akshara.chat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.function.Predicate;

/**
 * When staff answer messages: the school's days and hours (Monday to Saturday 08:00 to 18:00 unless it changes them),
 * less whole-school holidays from the school calendar, and not while a teacher is away. A parent may write at any
 * time; notifications to the teacher wait until {@link #nextAllowed}. Pure.
 */
public record ChatHours(Set<DayOfWeek> days, LocalTime from, LocalTime to) {

    /** How far ahead to look for the next working hour (a long holiday plus a long absence). */
    static final int LOOK_AHEAD_DAYS = 120;

    public static final ChatHours DEFAULT = new ChatHours(Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), ChatSettings.DEFAULT_FROM,
            ChatSettings.DEFAULT_TO);

    public ChatHours {
        days = Set.copyOf(days);
        if (days.isEmpty() || !to.isAfter(from)) {
            throw new IllegalArgumentException("Give at least one day and an end after the start");
        }
    }

    /**
     * The first moment at or after {@code now} inside the hours, on a day that is not a holiday ({@code holiday}) and
     * not before {@code returnsOn} (the day an away teacher is back; null when they are not away). {@code now} itself
     * when it is already inside; {@code now} too when nothing is found within {@value #LOOK_AHEAD_DAYS} days.
     */
    public Instant nextAllowed(Instant now, Predicate<LocalDate> holiday, LocalDate returnsOn) {
        ZonedDateTime local = now.atZone(ChatRules.INDIA);
        LocalDate day = local.toLocalDate();
        LocalTime time = local.toLocalTime();
        if (returnsOn != null && returnsOn.isAfter(day)) {
            day = returnsOn;
            time = LocalTime.MIN;
        }
        for (int i = 0; i <= LOOK_AHEAD_DAYS; i++) {
            if (days.contains(day.getDayOfWeek()) && !holiday.test(day) && time.isBefore(to)) {
                if (time.isBefore(from)) {
                    return day.atTime(from).atZone(ChatRules.INDIA).toInstant();
                }
                Instant candidate = day.atTime(time).atZone(ChatRules.INDIA).toInstant();
                return candidate.isBefore(now) ? now : candidate;
            }
            day = day.plusDays(1);
            time = LocalTime.MIN;
        }
        return now;
    }

    /** Whether {@code now} is inside the hours on a working, non-holiday day and the teacher is not away. */
    public boolean isOpen(Instant now, Predicate<LocalDate> holiday, LocalDate returnsOn) {
        return nextAllowed(now, holiday, returnsOn).equals(now);
    }
}
