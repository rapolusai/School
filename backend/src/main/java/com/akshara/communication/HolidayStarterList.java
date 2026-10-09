package com.akshara.communication;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A starting list of common Indian school holidays for an admin to review, never applied on its own. The three
 * national holidays fall on fixed dates every year. The festival dates are for 2026-27 only: many follow the lunar
 * calendar, vary by state and are announced by each state government, so every one is marked for the school to
 * confirm (and edit) before adding it.
 */
final class HolidayStarterList {

    enum Group {
        NATIONAL, FESTIVAL
    }

    record Holiday(LocalDate date, String title, Group group) {

        boolean needsConfirmation() {
            return group == Group.FESTIVAL;
        }
    }

    private static final List<Holiday> NATIONAL = List.of(
            new Holiday(LocalDate.of(2000, 1, 26), "Republic Day", Group.NATIONAL),
            new Holiday(LocalDate.of(2000, 8, 15), "Independence Day", Group.NATIONAL),
            new Holiday(LocalDate.of(2000, 10, 2), "Gandhi Jayanti", Group.NATIONAL));

    /** 2026-27 festivals as commonly observed; dates of lunar festivals are approximate and differ by state. */
    private static final List<Holiday> FESTIVALS_2026_27 = List.of(
            new Holiday(LocalDate.of(2026, 4, 3), "Good Friday", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 4, 14), "Dr. Ambedkar Jayanti", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 5, 1), "Buddha Purnima", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 5, 27), "Id-ul-Zuha (Bakrid)", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 6, 26), "Muharram", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 8, 26), "Milad-un-Nabi", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 8, 28), "Raksha Bandhan", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 9, 4), "Janmashtami", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 9, 14), "Ganesh Chaturthi", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 10, 20), "Dussehra", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 11, 8), "Diwali", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 11, 24), "Guru Nanak Jayanti", Group.FESTIVAL),
            new Holiday(LocalDate.of(2026, 12, 25), "Christmas", Group.FESTIVAL),
            new Holiday(LocalDate.of(2027, 1, 14), "Makar Sankranti / Pongal", Group.FESTIVAL),
            new Holiday(LocalDate.of(2027, 3, 6), "Maha Shivaratri", Group.FESTIVAL),
            new Holiday(LocalDate.of(2027, 3, 10), "Id-ul-Fitr", Group.FESTIVAL),
            new Holiday(LocalDate.of(2027, 3, 22), "Holi", Group.FESTIVAL));

    private HolidayStarterList() {
    }

    /** The holidays between the two dates (both included), in date order. */
    static List<Holiday> between(LocalDate from, LocalDate to) {
        List<Holiday> found = new ArrayList<>();
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            for (Holiday h : NATIONAL) {
                LocalDate date = MonthDay.from(h.date()).atYear(year);
                if (!date.isBefore(from) && !date.isAfter(to)) {
                    found.add(new Holiday(date, h.title(), h.group()));
                }
            }
        }
        FESTIVALS_2026_27.stream().filter(h -> !h.date().isBefore(from) && !h.date().isAfter(to)).forEach(found::add);
        found.sort(Comparator.comparing(Holiday::date).thenComparing(Holiday::title));
        return found;
    }
}
