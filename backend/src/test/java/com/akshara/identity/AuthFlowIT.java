package com.akshara.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

class AuthFlowIT extends IntegrationTest {

    @Test
    void signupThenSignInReturnsTheAdminAndASecureRefreshCookie() throws Exception {
        School school = api.signup();

        MockHttpServletResponse response = api.post("/api/auth/login", null, """
                {"schoolCode":"%s","email":"%s","password":"%s"}
                """.formatted(school.code().toUpperCase(), school.adminEmail().toUpperCase(), TestApi.PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.roles[0]").value("SCHOOL_ADMIN"))
                .andExpect(jsonPath("$.user.permissions", hasItem("users.manage")))
                .andExpect(jsonPath("$.user.platformAdmin").value(false))
                .andExpect(jsonPath("$.user.tenant.code").value(school.code()))
                .andExpect(jsonPath("$.user.tenant.status").value("TRIAL"))
                .andExpect(jsonPath("$.user.tenant.plan").value("STARTER"))
                .andExpect(jsonPath("$.user.tenant.trialEndsAt").isNotEmpty())
                .andReturn().getResponse();

        Cookie cookie = response.getCookie("refresh_token");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Strict");
        assertThat(cookie.getValue()).startsWith(school.tenantId() + ".");

        Session session = TestApi.session(response);
        api.get("/api/me", session.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(school.adminEmail()))
                .andExpect(jsonPath("$.tenant.id").value(school.tenantId().toString()));
    }

    @Test
    void wrongPasswordUnknownEmailAndUnknownSchoolAllGiveTheSameAnswer() throws Exception {
        School school = api.signup();
        String[] attempts = {
            body(school.code(), school.adminEmail(), "Wrong-password-1"),
            body(school.code(), "nobody@" + school.code() + ".akshara.test", TestApi.PASSWORD),
            body("no-such-school", school.adminEmail(), TestApi.PASSWORD),
        };
        String firstDetail = null;
        for (String attempt : attempts) {
            String detail = com.jayway.jsonpath.JsonPath.read(api.post("/api/auth/login", null, attempt)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andReturn().getResponse().getContentAsString(), "$.detail");
            if (firstDetail == null) {
                firstDetail = detail;
            }
            assertThat(detail).isEqualTo(firstDetail);
        }
    }

    @Test
    void refreshRotatesTheCookieAndLogoutEndsTheSession() throws Exception {
        School school = api.signup();
        Session first = api.login(school);

        MockHttpServletResponse refreshed = api.refresh(first.refreshToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(school.adminEmail()))
                .andReturn().getResponse();
        Session second = TestApi.session(refreshed);
        assertThat(second.refreshToken()).isNotBlank().isNotEqualTo(first.refreshToken());
        assertThat(second.accessToken()).isNotBlank();

        api.logout(second.refreshToken()).andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        api.refresh(second.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void aSecondRefreshWithTheSameCookieMomentsLaterIsRefusedWithoutEndingTheSession() throws Exception {
        School school = api.signup();
        Session first = api.login(school);
        Session second = TestApi.session(api.refresh(first.refreshToken())
                .andExpect(status().isOk()).andReturn().getResponse());

        // Two tabs refreshing at once: the loser gets 401 but must not wipe the winner's new cookie.
        api.refresh(first.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"));
        api.refresh(second.refreshToken()).andExpect(status().isOk());
    }

    @Test
    void reusingAnOldRefreshTokenRevokesTheWholeChain() throws Exception {
        School school = api.signup();
        Session first = api.login(school);
        Session second = TestApi.session(api.refresh(first.refreshToken())
                .andExpect(status().isOk()).andReturn().getResponse());

        // Pretend the first token was rotated long ago, then replay it as a thief would.
        try (Connection owner = ownerConnection();
                PreparedStatement update = owner.prepareStatement("""
                        update identity.refresh_token set revoked_at = now() - interval '1 hour'
                        where tenant_id = ? and replaced_by is not null""")) {
            update.setObject(1, school.tenantId());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
        api.refresh(first.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        api.refresh(second.refreshToken()).andExpect(status().isUnauthorized());

        Session admin = api.login(school);
        api.get("/api/audit-events", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", hasItem("auth.refresh_reused")));
    }

    @Test
    void refreshWithoutOrWithAGarbageCookieIsRefused() throws Exception {
        api.refresh(null).andExpect(status().isUnauthorized());
        api.refresh("not-a-token").andExpect(status().isUnauthorized());
        api.refresh(java.util.UUID.randomUUID() + ".abc").andExpect(status().isUnauthorized());
    }

    @Test
    void refreshFromAnotherSiteIsBlocked() throws Exception {
        School school = api.signup();
        Session session = api.login(school);
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/refresh")
                        .header("Origin", "https://evil.example")
                        .cookie(new Cookie("refresh_token", session.refreshToken())))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/refresh")
                        .header("Origin", "http://localhost:3000")
                        .cookie(new Cookie("refresh_token", session.refreshToken())))
                .andExpect(status().isOk());
    }

    @Test
    void repeatedFailuresAreThrottled() throws Exception {
        School school = api.signup();
        String wrong = body(school.code(), school.adminEmail(), "Wrong-password-1");
        for (int i = 0; i < 10; i++) {
            mvc.perform(fromIp("10.9.8.7", wrong)).andExpect(status().isUnauthorized());
        }
        mvc.perform(fromIp("10.9.8.7", wrong)).andExpect(status().isTooManyRequests());
        // Even the right password waits until the window passes.
        mvc.perform(fromIp("10.9.8.7", body(school.code(), school.adminEmail(), TestApi.PASSWORD)))
                .andExpect(status().isTooManyRequests());
        // Another address is not affected.
        mvc.perform(fromIp("10.9.8.8", body(school.code(), school.adminEmail(), TestApi.PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void suspendedSchoolsAndDisabledUsersCannotSignIn() throws Exception {
        School suspended = api.signup();
        School disabled = api.signup();
        Session stillOpen = api.login(disabled);
        try (Connection owner = ownerConnection()) {
            try (PreparedStatement s = owner.prepareStatement("update platform.tenant set status = 'SUSPENDED' where id = ?")) {
                s.setObject(1, suspended.tenantId());
                s.executeUpdate();
            }
            try (PreparedStatement s = owner.prepareStatement(
                    "update identity.user_account set status = 'DISABLED' where tenant_id = ?")) {
                s.setObject(1, disabled.tenantId());
                s.executeUpdate();
            }
        }
        api.post("/api/auth/login", null, body(suspended.code(), suspended.adminEmail(), TestApi.PASSWORD))
                .andExpect(status().isUnauthorized());
        api.post("/api/auth/login", null, body(disabled.code(), disabled.adminEmail(), TestApi.PASSWORD))
                .andExpect(status().isUnauthorized());
        api.refresh(stillOpen.refreshToken()).andExpect(status().isUnauthorized());
        api.get("/api/me", stillOpen.accessToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void signupValidatesItsInput() throws Exception {
        School taken = api.signup();
        api.post("/api/public/signup", null, signup(taken.code(), "a@b.akshara.test", TestApi.PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.schoolCode").exists());
        api.post("/api/public/signup", null, signup("admin", "a@b.akshara.test", TestApi.PASSWORD))
                .andExpect(status().isConflict());
        api.post("/api/public/signup", null, signup("Bad Code!", "a@b.akshara.test", TestApi.PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.schoolCode").exists());
        api.post("/api/public/signup", null, signup(TestApi.randomCode(), "not-an-email", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.adminEmail").exists())
                .andExpect(jsonPath("$.errors.password").exists());
        api.post("/api/public/signup", null, "{not json").andExpect(status().isBadRequest());
    }

    @Test
    void signupCreatesTheSevenStandardRoles() throws Exception {
        Session admin = api.login(api.signup());
        api.get("/api/roles", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[*].code", hasItem("TEACHER")))
                .andExpect(jsonPath("$[*].code", not(hasItem("SUPER_ADMIN"))));
    }

    private static String body(String code, String email, String password) {
        return """
                {"schoolCode":"%s","email":"%s","password":"%s"}
                """.formatted(code, email, password);
    }

    private static String signup(String code, String email, String password) {
        return """
                {"schoolName":"Test","schoolCode":"%s","board":"CBSE","city":"Pune",
                 "adminName":"Asha","adminEmail":"%s","password":"%s"}
                """.formatted(code, email, password);
    }

    private static MockHttpServletRequestBuilderWithIp fromIp(String ip, String json) {
        return new MockHttpServletRequestBuilderWithIp(ip, json);
    }

    /** A login request that appears to come from the given address. */
    private record MockHttpServletRequestBuilderWithIp(String ip, String json)
            implements org.springframework.test.web.servlet.RequestBuilder {

        @Override
        public org.springframework.mock.web.MockHttpServletRequest buildRequest(
                jakarta.servlet.ServletContext servletContext) {
            var request = MockMvcRequestBuilders.post("/api/auth/login")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(json)
                    .buildRequest(servletContext);
            request.setRemoteAddr(ip);
            return request;
        }
    }
}
