package com.akshara.shared;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    private Instant now = Instant.parse("2026-10-09T10:00:00Z");

    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    @Test
    void blocksAfterTooManyFailuresAndForgetsThemAfterTheWindow() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(5), clock);
        for (int i = 0; i < 3; i++) {
            limiter.check("k");
            limiter.recordFailure("k");
        }
        assertThatThrownBy(() -> limiter.check("k")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(429);
        assertThatCode(() -> limiter.check("other")).doesNotThrowAnyException();

        now = now.plus(Duration.ofMinutes(6));
        assertThatCode(() -> limiter.check("k")).doesNotThrowAnyException();
    }

    @Test
    void aSuccessfulSignInClearsTheCount() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, Duration.ofMinutes(5), clock);
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        limiter.reset("k");
        assertThatCode(() -> limiter.check("k")).doesNotThrowAnyException();
    }
}
