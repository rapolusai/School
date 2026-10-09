package com.akshara.admissions;

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
 * Limits the public enquiry form in a sliding window: page loads and enquiries per client IP, and enquiries per
 * school. In memory like the sign-in limiter; moves to Redis when the API runs as more than one task.
 */
@Component
class EnquiryRateLimiter {

    /** How often (in calls) keys whose window has passed are dropped, so the map does not grow without bound. */
    private static final int CLEANUP_EVERY = 500;

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final int maxInfoPerIp;
    private final int maxEnquiriesPerIp;
    private final int maxEnquiriesPerSchool;
    private final Duration window;
    private final Clock clock;

    @Autowired
    EnquiryRateLimiter(
            @Value("${akshara.admissions.public-info-per-ip:120}") int maxInfoPerIp,
            @Value("${akshara.admissions.enquiries-per-ip:20}") int maxEnquiriesPerIp,
            @Value("${akshara.admissions.enquiries-per-school:100}") int maxEnquiriesPerSchool,
            @Value("${akshara.admissions.enquiry-window:PT1H}") Duration window) {
        this(maxInfoPerIp, maxEnquiriesPerIp, maxEnquiriesPerSchool, window, Clock.systemUTC());
    }

    EnquiryRateLimiter(int maxInfoPerIp, int maxEnquiriesPerIp, int maxEnquiriesPerSchool, Duration window,
            Clock clock) {
        this.maxInfoPerIp = maxInfoPerIp;
        this.maxEnquiriesPerIp = maxEnquiriesPerIp;
        this.maxEnquiriesPerSchool = maxEnquiriesPerSchool;
        this.window = window;
        this.clock = clock;
    }

    /** Counts a load of a school's enquiry page from this IP; throws 429 when the IP has used up its loads. */
    void takeInfo(String ip) {
        take("info|" + ip, maxInfoPerIp);
    }

    /** Counts an enquiry sent from this IP; throws 429 when the IP has used up its enquiries. */
    void takeEnquiryFromIp(String ip) {
        take("ip|" + ip, maxEnquiriesPerIp);
    }

    /** Counts an enquiry sent to this school; throws 429 when the school has received too many. */
    void takeEnquiryForSchool(UUID schoolId) {
        take("school|" + schoolId, maxEnquiriesPerSchool);
    }

    private void take(String key, int max) {
        Instant now = clock.instant();
        boolean[] allowed = {false};
        // compute() runs atomically per key, so concurrent requests never over-count or lose an attempt.
        hits.compute(key, (k, attempts) -> {
            Deque<Instant> list = attempts == null ? new ArrayDeque<>() : attempts;
            prune(list, now);
            if (list.size() < max) {
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
                    "Too many enquiries have been sent. Wait a while and try again.");
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
