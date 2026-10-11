package com.akshara.lessons;

import java.time.Duration;
import java.time.Instant;

/**
 * The clock rules of an online test, kept pure so they are easy to test. A test is open from {@code opensAt} until
 * just before {@code closesAt}. An attempt lasts the test's duration from its start, and ends at the test's close if
 * that comes first (closing a test early ends attempts in progress too). The server takes answers until the deadline
 * plus a short grace for the network; after that the attempt is submitted automatically with what was saved.
 */
final class TestTiming {

    /** Allowance for a last answer that left the phone before the deadline but arrived just after it. */
    static final Duration GRACE = Duration.ofSeconds(10);

    /** Where a test stands for students. */
    enum Window {
        UPCOMING,
        OPEN,
        CLOSED
    }

    private TestTiming() {
    }

    static Window window(Instant opensAt, Instant closesAt, Instant now) {
        if (now.isBefore(opensAt)) {
            return Window.UPCOMING;
        }
        return now.isBefore(closesAt) ? Window.OPEN : Window.CLOSED;
    }

    /** The deadline of an attempt started at {@code startedAt}: the duration later, or the close if sooner. */
    static Instant deadline(Instant startedAt, int durationMinutes, Instant closesAt) {
        Instant byDuration = startedAt.plus(Duration.ofMinutes(durationMinutes));
        return byDuration.isBefore(closesAt) ? byDuration : closesAt;
    }

    /** The deadline that applies now: the attempt's own, or the test's close if the test was closed early. */
    static Instant effectiveDeadline(Instant attemptDeadline, Instant closesAt) {
        return closesAt.isBefore(attemptDeadline) ? closesAt : attemptDeadline;
    }

    /** True while answers are still taken (up to the deadline plus the grace). */
    static boolean acceptsAnswers(Instant deadline, Instant now) {
        return !now.isAfter(deadline.plus(GRACE));
    }

    /** True once the attempt is over and should be submitted automatically. */
    static boolean expired(Instant deadline, Instant now) {
        return !acceptsAnswers(deadline, now);
    }

    /** Whole seconds left until the deadline, never below 0. */
    static long secondsLeft(Instant deadline, Instant now) {
        return Math.max(0, Duration.between(now, deadline).getSeconds());
    }
}
