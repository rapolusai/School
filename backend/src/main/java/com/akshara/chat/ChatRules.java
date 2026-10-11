package com.akshara.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/** The limits of the messages and PTM area, in one place (docs/api/phase-2-chat.md). Pure. */
public final class ChatRules {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** The longest message text. */
    public static final int MAX_LENGTH = 2000;
    /** Attachments on one message (same types and 5 MB limit as homework, checked by the files module). */
    public static final int MAX_ATTACHMENTS = 3;
    /** How many messages one person may send in {@link #RATE_WINDOW}. */
    public static final int MAX_PER_HOUR = 20;
    public static final Duration RATE_WINDOW = Duration.ofHours(1);
    /** The sender may delete a message for both sides this long after sending it. */
    public static final Duration DELETE_WINDOW = Duration.ofMinutes(10);
    /** Messages returned when a conversation is opened; older ones come with {@code before}. */
    public static final int PAGE_MESSAGES = 50;
    /** The longest a teacher can be away for, in days. */
    public static final int MAX_AWAY_DAYS = 90;
    public static final int MAX_REASON = 500;
    public static final int MAX_NOTE = 1000;

    private ChatRules() {
    }

    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    /**
     * Whether one more message may be sent now, given when the sender's recent messages were sent (any order; only
     * those inside the window count). Empty when it may; otherwise how long until the oldest one leaves the window.
     */
    public static Optional<Duration> retryAfter(Collection<Instant> recentSends, Instant now) {
        Instant windowStart = now.minus(RATE_WINDOW);
        long inWindow = recentSends.stream().filter(t -> t.isAfter(windowStart)).count();
        if (inWindow < MAX_PER_HOUR) {
            return Optional.empty();
        }
        Instant oldest = recentSends.stream().filter(t -> t.isAfter(windowStart)).min(Comparator.naturalOrder())
                .orElse(now);
        Duration wait = Duration.between(now, oldest.plus(RATE_WINDOW));
        return Optional.of(wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait);
    }

    /** Whole minutes to wait, at least one, for a message to the sender. */
    public static long minutesToWait(Duration wait) {
        long minutes = (wait.getSeconds() + 59) / 60;
        return Math.max(1, minutes);
    }
}
