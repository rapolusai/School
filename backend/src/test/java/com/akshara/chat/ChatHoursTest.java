package com.akshara.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class ChatHoursTest {

    private static final Predicate<LocalDate> NO_HOLIDAYS = d -> false;

    /** A moment in India. 2026-10-12 is a Monday. */
    private static Instant at(String date, String time) {
        return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ChatRules.INDIA).toInstant();
    }

    @Test
    void insideTheHoursIsNow() {
        Instant now = at("2026-10-12", "10:15");
        assertThat(ChatHours.DEFAULT.nextAllowed(now, NO_HOLIDAYS, null)).isEqualTo(now);
        assertThat(ChatHours.DEFAULT.isOpen(now, NO_HOLIDAYS, null)).isTrue();
    }

    @Test
    void beforeTheStartWaitsForIt() {
        assertThat(ChatHours.DEFAULT.nextAllowed(at("2026-10-12", "06:30"), NO_HOLIDAYS, null))
                .isEqualTo(at("2026-10-12", "08:00"));
    }

    @Test
    void afterTheEndWaitsForTheNextWorkingMorning() {
        assertThat(ChatHours.DEFAULT.nextAllowed(at("2026-10-12", "18:00"), NO_HOLIDAYS, null))
                .isEqualTo(at("2026-10-13", "08:00"));
        assertThat(ChatHours.DEFAULT.isOpen(at("2026-10-12", "21:30"), NO_HOLIDAYS, null)).isFalse();
    }

    @Test
    void sundayIsNotAWorkingDayByDefault() {
        // Saturday evening and all of Sunday wait for Monday morning.
        assertThat(ChatHours.DEFAULT.nextAllowed(at("2026-10-17", "19:00"), NO_HOLIDAYS, null))
                .isEqualTo(at("2026-10-19", "08:00"));
        assertThat(ChatHours.DEFAULT.nextAllowed(at("2026-10-18", "11:00"), NO_HOLIDAYS, null))
                .isEqualTo(at("2026-10-19", "08:00"));
    }

    @Test
    void holidaysAreSkipped() {
        Predicate<LocalDate> dussehra = d -> d.equals(LocalDate.parse("2026-10-20"));
        assertThat(ChatHours.DEFAULT.nextAllowed(at("2026-10-19", "20:00"), dussehra, null))
                .isEqualTo(at("2026-10-21", "08:00"));
        assertThat(ChatHours.DEFAULT.isOpen(at("2026-10-20", "11:00"), dussehra, null)).isFalse();
    }

    @Test
    void anAwayTeacherIsReachedTheDayTheyAreBack() {
        assertThat(ChatHours.DEFAULT.nextAllowed(at("2026-10-12", "10:00"), NO_HOLIDAYS,
                LocalDate.parse("2026-10-15"))).isEqualTo(at("2026-10-15", "08:00"));
        // Back on a Sunday: the first working hour after it.
        assertThat(ChatHours.DEFAULT.nextAllowed(at("2026-10-12", "10:00"), NO_HOLIDAYS,
                LocalDate.parse("2026-10-18"))).isEqualTo(at("2026-10-19", "08:00"));
        // A return day already passed changes nothing.
        Instant now = at("2026-10-12", "10:00");
        assertThat(ChatHours.DEFAULT.nextAllowed(now, NO_HOLIDAYS, LocalDate.parse("2026-10-12"))).isEqualTo(now);
    }

    @Test
    void theSchoolsOwnDaysAndHours() {
        ChatHours weekdays = new ChatHours(Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), LocalTime.of(9, 30), LocalTime.of(16, 0));
        assertThat(weekdays.nextAllowed(at("2026-10-16", "16:00"), NO_HOLIDAYS, null))
                .isEqualTo(at("2026-10-19", "09:30"));
        assertThat(weekdays.isOpen(at("2026-10-16", "15:59"), NO_HOLIDAYS, null)).isTrue();
    }

    @Test
    void givesUpAfterTheLookAhead() {
        Instant now = at("2026-10-12", "20:00");
        assertThat(ChatHours.DEFAULT.nextAllowed(now, d -> true, null)).isEqualTo(now);
    }

    @Test
    void needsADayAndAnEndAfterTheStart() {
        assertThatThrownBy(() -> new ChatHours(Set.of(), LocalTime.of(8, 0), LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatHours(Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void daysAreStoredAsBits() {
        assertThat(ChatSettings.toBits(Set.of(DayOfWeek.MONDAY, DayOfWeek.SATURDAY))).isEqualTo(1 + 32);
        assertThat(ChatSettings.fromBits(ChatSettings.DEFAULT_DAYS)).hasSize(6).doesNotContain(DayOfWeek.SUNDAY);
        assertThat(ChatSettings.fromBits(64)).containsExactly(DayOfWeek.SUNDAY);
    }
}
