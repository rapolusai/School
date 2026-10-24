package com.akshara.transport;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.akshara.shared.ApiException;
import com.akshara.shared.RateLimitStore;

/**
 * Limits positions in a window: per vehicle (a GPS device or a phone sending far more often than every few seconds)
 * and wrong device tokens per client IP (guessing). The counts live in PostgreSQL ({@link RateLimitStore}), so the
 * limits hold across API instances.
 */
@Component
class DeviceRateLimiter {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final int positionsPerMinute;
    private final int failuresPerIp;
    private final Duration failureWindow;
    private final Clock clock;
    private final RateLimitStore store;

    @Autowired
    DeviceRateLimiter(
            @Value("${akshara.transport.positions-per-minute:12}") int positionsPerMinute,
            @Value("${akshara.transport.device-failures-per-ip:20}") int failuresPerIp,
            @Value("${akshara.transport.device-failure-window:PT10M}") Duration failureWindow,
            RateLimitStore store) {
        this(positionsPerMinute, failuresPerIp, failureWindow, Clock.systemUTC(), store);
    }

    DeviceRateLimiter(int positionsPerMinute, int failuresPerIp, Duration failureWindow, Clock clock,
            RateLimitStore store) {
        this.positionsPerMinute = positionsPerMinute;
        this.failuresPerIp = failuresPerIp;
        this.failureWindow = failureWindow;
        this.clock = clock;
        this.store = store;
    }

    /** Counts a position for the trip or vehicle; 429 once it has sent its positions for this minute. */
    void takePosition(UUID id) {
        if (store.hit("position|" + id, MINUTE, clock.instant()) > positionsPerMinute) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many positions",
                    "Positions are taken at most " + positionsPerMinute + " times a minute. Send less often.");
        }
    }

    /** 429 when the IP has sent too many wrong device tokens recently. Checking never counts. */
    void checkFailures(String ip) {
        if (store.hits(failureKey(ip), clock.instant()) >= failuresPerIp) {
            throw ApiException.tooManyRequests();
        }
    }

    void recordFailure(String ip) {
        store.hit(failureKey(ip), failureWindow, clock.instant());
    }

    private static String failureKey(String ip) {
        return "device-failure|" + ip;
    }
}
