package com.akshara.certificates;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.akshara.shared.ApiException;
import com.akshara.shared.RateLimitStore;

/**
 * Limits the public verification page per client IP in a window, like the public enquiry form, so nobody can walk
 * through tokens. The counts live in PostgreSQL ({@link RateLimitStore}), so the limit holds across API instances.
 */
@Component
class VerifyRateLimiter {

    private final int maxPerIp;
    private final Duration window;
    private final Clock clock;
    private final RateLimitStore store;

    @Autowired
    VerifyRateLimiter(
            @Value("${akshara.certificates.verify-per-ip:60}") int maxPerIp,
            @Value("${akshara.certificates.verify-window:PT1H}") Duration window,
            RateLimitStore store) {
        this(maxPerIp, window, Clock.systemUTC(), store);
    }

    VerifyRateLimiter(int maxPerIp, Duration window, Clock clock, RateLimitStore store) {
        this.maxPerIp = maxPerIp;
        this.window = window;
        this.clock = clock;
        this.store = store;
    }

    /** Counts a verification from this IP; throws 429 when the IP has used up its lookups. */
    void take(String ip) {
        if (store.hit("verify|" + ip, window, clock.instant()) > maxPerIp) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                    "Too many verifications from this connection. Wait a while and try again.");
        }
    }
}
