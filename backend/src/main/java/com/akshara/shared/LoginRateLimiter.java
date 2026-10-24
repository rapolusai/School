package com.akshara.shared;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Counts failed sign-ins per key (IP + school + email, or IP + email for the Super Admin) in a window. The counts live
 * in PostgreSQL through {@link RateLimitStore}, so the limit holds across every API instance. After
 * {@code maxFailures} failures the key gets 429 until the window that started with its first failure is over; a
 * successful sign-in clears it.
 */
@Component
public class LoginRateLimiter {

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final RateLimitStore store;

    @Autowired
    public LoginRateLimiter(AksharaProperties properties, RateLimitStore store) {
        this(properties.auth().maxFailedLogins(), properties.auth().failedLoginWindow(), Clock.systemUTC(), store);
    }

    LoginRateLimiter(int maxFailures, Duration window, Clock clock, RateLimitStore store) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
        this.store = store;
    }

    /** Throws 429 when the key has used up its failed attempts. */
    public void check(String key) {
        if (store.hits(storeKey(key), clock.instant()) >= maxFailures) {
            throw ApiException.tooManyRequests();
        }
    }

    public void recordFailure(String key) {
        store.hit(storeKey(key), window, clock.instant());
    }

    public void reset(String key) {
        store.clear(storeKey(key));
    }

    private static String storeKey(String key) {
        return "login|" + key;
    }
}
