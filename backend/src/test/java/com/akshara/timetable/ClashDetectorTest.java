package com.akshara.timetable;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.timetable.ClashDetector.Cell;
import com.akshara.timetable.ClashDetector.Conflict;
import com.akshara.timetable.ClashDetector.Excess;
import com.akshara.timetable.ClashDetector.Kind;
import com.akshara.timetable.ClashDetector.SectionSubject;

class ClashDetectorTest {

    static final UUID CLASS_5A = UUID.randomUUID();
    static final UUID CLASS_5B = UUID.randomUUID();
    static final UUID CLASS_2A = UUID.randomUUID();
    static final UUID MATHS = UUID.randomUUID();
    static final UUID ENGLISH = UUID.randomUUID();
    static final UUID RAVI = UUID.randomUUID();
    static final UUID KAVITHA = UUID.randomUUID();

    @Test
    void aTeacherInTwoSectionsAtOnceIsAClash() {
        List<Conflict> clashes = ClashDetector.clashes(List.of(
                new Cell(CLASS_5A, MONDAY, 1, MATHS, RAVI),
                new Cell(CLASS_5B, MONDAY, 1, MATHS, RAVI),
                new Cell(CLASS_2A, MONDAY, 1, ENGLISH, KAVITHA),
                // The same teacher in the next period, or on another day, is fine.
                new Cell(CLASS_5A, MONDAY, 2, MATHS, RAVI),
                new Cell(CLASS_5B, TUESDAY, 1, MATHS, RAVI)));
        assertThat(clashes).hasSize(1);
        Conflict clash = clashes.getFirst();
        assertThat(clash.kind()).isEqualTo(Kind.TEACHER);
        assertThat(clash.teacherId()).isEqualTo(RAVI);
        assertThat(clash.day()).isEqualTo(MONDAY);
        assertThat(clash.period()).isEqualTo(1);
        assertThat(clash.cells()).extracting(Cell::sectionId).containsExactly(CLASS_5A, CLASS_5B);
    }

    @Test
    void aSectionWithTwoSubjectsInOnePeriodIsAClash() {
        List<Conflict> clashes = ClashDetector.clashes(List.of(
                new Cell(CLASS_5A, TUESDAY, 3, MATHS, RAVI),
                new Cell(CLASS_5A, TUESDAY, 3, ENGLISH, KAVITHA),
                new Cell(CLASS_5A, MONDAY, 4, ENGLISH, null),
                new Cell(CLASS_5B, MONDAY, 4, MATHS, null)));
        assertThat(clashes).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(Kind.SECTION);
            assertThat(c.sectionId()).isEqualTo(CLASS_5A);
            assertThat(c.cells()).hasSize(2);
        });
    }

    @Test
    void clashesAreListedInWeekOrderAndPeriodsWithoutATeacherNeverClash() {
        List<Conflict> clashes = ClashDetector.clashes(List.of(
                new Cell(CLASS_5A, TUESDAY, 2, MATHS, RAVI),
                new Cell(CLASS_5B, TUESDAY, 2, MATHS, RAVI),
                new Cell(CLASS_5A, MONDAY, 5, ENGLISH, KAVITHA),
                new Cell(CLASS_2A, MONDAY, 5, ENGLISH, KAVITHA),
                new Cell(CLASS_5A, MONDAY, 6, ENGLISH, null),
                new Cell(CLASS_2A, MONDAY, 6, ENGLISH, null)));
        assertThat(clashes).extracting(Conflict::day, Conflict::period)
                .containsExactly(tuple(MONDAY, 5), tuple(TUESDAY, 2));
    }

    @Test
    void proposedCellsAreCheckedAgainstOtherSections() {
        List<Cell> others = List.of(new Cell(CLASS_5B, MONDAY, 1, MATHS, RAVI),
                new Cell(CLASS_2A, MONDAY, 2, ENGLISH, KAVITHA));
        List<Conflict> clashes = ClashDetector.clashesWith(List.of(
                new Cell(CLASS_5A, MONDAY, 1, MATHS, RAVI),
                new Cell(CLASS_5A, MONDAY, 2, MATHS, RAVI),
                new Cell(CLASS_5A, MONDAY, 3, ENGLISH, KAVITHA)), others);
        assertThat(clashes).singleElement().satisfies(c -> {
            assertThat(c.period()).isEqualTo(1);
            assertThat(c.cells().get(1).sectionId()).isEqualTo(CLASS_5B);
        });
        // Re-saving a section never clashes with its own earlier cells.
        assertThat(ClashDetector.clashesWith(List.of(new Cell(CLASS_5B, MONDAY, 1, MATHS, RAVI)), others)).isEmpty();
    }

    @Test
    void subjectsOverTheirWeeklyAllowanceAreWarnings() {
        List<Cell> cells = List.of(
                new Cell(CLASS_5A, MONDAY, 1, MATHS, RAVI),
                new Cell(CLASS_5A, MONDAY, 2, MATHS, RAVI),
                new Cell(CLASS_5A, TUESDAY, 1, MATHS, RAVI),
                new Cell(CLASS_5A, TUESDAY, 2, ENGLISH, KAVITHA),
                new Cell(CLASS_5B, TUESDAY, 3, ENGLISH, KAVITHA));
        List<Excess> excess = ClashDetector.overWeeklyLimit(cells, Map.of(
                new SectionSubject(CLASS_5A, MATHS), 2,
                new SectionSubject(CLASS_5A, ENGLISH), 1));
        assertThat(excess).containsExactly(new Excess(CLASS_5A, MATHS, 3, 2));
    }
}
