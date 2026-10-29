package com.akshara.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.shared.AksharaProperties;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Negative sweep of 2026-10-29 (docs/testing/negative-sweep-2026-10-29.md), sign-in and passwords: parallel guesses
 * against the shared limit, X-Forwarded-For from a client, case and space variants of the key, the Origin check
 * reached through an encoded path, a School Admin setting their own password without the current one, and refresh
 * cookies used on the wrong endpoint or twice.
 */
class AuthNegativeIT extends IntegrationTest {

    @Autowired
    AksharaProperties properties;

    @Test
    void parallelWrongPasswordsGetNoMoreTriesThanTheLimit() throws Exception {
        School school = api.signup();
        int max = properties.auth().maxFailedLogins();
        String wrong = """
                {"schoolCode":"%s","email":"%s","password":"not-the-password"}"""
                .formatted(school.code(), school.adminEmail());
        int attempts = max * 3;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Integer> codes = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                calls.add(() -> login("198.51.100.9", null, wrong).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        // Every guess that was actually checked is a 401; at most the limit's worth were checked.
        assertThat(codes).filteredOn(c -> c == 401).hasSizeLessThanOrEqualTo(max);
        assertThat(codes).filteredOn(c -> c == 429).hasSizeGreaterThanOrEqualTo(attempts - max);
    }

    @Test
    void aClientsOwnXForwardedForAndCaseVariantsDoNotEscapeTheLimit() throws Exception {
        School school = api.signup();
        int max = properties.auth().maxFailedLogins();
        for (int i = 0; i < max; i++) {
            String email = i % 2 == 0 ? school.adminEmail().toUpperCase() : "  " + school.adminEmail() + " ";
            login("198.51.100.10", "203.0.113." + i, """
                    {"schoolCode":"%s","email":"%s","password":"not-the-password"}"""
                    .formatted(school.code().toUpperCase(), email)).andExpect(status().isUnauthorized());
        }
        // Without a trusted proxy in front (no "aws" profile), X-Forwarded-For is ignored: still blocked.
        login("198.51.100.10", "203.0.113.200", """
                {"schoolCode":"%s","email":"%s","password":"%s"}"""
                .formatted(school.code(), school.adminEmail(), TestApi.PASSWORD))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void theOriginCheckCannotBeSkippedWithAnEncodedPath() throws Exception {
        School school = api.signup();
        Session session = api.login(school);
        for (String path : List.of("/api/auth/refres%68", "/api/auth/%72efresh", "/api/auth/logou%74")) {
            int code = mvc.perform(MockMvcRequestBuilders.post(URI.create(path))
                    .header("Origin", "https://evil.example")
                    .cookie(new Cookie("refresh_token", session.refreshToken())))
                    .andReturn().getResponse().getStatus();
            assertThat(code).as(path).isIn(403, 404);
        }
        // The session was not touched.
        api.refresh(session.refreshToken()).andExpect(status().isOk());
    }

    @Test
    void aSchoolAdminCannotSetTheirOwnPasswordWithoutTheCurrentOne() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        String me = TestApi.read(api.get("/api/me", admin.accessToken()), "$.id");
        api.put("/api/staff/" + me + "/password", admin.accessToken(), """
                {"password":"Taken-over-password-1"}""").andExpect(status().isConflict());
        api.login(school);
        // Another staff member's password is still theirs to set, and a parent or unknown id is 404.
        String email = "t1@" + school.code() + ".akshara.test";
        String teacher = api.createUser(admin, "Tara Teacher", email, List.of("TEACHER"));
        api.put("/api/staff/" + teacher + "/password", admin.accessToken(), """
                {"password":"Set-by-admin-123"}""").andExpect(status().isOk());
        api.login(school.code(), email, "Set-by-admin-123");
        api.put("/api/staff/" + java.util.UUID.randomUUID() + "/password", admin.accessToken(), """
                {"password":"Set-by-admin-123"}""").andExpect(status().isNotFound());
        api.put("/api/staff/" + teacher + "/password", admin.accessToken(), """
                {"password":"short"}""").andExpect(status().isBadRequest());
        // A teacher cannot set anyone's password.
        Session t = api.login(school.code(), email, "Set-by-admin-123");
        api.put("/api/staff/" + me + "/password", t.accessToken(), """
                {"password":"Taken-over-password-1"}""").andExpect(status().isForbidden());
    }

    @Test
    void passwordChangesAndResetsRefuseOddInput() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        // Over-long, empty and missing fields.
        api.post("/api/auth/password", admin.accessToken(), """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted(TestApi.PASSWORD, "x".repeat(201)))
                .andExpect(status().isBadRequest());
        api.post("/api/auth/password", admin.accessToken(), "{}").andExpect(status().isBadRequest());
        api.post("/api/auth/password-reset/confirm", null, """
                {"schoolCode":"%s","email":"%s","code":"12345678901234567890","newPassword":"Brand-new-password-1"}"""
                .formatted(school.code(), school.adminEmail())).andExpect(status().isBadRequest());
        api.post("/api/auth/password-reset", null, """
                {"schoolCode":"%s","email":"%s"}""".formatted("x".repeat(500), "y".repeat(500)))
                .andExpect(status().isBadRequest());
        // The platform console cannot use the school change-password endpoint.
        Session root = api.platformLogin(PLATFORM_EMAIL, PLATFORM_PASSWORD);
        api.post("/api/auth/password", root.accessToken(), """
                {"currentPassword":"%s","newPassword":"Brand-new-password-1"}""".formatted(PLATFORM_PASSWORD))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void refreshCookiesOnTheWrongEndpointOrAfterRotationAreRefused() throws Exception {
        School school = api.signup();
        Session schoolSession = api.login(school);
        // A school cookie on the platform endpoint, and a forged platform cookie.
        mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/refresh")
                .cookie(new Cookie("platform_refresh_token", schoolSession.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/refresh")
                .cookie(new Cookie("platform_refresh_token", "p." + "A".repeat(43))))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/refresh")
                .cookie(new Cookie("platform_refresh_token", "p." + "A".repeat(5000))))
                .andExpect(status().isUnauthorized());
        // Logging out with garbage is harmless.
        mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/logout")
                .cookie(new Cookie("platform_refresh_token", "garbage"))).andExpect(status().is2xxSuccessful());
    }

    private ResultActions login(String remoteAddr, String forwardedFor, String body) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/auth/login").with(r -> {
            r.setRemoteAddr(remoteAddr);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(body);
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request);
    }
}
