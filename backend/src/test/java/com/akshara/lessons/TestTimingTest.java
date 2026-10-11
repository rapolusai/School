package com.akshara.lessons;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.akshara.lessons.TestTiming.Window;

/** The clock rules of an online test: the window, an attempt's deadline, the grace and the time left. */
class TestTimingTest {

    static final Instant OPENS = Instant.parse("2026-07-06T03:30:00Z");
    static final Instant CLOSES = Instant.parse("2026-07-06T11:30:00Z");

    @Test
    void aTestIsOpenFromItsStartUntilJustBeforeItsClose() {
        assertThat(TestTiming.window(OPENS, CLOSES, OPENS.minusSeconds(1))).isEqualTo(Window.UPCOMING);
        assertThat(TestTiming.window(OPENS, CLOSES, OPENS)).isEqualTo(Window.OPEN);
        assertThat(TestTiming.window(OPENS, CLOSES, CLOSES.minusSeconds(1))).isEqualTo(Window.OPEN);
        assertThat(TestTiming.window(OPENS, CLOSES, CLOSES)).isEqualTo(Window.CLOSED);
        assertThat(TestTiming.window(OPENS, CLOSES, CLOSES.plus(Duration.ofDays(1)))).isEqualTo(Window.CLOSED);
    }

    @Test
    void anAttemptLastsTheDurationFromItsStart() {
        Instant start = OPENS.plus(Duration.ofHours(1));
        assertThat(TestTiming.deadline(start, 20, CLOSES)).isEqualTo(start.plus(Duration.ofMinutes(20)));
    }

    @Test
    void anAttemptStartedLateEndsWhenTheTestCloses() {
        Instant start = CLOSES.minus(Duration.ofMinutes(5));
        assertThat(TestTiming.deadline(start, 20, CLOSES)).isEqualTo(CLOSES);
        // Exactly the duration before the close: both are the same instant.
        assertThat(TestTiming.deadline(CLOSES.minus(Duration.ofMinutes(20)), 20, CLOSES)).isEqualTo(CLOSES);
    }

    @Test
    void closingATestEarlyEndsAttemptsInProgress() {
        Instant deadline = OPENS.plus(Duration.ofMinutes(50));
        Instant closedNow = OPENS.plus(Duration.ofMinutes(30));
        assertThat(TestTiming.effectiveDeadline(deadline, closedNow)).isEqualTo(closedNow);
        assertThat(TestTiming.effectiveDeadline(deadline, CLOSES)).isEqualTo(deadline);
    }

    @Test
    void answersAreTakenUntilTheDeadlinePlusTheGrace() {
        Instant deadline = OPENS.plus(Duration.ofMinutes(20));
        assertThat(TestTiming.GRACE).isEqualTo(Duration.ofSeconds(10));
        assertThat(TestTiming.acceptsAnswers(deadline, deadline.minusSeconds(1))).isTrue();
        assertThat(TestTiming.acceptsAnswers(deadline, deadline)).isTrue();
        assertThat(TestTiming.acceptsAnswers(deadline, deadline.plus(TestTiming.GRACE))).isTrue();
        assertThat(TestTiming.acceptsAnswers(deadline, deadline.plus(TestTiming.GRACE).plusMillis(1))).isFalse();
        assertThat(TestTiming.expired(deadline, deadline.plus(TestTiming.GRACE))).isFalse();
        assertThat(TestTiming.expired(deadline, deadline.plus(Duration.ofMinutes(1)))).isTrue();
    }

    @Test
    void secondsLeftCountDownToZeroAndStayThere() {
        Instant deadline = OPENS.plus(Duration.ofMinutes(20));
        assertThat(TestTiming.secondsLeft(deadline, OPENS)).isEqualTo(1200);
        assertThat(TestTiming.secondsLeft(deadline, deadline.minusMillis(1500))).isEqualTo(1);
        assertThat(TestTiming.secondsLeft(deadline, deadline)).isZero();
        assertThat(TestTiming.secondsLeft(deadline, deadline.plusSeconds(30))).isZero();
    }
}
