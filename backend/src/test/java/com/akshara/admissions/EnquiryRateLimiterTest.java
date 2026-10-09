package com.akshara.admissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.shared.ApiException;

class EnquiryRateLimiterTest {

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
    void limitsEnquiriesPerIpAndForgetsThemAfterTheWindow() {
        EnquiryRateLimiter limiter = new EnquiryRateLimiter(10, 3, 100, Duration.ofHours(1), clock);
        for (int i = 0; i < 3; i++) {
            limiter.takeEnquiryFromIp("10.0.0.1");
        }
        assertThatThrownBy(() -> limiter.takeEnquiryFromIp("10.0.0.1")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(429);
        assertThatCode(() -> limiter.takeEnquiryFromIp("10.0.0.2")).doesNotThrowAnyException();
        // Page loads are counted separately from enquiries.
        assertThatCode(() -> limiter.takeInfo("10.0.0.1")).doesNotThrowAnyException();

        now = now.plus(Duration.ofMinutes(61));
        assertThatCode(() -> limiter.takeEnquiryFromIp("10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void limitsEnquiriesPerSchoolWhateverTheIp() {
        EnquiryRateLimiter limiter = new EnquiryRateLimiter(10, 10, 2, Duration.ofHours(1), clock);
        UUID school = UUID.randomUUID();
        limiter.takeEnquiryForSchool(school);
        limiter.takeEnquiryForSchool(school);
        assertThatThrownBy(() -> limiter.takeEnquiryForSchool(school)).isInstanceOf(ApiException.class);
        assertThatCode(() -> limiter.takeEnquiryForSchool(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void refusedAttemptsDoNotExtendTheWait() {
        EnquiryRateLimiter limiter = new EnquiryRateLimiter(1, 10, 10, Duration.ofMinutes(10), clock);
        limiter.takeInfo("ip");
        now = now.plus(Duration.ofMinutes(9));
        assertThatThrownBy(() -> limiter.takeInfo("ip")).isInstanceOf(ApiException.class);
        now = now.plus(Duration.ofMinutes(2));
        assertThatCode(() -> limiter.takeInfo("ip")).doesNotThrowAnyException();
    }

    @Test
    void forgetsIdleKeys() {
        EnquiryRateLimiter limiter = new EnquiryRateLimiter(1000, 1000, 1000, Duration.ofMinutes(1), clock);
        for (int i = 0; i < 499; i++) {
            limiter.takeInfo("ip-" + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(499);
        now = now.plus(Duration.ofMinutes(2));
        // Every 500th call drops keys whose window has passed.
        limiter.takeInfo("fresh");
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }
}
