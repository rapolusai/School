package com.akshara.certificates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.akshara.shared.ApiException;

class VerifyRateLimiterTest {

    private Instant now = Instant.parse("2026-10-11T10:00:00Z");

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
    void limitsLookupsPerIpAndForgetsThemAfterTheWindow() {
        VerifyRateLimiter limiter = new VerifyRateLimiter(3, Duration.ofHours(1), clock);
        for (int i = 0; i < 3; i++) {
            limiter.take("10.0.0.1");
        }
        assertThatThrownBy(() -> limiter.take("10.0.0.1")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        // Another IP is not affected.
        limiter.take("10.0.0.2");
        now = now.plus(Duration.ofHours(1)).plusSeconds(1);
        limiter.take("10.0.0.1");
    }

    @Test
    void dropsKeysWhoseWindowPassed() {
        VerifyRateLimiter limiter = new VerifyRateLimiter(1000, Duration.ofMinutes(1), clock);
        for (int i = 0; i < 499; i++) {
            limiter.take("10.0.0." + (i % 250));
        }
        assertThat(limiter.trackedKeys()).isEqualTo(250);
        now = now.plus(Duration.ofMinutes(2));
        limiter.take("10.1.1.1");
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }
}
