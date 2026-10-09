package com.akshara.identity;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.AksharaProperties;
import com.akshara.shared.ApiExceptionHandler;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";
    static final String COOKIE_PATH = "/api/auth";

    private final AuthService auth;
    private final AksharaProperties properties;

    public AuthController(AuthService auth, AksharaProperties properties) {
        this.auth = auth;
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
