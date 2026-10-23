package com.akshara.assist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.akshara.assist.AssistSettings.Weights;
import com.akshara.assist.AssistViews.Reason;
import com.akshara.assist.RiskScorer.Score;
import com.akshara.assist.RiskScorer.Signals;

/** The rule-based score: each signal's contribution is listed and explained; weights are the school's. */
class RiskScorerTest {

    private static final Weights W = Weights.DEFAULTS;

    @Test
    void aStudentWithNoWarningSignsIsNotListed() {
        Score score = RiskScorer.score(new Signals(96.0, 94.0, 82.0, 80.0, "Unit test 1", 0, 0, 95.0), W);
        assertThat(score.score()).isZero();
        assertThat(score.level()).isNull();
        assertThat(score.reasons()).isEmpty();
    }

    @Test
    void everyContributionIsExplainedAndAddsUp() {
        Score score = RiskScorer.score(new Signals(55.0, 72.0, 30.0, 52.0, "Half-yearly", 1_250_000, 100, 35.0), W);
        assertThat(score.reasons()).extracting(Reason::signal).containsExactly(RiskScorer.ATTENDANCE_30,
                RiskScorer.ATTENDANCE_90, RiskScorer.MARKS_DROP, RiskScorer.FEE_DUES, RiskScorer.HOMEWORK);
        assertThat(score.reasons()).extracting(Reason::points).containsExactly(25, 9, 25, 10, 25);
        assertThat(score.reasons()).extracting(Reason::detail).containsExactly(
                "Attendance 55% in the last 30 days",
                "Attendance 72% in the last 90 days",
                "Marks fell by 22 points between the last two exams (52% to 30%)",
                "Fees of ₹12,500 overdue for 100 days",
                "Handed in 35% of online homework in the last 30 days");
        assertThat(score.score()).isEqualTo(94);
        assertThat(score.level()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void lowMarksWithoutADropAndMissingSignalsAreHandled() {
        Score score = RiskScorer.score(new Signals(null, null, 38.0, null, "Unit test 2", 0, 0, null), W);
        assertThat(score.reasons()).hasSize(1);
        assertThat(score.reasons().getFirst().signal()).isEqualTo(RiskScorer.MARKS_LOW);
        assertThat(score.reasons().getFirst().detail()).isEqualTo("38% in Unit test 2");
        assertThat(score.score()).isEqualTo(15);
        assertThat(score.level()).isNull();
        // Below the threshold until the school lowers it.
        assertThat(RiskScorer.score(new Signals(null, null, 38.0, null, null, 0, 0, null),
                new Weights(25, 15, 25, 10, 25, 10)).level()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void weightsAreTheSchoolsAndTheScoreIsCappedAtAHundred() {
        Signals bad = new Signals(50.0, 50.0, 20.0, 60.0, null, 100_000, 120, 10.0);
        assertThat(RiskScorer.score(bad, new Weights(100, 100, 100, 100, 100, 30)).score()).isEqualTo(100);
        Score noHomework = RiskScorer.score(bad, new Weights(25, 15, 25, 10, 0, 30));
        assertThat(noHomework.reasons()).extracting(Reason::signal).doesNotContain(RiskScorer.HOMEWORK);
        assertThat(noHomework.score()).isEqualTo(75);
        assertThat(RiskScorer.score(bad, new Weights(0, 0, 0, 10, 0, 30)).level()).isNull();
        // Recent dues do not count until they are more than 15 days overdue.
        assertThat(RiskScorer.score(new Signals(null, null, null, null, null, 50_000, 10, null), W).score())
                .isZero();
    }
}
