package com.akshara.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * A Super Admin's session survives a reload: sign-in sets an httpOnly refresh cookie scoped to /api/platform/auth that
 * rotates like a school user's. A platform cookie never works on the school refresh, a school cookie never works on
 * the platform refresh, and the tokens of one never reach the other's endpoints.
 */
class PlatformSessionIT extends IntegrationTest {

    private static final String COOKIE = "platform_refresh_token";

    private record PlatformSession(String accessToken, String refreshToken) {
    }

    @Test
    void aReloadKeepsTheSuperAdminSignedInWithARotatingCookie() throws Exception {
        MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"%s"}""".formatted(PLATFORM_EMAIL, PLATFORM_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.platformAdmin").value(true))
                .andExpect(jsonPath("$.user.tenant").doesNotExist())
                .andReturn().getResponse();
        Cookie cookie = response.getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/platform/auth");
        assertThat(cookie.getMaxAge()).isPositive();
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(h -> assertThat(h)
                .startsWith("platform_refresh_token=").contains("SameSite=Strict"));
        // Any school session left in the browser is cleared, so the reload restores this one.
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(h -> assertThat(h)
                .startsWith("refresh_token=;").contains("Path=/api/auth").contains("Max-Age=0"));

        // The reload: the web app asks for a new access token with the cookie alone.
        MockHttpServletResponse refreshed = refresh(cookie.getValue())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.platformAdmin").value(true))
                .andExpect(jsonPath("$.user.email").value(PLATFORM_EMAIL))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andReturn().getResponse();
        String next = refreshed.getCookie(COOKIE).getValue();
        assertThat(next).isNotEqualTo(cookie.getValue());
        String accessToken = com.jayway.jsonpath.JsonPath.read(refreshed.getContentAsString(), "$.accessToken");
        api.get("/api/platform/tenants", accessToken).andExpect(status().isOk());

        // The chain keeps rotating.
        String third = refresh(next).andExpect(status().isOk()).andReturn().getResponse().getCookie(COOKIE).getValue();

        // An old cookie coming back long after it was replaced looks stolen: the whole chain ends.
        try (Connection owner = ownerConnection(); PreparedStatement s = owner.prepareStatement(
                "update platform.platform_refresh_token set revoked_at = now() - interval '1 hour' "
                        + "where replaced_by is not null")) {
            s.executeUpdate();
        }
        refresh(cookie.getValue()).andExpect(status().isUnauthorized());
        refresh(third).andExpect(status().isUnauthorized());
    }

    @Test
    void signingOutEndsTheSessionAndOnlyTheWebAppMayRefresh() throws Exception {
        PlatformSession session = platformLogin();
        mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/refresh")
                .cookie(new Cookie(COOKIE, session.refreshToken()))
                .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        MockHttpServletResponse out = mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/logout")
                .cookie(new Cookie(COOKIE, session.refreshToken())))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();
        assertThat(out.getCookie(COOKIE).getMaxAge()).isZero();
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
        refresh(null).andExpect(status().isUnauthorized());
        refresh("p.not-a-real-token-but-long-enough-to-look-like-one-xx").andExpect(status().isUnauthorized());
    }

    @Test
    void platformAndSchoolSessionsNeverCross() throws Exception {
        School school = api.signup();
        Session schoolAdmin = api.login(school);
        PlatformSession platform = platformLogin();

        // A platform refresh token is refused by the school refresh, under either cookie name.
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/refresh")
                .cookie(new Cookie("refresh_token", platform.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/refresh")
                .cookie(new Cookie(COOKIE, platform.refreshToken())))
                .andExpect(status().isUnauthorized());
        // A school refresh token is refused by the platform refresh.
        refresh(schoolAdmin.refreshToken()).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/refresh")
                .cookie(new Cookie("refresh_token", schoolAdmin.refreshToken())))
                .andExpect(status().isUnauthorized());
        // Both still work where they belong (a refused attempt does not end them).
        api.refresh(schoolAdmin.refreshToken()).andExpect(status().isOk());
        PlatformSession refreshed = platformRefresh(platform.refreshToken());

        // A refreshed platform access token cannot act on a school, and a school token cannot act on the platform.
        for (String path : List.of("/api/users", "/api/students", "/api/me/children")) {
            api.get(path, refreshed.accessToken()).andExpect(status().isForbidden());
        }
        api.get("/api/platform/tenants", schoolAdmin.accessToken()).andExpect(status().isForbidden());
        api.get("/api/platform/tenants", refreshed.accessToken()).andExpect(status().isOk());

        // A school sign-in clears a Super Admin cookie left in the browser.
        MockHttpServletResponse login = mvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"schoolCode":"%s","email":"%s","password":"%s"}"""
                        .formatted(school.code(), school.adminEmail(), com.akshara.support.TestApi.PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(login.getHeaders("Set-Cookie")).anySatisfy(h -> assertThat(h)
                .startsWith("platform_refresh_token=;").contains("Path=/api/platform/auth").contains("Max-Age=0"));
    }

    private PlatformSession platformLogin() throws Exception {
        MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"%s"}""".formatted(PLATFORM_EMAIL, PLATFORM_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        return new PlatformSession(com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.accessToken"),
                response.getCookie(COOKIE).getValue());
    }

    private PlatformSession platformRefresh(String cookie) throws Exception {
        MockHttpServletResponse response = refresh(cookie).andExpect(status().isOk()).andReturn().getResponse();
        return new PlatformSession(com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.accessToken"),
                response.getCookie(COOKIE).getValue());
    }

    private ResultActions refresh(String cookie) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/platform/auth/refresh");
        if (cookie != null) {
            request.cookie(new Cookie(COOKIE, cookie));
        }
        return mvc.perform(request);
    }
}
