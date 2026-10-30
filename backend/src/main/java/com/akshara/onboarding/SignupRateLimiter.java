package com.akshara.onboarding;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.akshara.shared.ApiException;
import com.akshara.shared.RateLimitStore;

/**
 * Limits self-service sign-up in a window: schools created from one client IP, and schools created in all. Each
 * sign-up seeds a whole school, so a script must not be able to create thousands. The counts live in PostgreSQL
 * ({@link RateLimitStore}), so the limits hold across API instances.
 */
@Component
class SignupRateLimiter {

    private final int maxPerIp;
    private final int maxInAll;
    private final Duration window;
    private final Clock clock;
    private final RateLimitStore store;

    @Autowired
    SignupRateLimiter(
            @Value("${akshara.signup.per-ip:5}") int maxPerIp,
            @Value("${akshara.signup.in-all:100}") int maxInAll,
            @Value("${akshara.signup.window:PT1H}") Duration window,
            RateLimitStore store) {
        this(maxPerIp, maxInAll, window, Clock.systemUTC(), store);
    }

    SignupRateLimiter(int maxPerIp, int maxInAll, Duration window, Clock clock, RateLimitStore store) {
        this.maxPerIp = maxPerIp;
        this.maxInAll = maxInAll;
        this.window = window;
        this.clock = clock;
        this.store = store;
    }

    /** Counts a sign-up from this IP; throws 429 when the IP, or everyone together, has used up the window. */
    void take(String ip) {
        if (store.hit("signup-ip|" + ip, window, clock.instant()) > maxPerIp) {
            throw tooMany();
        }
        if (store.hit("signup-all", window, clock.instant()) > maxInAll) {
            throw tooMany();
        }
    }

    private static ApiException tooMany() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                "Too many schools have signed up from here recently. Wait an hour and try again, or contact us.");
    }
}
