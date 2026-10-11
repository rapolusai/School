package com.akshara.chat;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PTM slot times: back-to-back slots of the event's length from a teacher's first to last available minute, skipping the
 * breaks (a slot that would run into a break starts after it instead). Breaks are written "10:30-10:45,12:00-12:30".
 * Pure.
 */
public final class PtmSlots {

    /** The most breaks an event can have. */
    public static final int MAX_BREAKS = 5;
    /** The most slots one teacher can have at one event. */
    public static final int MAX_SLOTS = 120;

    /** A time range on the meeting day, start included, end excluded. */
    public record Range(LocalTime from, LocalTime to) {

        public Range {
            if (from == null || to == null || !to.isAfter(from)) {
                throw new IllegalArgumentException("A range must end after it starts");
            }
        }

        boolean overlaps(LocalTime start, LocalTime end) {
            return start.isBefore(to) && end.isAfter(from);
        }

        @Override
        public String toString() {
            return from + "-" + to;
        }
    }

    private PtmSlots() {
    }

    /**
     * The slots from {@code from} to {@code to} of {@code minutes} each, around the breaks, in order. A slot never
     * runs past {@code to}.
     */
    public static List<Range> generate(LocalTime from, LocalTime to, int minutes, List<Range> breaks) {
        if (minutes < 1 || from == null || to == null || !to.isAfter(from)) {
            return List.of();
        }
        List<Range> sorted = breaks.stream().sorted(Comparator.comparing(Range::from)).toList();
        List<Range> slots = new ArrayList<>();
        LocalTime start = from;
        while (slots.size() < MAX_SLOTS) {
            LocalTime end = start.plusMinutes(minutes);
            // Past the end of the day, or wrapped around midnight.
            if (end.isAfter(to) || !end.isAfter(start)) {
                break;
            }
            LocalTime slotStart = start;
            Range clash = sorted.stream().filter(b -> b.overlaps(slotStart, end)).findFirst().orElse(null);
            if (clash != null) {
                start = clash.to();
                continue;
            }
            slots.add(new Range(start, end));
            start = end;
        }
        return slots;
    }

    /** Reads "10:30-10:45,12:00-12:30" (blank for none). Throws IllegalArgumentException for anything else. */
    public static List<Range> parseBreaks(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Range> result = new ArrayList<>();
        for (String part : text.split(",")) {
            String[] times = part.trim().split("-");
            if (times.length != 2) {
                throw new IllegalArgumentException("A break is written 10:30-10:45");
            }
            try {
                result.add(new Range(LocalTime.parse(times[0].trim()), LocalTime.parse(times[1].trim())));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("A break is written 10:30-10:45", e);
            }
        }
        return result;
    }

    /** "10:30-10:45,12:00-12:30", ordered by start. */
    public static String formatBreaks(List<Range> breaks) {
        return breaks.stream().sorted(Comparator.comparing(Range::from)).map(Range::toString)
                .collect(Collectors.joining(","));
    }

    /**
     * Why these breaks do not suit an event from {@code from} to {@code to}, or null when they do: at most
     * {@value #MAX_BREAKS}, each inside the hours, none overlapping another.
     */
    public static String problem(LocalTime from, LocalTime to, List<Range> breaks) {
        if (breaks.size() > MAX_BREAKS) {
            return "Add at most " + MAX_BREAKS + " breaks.";
        }
        List<Range> sorted = breaks.stream().sorted(Comparator.comparing(Range::from)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            Range b = sorted.get(i);
            if (b.from().isBefore(from) || b.to().isAfter(to)) {
                return "Every break must be inside the meeting hours.";
            }
            if (i > 0 && b.from().isBefore(sorted.get(i - 1).to())) {
                return "Breaks cannot overlap.";
            }
        }
        return null;
    }

    /** Minutes in a range. */
    static long minutes(Range range) {
        return Duration.between(range.from(), range.to()).toMinutes();
    }
}
