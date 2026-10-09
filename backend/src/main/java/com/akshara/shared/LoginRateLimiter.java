package com.akshara.shared;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Counts failed sign-ins per key (IP + school + email) in a sliding window. In-memory for Phase 0;
 * moves to Redis when the API runs as more than one task.
 */
@Component
public class LoginRateLimiter {

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final java.time.Duration window;
    private final Clock clock;

    @Autowired
    public LoginRateLimiter(AksharaProperties properties) {
        this(properties.auth().maxFailedLogins(), properties.auth().failedLoginWindow(), Clock.systemUTC());
    }

    LoginRateLimiter(int maxFailures, java.time.Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    /** Throws 429 when the key has used up its failed attempts. */
    public void check(String key) {
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return;
        }
        synchronized (attempts) {
            prune(attempts);
            if (attempts.size() >= maxFailures) {
                throw ApiException.tooManyRequests();
            }
        }
    }

    public void recordFailure(String key) {
        Deque<Instant> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts);
            attempts.addLast(clock.instant());
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Instant> attempts) {
        Instant cutoff = clock.instant().minus(window);
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
            attempts.removeFirst();
        }
    }
}
