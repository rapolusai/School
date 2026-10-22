package com.akshara.certificates;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.akshara.shared.ApiException;

/**
 * Limits the public verification page per client IP in a sliding window, like the public enquiry form, so nobody
 * can walk through tokens. In memory like the sign-in limiter; moves to Redis when the API runs as more than one
 * task.
 */
@Component
class VerifyRateLimiter {

    private static final int CLEANUP_EVERY = 500;

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final int maxPerIp;
    private final Duration window;
    private final Clock clock;

    @Autowired
    VerifyRateLimiter(
            @Value("${akshara.certificates.verify-per-ip:60}") int maxPerIp,
            @Value("${akshara.certificates.verify-window:PT1H}") Duration window) {
        this(maxPerIp, window, Clock.systemUTC());
    }

    VerifyRateLimiter(int maxPerIp, Duration window, Clock clock) {
        this.maxPerIp = maxPerIp;
        this.window = window;
        this.clock = clock;
    }

    /** Counts a verification from this IP; throws 429 when the IP has used up its lookups. */
    void take(String ip) {
        Instant now = clock.instant();
        boolean[] allowed = {false};
        hits.compute(ip, (k, attempts) -> {
            Deque<Instant> list = attempts == null ? new ArrayDeque<>() : attempts;
            prune(list, now);
            if (list.size() < maxPerIp) {
                list.addLast(now);
                allowed[0] = true;
            }
            return list;
        });
        if (calls.incrementAndGet() % CLEANUP_EVERY == 0) {
            cleanUp(now);
        }
        if (!allowed[0]) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                    "Too many verifications from this connection. Wait a while and try again.");
        }
    }

    private void cleanUp(Instant now) {
        for (String key : hits.keySet()) {
            hits.computeIfPresent(key, (k, attempts) -> {
                prune(attempts, now);
                return attempts.isEmpty() ? null : attempts;
            });
        }
    }

    private void prune(Deque<Instant> attempts, Instant now) {
        Instant cutoff = now.minus(window);
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
            attempts.removeFirst();
        }
    }

    int trackedKeys() {
        return hits.size();
    }
}
