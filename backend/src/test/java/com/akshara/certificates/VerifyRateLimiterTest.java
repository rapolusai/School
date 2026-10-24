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
import com.akshara.shared.InMemoryRateLimitStore;

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
        VerifyRateLimiter limiter = new VerifyRateLimiter(3, Duration.ofHours(1), clock,
                new InMemoryRateLimitStore());
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
}
