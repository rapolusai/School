package com.akshara.identity;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.AksharaProperties;
import com.akshara.shared.ApiExceptionHandler;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.SessionCookies;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String REFRESH_COOKIE = SessionCookies.SCHOOL_NAME;
    static final String COOKIE_PATH = SessionCookies.SCHOOL_PATH;

    private final AuthService auth;
    private final PasswordResetService resets;
    private final AksharaProperties properties;

    public AuthController(AuthService auth, PasswordResetService resets, AksharaProperties properties) {
        this.auth = auth;
        this.resets = resets;
        this.properties = properties;
    }

    public record LoginRequest(
            @NotBlank @Size(max = 40) String schoolCode,
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String password) {
    }

    public record AuthResponse(String accessToken, long expiresIn, Me user) {

        static AuthResponse of(AuthService.Session session) {
            return new AuthResponse(session.accessToken(), session.expiresIn(), session.user());
        }
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthService.Session session = auth.login(request.schoolCode(), request.email(), request.password(),
                http.getRemoteAddr());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                // A Super Admin session left in this browser would otherwise come back on the next reload.
                .header(HttpHeaders.SET_COOKIE, SessionCookies.cleared(SessionCookies.PLATFORM_NAME,
                        SessionCookies.PLATFORM_PATH, properties.auth().cookieSecure()).toString())
                .body(AuthResponse.of(session));
    }

    public record ResetRequest(
            @NotBlank @Size(max = 40) String schoolCode,
            @NotBlank @Size(max = 254) String email) {
    }

    public record ResetConfirm(
            @NotBlank @Size(max = 40) String schoolCode,
            @NotBlank @Size(max = 254) String email,
            @NotBlank(message = "Enter the 6-digit code from the email.") @Size(max = 12) String code,
            @NotBlank @Size(min = 10, max = 200, message = "Use at least 10 characters.") String newPassword) {
    }

    public record PasswordChange(
            @NotBlank @Size(max = 200) String currentPassword,
            @NotBlank @Size(min = 10, max = 200, message = "Use at least 10 characters.") String newPassword) {
    }

    public record ResetAccepted(String message) {
    }

    /**
     * "Forgot password": sends a code to the person's email when the school and email exist. The answer is the same
     * either way, so the form never tells anyone who has an account.
     */
    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResetAccepted requestReset(@Valid @RequestBody ResetRequest request, HttpServletRequest http) {
        resets.request(request.schoolCode(), request.email(), http.getRemoteAddr());
        return new ResetAccepted("If the school code and email are right, a 6-digit code is on its way to that email. "
                + "It works for 15 minutes.");
    }

    /** Sets a new password with the emailed code, and ends every open session of that person. */
    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmReset(@Valid @RequestBody ResetConfirm request, HttpServletRequest http) {
        resets.confirm(request.schoolCode(), request.email(), request.code(), request.newPassword(),
                http.getRemoteAddr());
    }

    /**
     * A signed-in person of a school changes their own password. Other devices are signed out; this one gets a fresh
     * session (new refresh cookie and access token).
     */
    @PostMapping("/password")
    public ResponseEntity<AuthResponse> changePassword(@Valid @RequestBody PasswordChange request) {
        if (CurrentUser.isPlatformAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException("School accounts only");
        }
        AuthService.Session session = auth.changePassword(CurrentUser.requireId(), request.currentPassword(),
                request.newPassword());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(AuthResponse.of(session));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String cookie) {
        try {
            AuthService.Session session = auth.refresh(cookie);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                    .body(AuthResponse.of(session));
        } catch (AuthService.RefreshRejected e) {
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
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String cookie) {
        auth.logout(cookie);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearedCookie().toString()).build();
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.auth().cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(properties.auth().refreshTokenTtl())
                .build();
    }

    private ResponseCookie clearedCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(properties.auth().cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(0)
                .build();
    }
}
