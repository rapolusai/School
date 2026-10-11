package com.akshara.transport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.akshara.shared.ApiException;

/**
 * Limits positions in a sliding window: per vehicle (a GPS device or a phone sending far more often than every few
 * seconds) and wrong device tokens per client IP (guessing). In memory like the sign-in limiter; moves to Redis when
 * the API runs as more than one task.
 */
@Component
class DeviceRateLimiter {

    /** How often (in calls) keys whose window has passed are dropped, so the map does not grow without bound. */
    private static final int CLEANUP_EVERY = 500;

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final int positionsPerMinute;
    private final int failuresPerIp;
    private final Duration failureWindow;
    private final Clock clock;

    @Autowired
    DeviceRateLimiter(
            @Value("${akshara.transport.positions-per-minute:12}") int positionsPerMinute,
            @Value("${akshara.transport.device-failures-per-ip:20}") int failuresPerIp,
            @Value("${akshara.transport.device-failure-window:PT10M}") Duration failureWindow) {
        this(positionsPerMinute, failuresPerIp, failureWindow, Clock.systemUTC());
    }

    DeviceRateLimiter(int positionsPerMinute, int failuresPerIp, Duration failureWindow, Clock clock) {
        this.positionsPerMinute = positionsPerMinute;
        this.failuresPerIp = failuresPerIp;
        this.failureWindow = failureWindow;
        this.clock = clock;
    }

    /** Counts a position for the trip or vehicle; 429 once it has sent its positions for this minute. */
    void takePosition(UUID id) {
        if (!take("position|" + id, positionsPerMinute, Duration.ofMinutes(1), true)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many positions",
                    "Positions are taken at most " + positionsPerMinute + " times a minute. Send less often.");
        }
    }

    /** 429 when the IP has sent too many wrong device tokens recently. */
    void checkFailures(String ip) {
        if (!take("failure|" + ip, failuresPerIp, failureWindow, false)) {
            throw ApiException.tooManyRequests();
        }
    }

    void recordFailure(String ip) {
        take("failure|" + ip, Integer.MAX_VALUE, failureWindow, true);
    }

    /**
     * Whether the key has room under {@code max} in the window; when {@code count} is true the call is counted (only
     * if it is allowed).
     */
    private boolean take(String key, int max, Duration window, boolean count) {
        Instant now = clock.instant();
        boolean[] allowed = {false};
        // compute() runs atomically per key, so concurrent requests never over-count or lose an attempt.
        hits.compute(key, (k, attempts) -> {
            Deque<Instant> list = attempts == null ? new ArrayDeque<>() : attempts;
            prune(list, now, window);
            if (list.size() < max) {
                if (count) {
                    list.addLast(now);
                }
                allowed[0] = true;
            }
            return list.isEmpty() ? null : list;
        });
        if (calls.incrementAndGet() % CLEANUP_EVERY == 0) {
            cleanUp(now);
        }
        return allowed[0];
    }

    private void cleanUp(Instant now) {
        Duration longest = failureWindow.compareTo(Duration.ofMinutes(1)) > 0 ? failureWindow : Duration.ofMinutes(1);
        for (String key : hits.keySet()) {
            hits.computeIfPresent(key, (k, attempts) -> {
                prune(attempts, now, longest);
                return attempts.isEmpty() ? null : attempts;
            });
        }
    }

    private static void prune(Deque<Instant> attempts, Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
            attempts.removeFirst();
        }
    }

    int trackedKeys() {
        return hits.size();
    }
}
