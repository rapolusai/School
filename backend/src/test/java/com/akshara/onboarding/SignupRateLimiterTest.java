package com.akshara.onboarding;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.akshara.shared.ApiException;
import com.akshara.shared.InMemoryRateLimitStore;

class SignupRateLimiterTest {

    private Instant now = Instant.parse("2026-10-30T10:00:00Z");

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
    void limitsSignupsPerIpAndForgetsThemAfterTheWindow() {
        SignupRateLimiter limiter = new SignupRateLimiter(3, 100, Duration.ofHours(1), clock,
                new InMemoryRateLimitStore());
        for (int i = 0; i < 3; i++) {
            limiter.take("10.0.0.1");
        }
        assertThatThrownBy(() -> limiter.take("10.0.0.1")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(429);
        assertThatCode(() -> limiter.take("10.0.0.2")).doesNotThrowAnyException();

        now = now.plus(Duration.ofMinutes(61));
        assertThatCode(() -> limiter.take("10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void limitsSignupsInAllWhateverTheIp() {
        SignupRateLimiter limiter = new SignupRateLimiter(10, 2, Duration.ofHours(1), clock,
                new InMemoryRateLimitStore());
        limiter.take("10.0.0.1");
        limiter.take("10.0.0.2");
        assertThatThrownBy(() -> limiter.take("10.0.0.3")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(429);
    }

    @Test
    void anIpOverItsLimitDoesNotUseUpEveryoneElsesSignups() {
        SignupRateLimiter limiter = new SignupRateLimiter(1, 3, Duration.ofHours(1), clock,
                new InMemoryRateLimitStore());
        limiter.take("10.0.0.1");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limiter.take("10.0.0.1")).isInstanceOf(ApiException.class);
        }
        assertThatCode(() -> limiter.take("10.0.0.2")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.take("10.0.0.3")).doesNotThrowAnyException();
    }
}
