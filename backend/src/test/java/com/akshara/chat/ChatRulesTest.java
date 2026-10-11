package com.akshara.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ChatRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-12T06:00:00Z");

    private static List<Instant> sends(int count, Duration first, Duration step) {
        List<Instant> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(NOW.minus(first).plus(step.multipliedBy(i)));
        }
        return result;
    }

    @Test
    void underTheLimitMaySend() {
        assertThat(ChatRules.retryAfter(List.of(), NOW)).isEmpty();
        assertThat(ChatRules.retryAfter(sends(ChatRules.MAX_PER_HOUR - 1, Duration.ofMinutes(50),
                Duration.ofMinutes(1)), NOW)).isEmpty();
    }

    @Test
    void atTheLimitWaitsUntilTheOldestLeavesTheHour() {
        // 20 messages, the first 50 minutes ago: wait 10 minutes.
        List<Instant> recent = sends(ChatRules.MAX_PER_HOUR, Duration.ofMinutes(50), Duration.ofMinutes(1));
        assertThat(ChatRules.retryAfter(recent, NOW)).contains(Duration.ofMinutes(10));
        assertThat(ChatRules.minutesToWait(Duration.ofMinutes(10))).isEqualTo(10);
    }

    @Test
    void messagesOlderThanAnHourDoNotCount() {
        List<Instant> recent = new ArrayList<>(sends(10, Duration.ofMinutes(30), Duration.ofMinutes(1)));
        recent.addAll(sends(15, Duration.ofMinutes(120), Duration.ofMinutes(1)));
        assertThat(ChatRules.retryAfter(recent, NOW)).isEmpty();
    }

    @Test
    void theOrderOfSendsDoesNotMatter() {
        List<Instant> recent = new ArrayList<>(sends(ChatRules.MAX_PER_HOUR, Duration.ofMinutes(59),
                Duration.ofSeconds(30)));
        java.util.Collections.reverse(recent);
        assertThat(ChatRules.retryAfter(recent, NOW)).contains(Duration.ofMinutes(1));
    }

    @Test
    void waitsAreRoundedUpToWholeMinutes() {
        assertThat(ChatRules.minutesToWait(Duration.ofSeconds(1))).isEqualTo(1);
        assertThat(ChatRules.minutesToWait(Duration.ofSeconds(61))).isEqualTo(2);
        assertThat(ChatRules.minutesToWait(Duration.ZERO)).isEqualTo(1);
    }

    @Test
    void aMessageCanBeDeletedForTenMinutes() {
        ChatMessage m = new ChatMessage(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), Side.PARENT,
                "Lata Rao", "Hello", 0, NOW);
        assertThat(m.deletableAt(NOW.plus(Duration.ofMinutes(10)))).isTrue();
        assertThat(m.deletableAt(NOW.plus(Duration.ofMinutes(10)).plusSeconds(1))).isFalse();
        m.delete(NOW.plusSeconds(5));
        assertThat(m.deletableAt(NOW.plusSeconds(10))).isFalse();
    }

    @Test
    void erasingClearsTheTextAndTheParentsName() {
        ChatMessage m = new ChatMessage(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), Side.PARENT,
                "Lata Rao", "Call me on 98765 01001", 2, NOW);
        m.erase(NOW);
        assertThat(m.getBody()).isNull();
        assertThat(m.getAttachmentCount()).isZero();
        assertThat(m.getSenderName()).isEqualTo(ChatMessage.ERASED_PARENT);
        assertThat(m.isErased()).isTrue();
    }
}
