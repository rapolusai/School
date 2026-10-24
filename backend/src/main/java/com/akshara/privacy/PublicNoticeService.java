package com.akshara.privacy;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantStatus;
import com.akshara.platform.TenantView;
import com.akshara.privacy.PrivacyViews.PublicNotice;
import com.akshara.shared.ApiException;
import com.akshara.shared.RateLimitStore;
import com.akshara.shared.TenantContext;

/**
 * A school's privacy notice for anyone, without signing in. The school is looked up from the code in the address; an
 * unknown or suspended school, or one that has not published a notice, is "not found". Loads are limited per client IP
 * in a window; the counts live in PostgreSQL ({@link RateLimitStore}) like the other public limits, so the limit
 * holds across API instances.
 */
@Service
class PublicNoticeService {

    private static final int MAX_CODE_LENGTH = 40;

    private final TenantDirectory tenants;
    private final PrivacyNoticeService notices;
    private final int maxPerIp;
    private final Duration window;
    private final Clock clock = Clock.systemUTC();
    private final RateLimitStore store;

    PublicNoticeService(TenantDirectory tenants, PrivacyNoticeService notices,
            @Value("${akshara.privacy.public-notice-per-ip:120}") int maxPerIp,
            @Value("${akshara.privacy.public-notice-window:PT1H}") Duration window, RateLimitStore store) {
        this.tenants = tenants;
        this.store = store;
        this.notices = notices;
        this.maxPerIp = maxPerIp;
        this.window = window;
    }

    PublicNotice notice(String schoolCode, Integer version, String clientIp) {
        take(clientIp == null ? "unknown" : clientIp);
        if (schoolCode == null || schoolCode.isBlank() || schoolCode.length() > MAX_CODE_LENGTH) {
            throw ApiException.notFound("School");
        }
        TenantView school = tenants.findByCode(schoolCode)
                .filter(t -> t.status() != TenantStatus.SUSPENDED)
                .orElseThrow(() -> ApiException.notFound("School"));
        // The notice service opens its transaction inside runAs, so the connection is stamped with this school.
        return TenantContext.runAs(school.id(), () -> notices.publicNotice(school.name(), school.code(), version));
    }

    private void take(String ip) {
        if (store.hit("privacy-notice|" + ip, window, clock.instant()) > maxPerIp) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                    "The privacy notice was opened too many times. Wait a while and try again.");
        }
    }
}
