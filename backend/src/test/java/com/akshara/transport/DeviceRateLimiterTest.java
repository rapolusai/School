package com.akshara.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.akshara.shared.ApiException;

/** Positions per vehicle per minute, wrong device tokens per IP, and the CSV downloads' format. */
class DeviceRateLimiterTest {

    /** A clock the test moves by hand. */
    static final class MovingClock extends Clock {

        Instant now = Instant.parse("2026-10-12T02:30:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void eachVehicleSendsAtMostItsPositionsAMinute() {
        MovingClock clock = new MovingClock();
        DeviceRateLimiter limiter = new DeviceRateLimiter(3, 5, Duration.ofMinutes(10), clock);
        UUID bus = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.takePosition(bus);
        }
        assertThatThrownBy(() -> limiter.takePosition(bus)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        // Another vehicle has its own allowance.
        limiter.takePosition(other);
        // The window slides: a minute after the first positions, the bus may send again.
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        limiter.takePosition(bus);
    }

    @Test
    void wrongTokensFromOneAddressAreLimited() {
        MovingClock clock = new MovingClock();
        DeviceRateLimiter limiter = new DeviceRateLimiter(12, 2, Duration.ofMinutes(10), clock);
        limiter.checkFailures("10.0.0.1");
        limiter.recordFailure("10.0.0.1");
        limiter.recordFailure("10.0.0.1");
        assertThatThrownBy(() -> limiter.checkFailures("10.0.0.1")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        // Checking alone never counts, and other addresses are not affected.
        limiter.checkFailures("10.0.0.2");
        limiter.checkFailures("10.0.0.2");
        limiter.checkFailures("10.0.0.2");
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        limiter.checkFailures("10.0.0.1");
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void csvFilesOpenInExcelAndNeverRunFormulas() {
        TransportCsv csv = new TransportCsv("Name", "Amount", "When", "Note");
        csv.row("=HYPERLINK(\"x\")", new TransportCsv.Rupees(1_234_50), LocalTime.of(7, 5), "a, \"b\"");
        csv.row(null, new TransportCsv.Rupees(-5), LocalDate.of(2026, 10, 12), "आशा");
        String text = new String(csv.bytes(), StandardCharsets.UTF_8);
        assertThat(csv.bytes()[0]).isEqualTo((byte) 0xEF);
        assertThat(text).isEqualTo("﻿Name,Amount,When,Note\r\n"
                + "\"'=HYPERLINK(\"\"x\"\")\",1234.50,07:05,\"a, \"\"b\"\"\"\r\n"
                + ",-0.05,2026-10-12,आशा\r\n");
    }
}
