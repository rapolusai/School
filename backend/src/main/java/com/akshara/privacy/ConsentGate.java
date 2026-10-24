package com.akshara.privacy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.JwtService;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;

/**
 * Enforces parent consent in the API, not only on the web screen. A family sign-in (every role is Parent or Student)
 * whose children at school have not all had essential processing agreed against the school's current privacy notice
 * gets {@code 403} with problem type {@value #TYPE} from every endpoint except the few needed to read and give consent
 * (see {@link #open}), sign out and refresh. Staff, including a teacher who is also a parent, are never checked. A
 * school with no published notice asks nothing, as before.
 */
@Component
class ConsentGate implements HandlerInterceptor {

    /** Problem type the web app routes to the consent screen. */
    static final String TYPE = "urn:akshara:problem:consent-required";

    static final Set<String> FAMILY_ROLES = Set.of("PARENT", "STUDENT");

    private final StudentRoster roster;
    private final PrivacyNoticeRepository notices;
    private final ConsentRecordRepository records;
    private final TransactionTemplate readOnly;

    ConsentGate(StudentRoster roster, PrivacyNoticeRepository notices, ConsentRecordRepository records,
            PlatformTransactionManager transactions) {
        this.roster = roster;
        this.notices = notices;
        this.records = records;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Jwt token = CurrentUser.token().orElse(null);
        if (token == null || CurrentUser.isPlatformAdmin() || TenantContext.current().isEmpty()
                || open(request.getRequestURI())) {
            return true;
        }
        List<String> roles = token.getClaimAsStringList(JwtService.CLAIM_ROLES);
        if (roles == null || roles.isEmpty() || !FAMILY_ROLES.containsAll(roles)) {
            return true;
        }
        if (needsConsent(UUID.fromString(token.getSubject()))) {
            boolean parent = roles.contains("PARENT");
            throw new ApiException(HttpStatus.FORBIDDEN, "Consent needed", parent
                    ? "Accept the school's privacy notice to continue."
                    : "A parent needs to accept the school's privacy notice before you can use the app.",
                    Map.of(), TYPE);
        }
        return true;
    }

    /**
     * Endpoints a family member can use before consent: who they are, their language, and their privacy page (read
     * the notice, give consent, raise a request or grievance). Sign-in, refresh and sign-out need no token at all.
     */
    static boolean open(String path) {
        return path.equals("/api/me") || path.equals("/api/me/preferences") || path.equals("/api/me/privacy")
                || path.startsWith("/api/me/privacy/") || path.startsWith("/api/auth/")
                || path.startsWith("/api/public/");
    }

    /**
     * True when the current school has a published notice and at least one of the sign-in's students at school has no
     * essential consent for its version. Runs as the signed-in school, under row-level security.
     */
    boolean needsConsent(UUID userId) {
        return Boolean.TRUE.equals(readOnly.execute(status -> {
            int version = notices.latestNumber();
            if (version == 0) {
                return false;
            }
            Set<UUID> active = roster.activeStudentIdsOf(userId);
            if (active.isEmpty()) {
                return false;
            }
            return !Set.copyOf(records.essentialGivenAmong(version, active)).containsAll(active);
        }));
    }

    /** Puts the gate in front of every API endpoint. */
    @Configuration(proxyBeanMethods = false)
    static class Registration implements WebMvcConfigurer {

        private final ConsentGate gate;

        Registration(ConsentGate gate) {
            this.gate = gate;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(gate).addPathPatterns("/api/**");
        }
    }
}
