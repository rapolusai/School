package com.akshara.admissions;

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
 * Limits the public enquiry form in a window: page loads and enquiries per client IP, and enquiries per school. The
 * counts live in PostgreSQL ({@link RateLimitStore}), so the limits hold across API instances.
 */
@Component
class EnquiryRateLimiter {

    private final int maxInfoPerIp;
    private final int maxEnquiriesPerIp;
    private final int maxEnquiriesPerSchool;
    private final Duration window;
    private final Clock clock;
    private final RateLimitStore store;

    @Autowired
    EnquiryRateLimiter(
            @Value("${akshara.admissions.public-info-per-ip:120}") int maxInfoPerIp,
            @Value("${akshara.admissions.enquiries-per-ip:20}") int maxEnquiriesPerIp,
            @Value("${akshara.admissions.enquiries-per-school:100}") int maxEnquiriesPerSchool,
            @Value("${akshara.admissions.enquiry-window:PT1H}") Duration window,
            RateLimitStore store) {
        this(maxInfoPerIp, maxEnquiriesPerIp, maxEnquiriesPerSchool, window, Clock.systemUTC(), store);
    }

    EnquiryRateLimiter(int maxInfoPerIp, int maxEnquiriesPerIp, int maxEnquiriesPerSchool, Duration window,
            Clock clock, RateLimitStore store) {
        this.maxInfoPerIp = maxInfoPerIp;
        this.maxEnquiriesPerIp = maxEnquiriesPerIp;
        this.maxEnquiriesPerSchool = maxEnquiriesPerSchool;
        this.window = window;
        this.clock = clock;
        this.store = store;
    }

    /** Counts a load of a school's enquiry page from this IP; throws 429 when the IP has used up its loads. */
    void takeInfo(String ip) {
        take("enquiry-info|" + ip, maxInfoPerIp);
    }

    /** Counts an enquiry sent from this IP; throws 429 when the IP has used up its enquiries. */
    void takeEnquiryFromIp(String ip) {
        take("enquiry-ip|" + ip, maxEnquiriesPerIp);
    }

    /** Counts an enquiry sent to this school; throws 429 when the school has received too many. */
    void takeEnquiryForSchool(UUID schoolId) {
        take("enquiry-school|" + schoolId, maxEnquiriesPerSchool);
    }

    private void take(String key, int max) {
        if (store.hit(key, window, clock.instant()) > max) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                    "Too many enquiries have been sent. Wait a while and try again.");
        }
    }
}
