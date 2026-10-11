package com.akshara.lessons;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.lessons.CoverageMath.Coverage;
import com.akshara.lessons.CoverageMath.Topic;

/** Syllabus coverage: percent taught, topics due by their target month, and the behind-schedule flag. */
class CoverageMathTest {

    static final YearMonth AUGUST = YearMonth.of(2026, 8);

    static Topic topic(String month) {
        return new Topic(UUID.randomUUID(), month == null ? null : YearMonth.parse(month).atDay(1));
    }

    @Test
    void percentIsTaughtTopicsOverAllTopicsToOneDecimal() {
        List<Topic> topics = List.of(topic(null), topic(null), topic(null));
        Coverage one = CoverageMath.of(topics, Set.of(topics.get(0).id()), AUGUST);
        assertThat(one.topics()).isEqualTo(3);
        assertThat(one.taught()).isEqualTo(1);
        assertThat(one.percent()).isEqualByComparingTo("33.3");
        Coverage two = CoverageMath.of(topics, Set.of(topics.get(0).id(), topics.get(1).id()), AUGUST);
        // 66.666... rounds half up.
        assertThat(two.percent()).isEqualByComparingTo("66.7");
        Coverage all = CoverageMath.of(topics, Set.of(topics.get(0).id(), topics.get(1).id(), topics.get(2).id()),
                AUGUST);
        assertThat(all.percent()).isEqualByComparingTo("100.0");
        assertThat(all.percent().scale()).isEqualTo(1);
    }

    @Test
    void noTopicsMeansNoCoverageAndNotBehind() {
        Coverage none = CoverageMath.of(List.of(), Set.of(), AUGUST);
        assertThat(none.percent()).isEqualByComparingTo("0.0");
        assertThat(none.expectedPercent()).isEqualByComparingTo("0.0");
        assertThat(none.behind()).isFalse();
    }

    @Test
    void taughtPlansForTopicsOutsideTheSyllabusAreNotCounted() {
        List<Topic> topics = List.of(topic(null), topic(null));
        Coverage c = CoverageMath.of(topics, Set.of(UUID.randomUUID()), AUGUST);
        assertThat(c.taught()).isZero();
        assertThat(c.percent()).isEqualByComparingTo("0.0");
    }

    @Test
    void aTopicIsDueOnceItsTargetMonthHasEnded() {
        assertThat(CoverageMath.isDue(LocalDate.of(2026, 7, 1), AUGUST)).isTrue();
        assertThat(CoverageMath.isDue(LocalDate.of(2026, 8, 1), AUGUST)).isFalse();
        assertThat(CoverageMath.isDue(LocalDate.of(2026, 9, 1), AUGUST)).isFalse();
        assertThat(CoverageMath.isDue(null, AUGUST)).isFalse();
    }

    @Test
    void aSectionWithADueTopicNotTaughtIsBehindSchedule() {
        Topic june = topic("2026-06");
        Topic july = topic("2026-07");
        Topic august = topic("2026-08");
        Topic open = topic(null);
        List<Topic> topics = List.of(june, july, august, open);
        Coverage behind = CoverageMath.of(topics, Set.of(june.id()), AUGUST);
        assertThat(behind.due()).isEqualTo(2);
        assertThat(behind.overdue()).isEqualTo(1);
        assertThat(behind.expectedPercent()).isEqualByComparingTo("50.0");
        assertThat(behind.behind()).isTrue();

        Coverage onTrack = CoverageMath.of(topics, Set.of(june.id(), july.id()), AUGUST);
        assertThat(onTrack.overdue()).isZero();
        assertThat(onTrack.behind()).isFalse();
        // The topic for this month is not overdue yet, and one without a target month never is.
        Coverage ahead = CoverageMath.of(topics, Set.of(june.id(), july.id(), august.id()), AUGUST);
        assertThat(ahead.percent()).isEqualByComparingTo("75.0");
        assertThat(ahead.behind()).isFalse();
    }

    @Test
    void nextMonthTheCurrentTopicBecomesDue() {
        Topic august = topic("2026-08");
        Coverage september = CoverageMath.of(List.of(august), Set.of(), AUGUST.plusMonths(1));
        assertThat(september.due()).isEqualTo(1);
        assertThat(september.behind()).isTrue();
    }

    @Test
    void percentRoundsHalfUp() {
        assertThat(CoverageMath.percent(1, 8)).isEqualByComparingTo("12.5");
        assertThat(CoverageMath.percent(1, 16)).isEqualByComparingTo("6.3");
        assertThat(CoverageMath.percent(0, 5)).isEqualByComparingTo("0.0");
        assertThat(CoverageMath.percent(3, 0)).isEqualByComparingTo("0.0");
    }
}
