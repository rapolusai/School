package com.akshara.timetable;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Finds timetable clashes: a teacher booked in two sections in the same period, or a section given two subjects in
 * the same period. Also finds subjects scheduled more often than their weekly allowance. Pure logic on plain values,
 * so it is tested without a database.
 */
final class ClashDetector {

    enum Kind {
        /** One teacher in two or more sections at the same time. */
        TEACHER,
        /** One section with two or more subjects at the same time. */
        SECTION
    }

    /** One cell of a timetable. {@code teacherId} may be null (no teacher yet). */
    record Cell(UUID sectionId, DayOfWeek day, int period, UUID subjectId, UUID teacherId) {

        Cell {
            Objects.requireNonNull(sectionId);
            Objects.requireNonNull(day);
            Objects.requireNonNull(subjectId);
        }
    }

    /** The cells that clash with each other. {@code teacherId} is set for a teacher clash. */
    record Conflict(Kind kind, DayOfWeek day, int period, UUID teacherId, UUID sectionId, List<Cell> cells) {
    }

    /** A subject scheduled more times in a week than its teacher assignment allows. */
    record Excess(UUID sectionId, UUID subjectId, int scheduled, int allowed) {
    }

    private static final Comparator<Conflict> ORDER = Comparator.comparing(Conflict::day)
            .thenComparingInt(Conflict::period)
            .thenComparing(Conflict::kind);

    private ClashDetector() {
    }

    /** Every clash among the cells, in week order. */
    static List<Conflict> clashes(Collection<Cell> cells) {
        List<Conflict> found = new ArrayList<>();
        Map<String, List<Cell>> bySectionTime = cells.stream()
                .collect(Collectors.groupingBy(c -> c.sectionId() + "|" + c.day() + "|" + c.period(),
                        LinkedHashMap::new, Collectors.toList()));
        bySectionTime.values().stream()
                .filter(group -> group.size() > 1)
                .forEach(group -> {
                    Cell first = group.getFirst();
                    found.add(new Conflict(Kind.SECTION, first.day(), first.period(), null, first.sectionId(),
                            List.copyOf(group)));
                });
        Map<String, List<Cell>> byTeacherTime = cells.stream()
                .filter(c -> c.teacherId() != null)
                .collect(Collectors.groupingBy(c -> c.teacherId() + "|" + c.day() + "|" + c.period(),
                        LinkedHashMap::new, Collectors.toList()));
        byTeacherTime.values().stream()
                .filter(group -> group.stream().map(Cell::sectionId).distinct().count() > 1)
                .forEach(group -> {
                    Cell first = group.getFirst();
                    found.add(new Conflict(Kind.TEACHER, first.day(), first.period(), first.teacherId(), null,
                            List.copyOf(group)));
                });
        found.sort(ORDER);
        return found;
    }

    /**
     * The clashes that a section's proposed cells would cause with the rest of the school: its teachers booked
     * elsewhere at the same time. {@code others} are the cells of every other section.
     */
    static List<Conflict> clashesWith(Collection<Cell> proposed, Collection<Cell> others) {
        Map<String, List<Cell>> busy = others.stream()
                .filter(c -> c.teacherId() != null)
                .collect(Collectors.groupingBy(c -> c.teacherId() + "|" + c.day() + "|" + c.period()));
        List<Conflict> found = new ArrayList<>();
        for (Cell cell : proposed) {
            if (cell.teacherId() == null) {
                continue;
            }
            List<Cell> elsewhere = busy.getOrDefault(cell.teacherId() + "|" + cell.day() + "|" + cell.period(),
                    List.of()).stream().filter(o -> !o.sectionId().equals(cell.sectionId())).toList();
            if (!elsewhere.isEmpty()) {
                List<Cell> group = new ArrayList<>();
                group.add(cell);
                group.addAll(elsewhere);
                found.add(new Conflict(Kind.TEACHER, cell.day(), cell.period(), cell.teacherId(), null, group));
            }
        }
        found.sort(ORDER);
        return found;
    }

    /** Subjects of each section scheduled more often than allowed. Subjects without an allowance are not checked. */
    static List<Excess> overWeeklyLimit(Collection<Cell> cells, Map<SectionSubject, Integer> allowed) {
        Map<SectionSubject, Long> counts = cells.stream()
                .collect(Collectors.groupingBy(c -> new SectionSubject(c.sectionId(), c.subjectId()),
                        LinkedHashMap::new, Collectors.counting()));
        List<Excess> result = new ArrayList<>();
        counts.forEach((key, count) -> {
            Integer limit = allowed.get(key);
            if (limit != null && count > limit) {
                result.add(new Excess(key.sectionId(), key.subjectId(), count.intValue(), limit));
            }
        });
        return result;
    }

    record SectionSubject(UUID sectionId, UUID subjectId) {
    }
}
