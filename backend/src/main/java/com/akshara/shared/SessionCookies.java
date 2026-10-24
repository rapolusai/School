package com.akshara.shared;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

/**
 * The two httpOnly refresh cookies. A school user's cookie is sent only to {@code /api/auth}, a Super Admin's only to
 * {@code /api/platform/auth}, so neither endpoint ever receives the other's cookie; their values have different shapes
 * as well, so one pasted into the other is refused. Signing in as one clears the other, so a reload restores the
 * session that signed in last.
 */
public final class SessionCookies {

    public static final String SCHOOL_NAME = "refresh_token";
    public static final String SCHOOL_PATH = "/api/auth";
    public static final String PLATFORM_NAME = "platform_refresh_token";
    public static final String PLATFORM_PATH = "/api/platform/auth";

    private SessionCookies() {
    }

    /** An httpOnly, SameSite=Strict cookie for the path. */
    public static ResponseCookie set(String name, String path, String value, Duration ttl, boolean secure) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(path)
                .maxAge(ttl)
                .build();
    }

    /** Removes the cookie of that name and path from the browser. */
    public static ResponseCookie cleared(String name, String path, boolean secure) {
        return set(name, path, "", Duration.ZERO, secure);
    }
}
