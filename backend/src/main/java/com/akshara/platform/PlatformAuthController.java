package com.akshara.platform;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.audit.AuditService.Actor;
import com.akshara.audit.PlatformAuditService;
import com.akshara.shared.AksharaProperties;
import com.akshara.shared.ApiException;
import com.akshara.shared.ApiExceptionHandler;
import com.akshara.shared.LoginRateLimiter;
import com.akshara.shared.SessionCookies;

/**
 * Super Admin sign-in. Like a school sign-in it answers with a short-lived access token and sets an httpOnly refresh
 * cookie, here named {@code platform_refresh_token} and sent only to {@code /api/platform/auth}, so a reload keeps
 * the Super Admin signed in.
 */
@RestController
@RequestMapping("/api/platform/auth")
public class PlatformAuthController {

    private final PlatformAdminRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;
    private final PlatformSessions sessions;
    private final AksharaProperties properties;
    private final PlatformAuditService audit;
    private final String dummyHash;

    public PlatformAuthController(PlatformAdminRepository admins, PasswordEncoder passwordEncoder,
            LoginRateLimiter rateLimiter, PlatformSessions sessions, AksharaProperties properties,
            PlatformAuditService audit) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.sessions = sessions;
        this.properties = properties;
        this.audit = audit;
        // Checked when the email is unknown, so a wrong email takes as long as a wrong password.
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    public record PlatformLoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody PlatformLoginRequest request,
            HttpServletRequest http) {
        String key = "platform|" + http.getRemoteAddr() + "|" + request.email().toLowerCase();
        // Counted before the slow check, so parallel guesses cannot all slip past the limit.
        rateLimiter.attempt(key);
        PlatformAdmin admin = admins.findByEmail(request.email().trim()).orElse(null);
        boolean ok = passwordEncoder.matches(request.password(), admin == null ? dummyHash : admin.getPasswordHash())
                && admin != null && admin.isActive();
        if (!ok) {
            // Kept although this transaction rolls back: the Super Admin's trail shows every failed attempt.
            String email = request.email().trim().toLowerCase();
            audit.recordNow(admin == null ? new Actor(null, null) : new Actor(admin.getId(), admin.getName()),
                    "platform.login_failed", null, "platform_admin", admin == null ? null : admin.getId(),
                    Map.of("email", email.length() > 200 ? email.substring(0, 200) : email, "reason",
                            admin == null ? "unknown_email" : admin.isActive() ? "wrong_password" : "disabled"));
            throw ApiException.unauthorized("Those details don't match.");
        }
        rateLimiter.reset(key);
        admin.recordLogin();
        PlatformSessions.Session session = sessions.start(admin);
        audit.record(new Actor(admin.getId(), admin.getName()), "platform.login", null, "platform_admin",
                admin.getId(), Map.of());
        boolean secure = properties.auth().cookieSecure();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, SessionCookies.set(SessionCookies.PLATFORM_NAME,
                        SessionCookies.PLATFORM_PATH, session.refreshToken(), sessions.ttl(), secure).toString())
                // A school session left in this browser would otherwise come back on the next reload.
                .header(HttpHeaders.SET_COOKIE, SessionCookies.cleared(SessionCookies.SCHOOL_NAME,
                        SessionCookies.SCHOOL_PATH, secure).toString())
                .body(session.body());
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = SessionCookies.PLATFORM_NAME, required = false) String cookie) {
        try {
            PlatformSessions.Session session = sessions.refresh(cookie);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, SessionCookies.set(SessionCookies.PLATFORM_NAME,
                            SessionCookies.PLATFORM_PATH, session.refreshToken(), sessions.ttl(),
                            properties.auth().cookieSecure()).toString())
                    .body(session.body());
        } catch (PlatformSessions.RefreshRejected e) {
            var problem = ApiExceptionHandler.problem(e.status(), e.title(), e.getMessage(), Map.of());
            if (!e.clearCookie()) {
                return problem;
            }
            return ResponseEntity.status(problem.getStatusCode())
                    .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                    .body(problem.getBody());
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = SessionCookies.PLATFORM_NAME, required = false) String cookie) {
        sessions.logout(cookie).ifPresent(admin -> audit.record(new Actor(admin.getId(), admin.getName()),
                "platform.logout", null, "platform_admin", admin.getId(), Map.of()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearedCookie().toString()).build();
    }

    private org.springframework.http.ResponseCookie clearedCookie() {
        return SessionCookies.cleared(SessionCookies.PLATFORM_NAME, SessionCookies.PLATFORM_PATH,
                properties.auth().cookieSecure());
    }
}
