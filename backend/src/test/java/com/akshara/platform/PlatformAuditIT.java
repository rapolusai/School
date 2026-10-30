package com.akshara.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.shared.SessionCookies;
import com.akshara.support.BillingFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * The Super Admin's own trail across schools (Activity page): Super Admin sign-ins, failed sign-ins and sign-outs;
 * every change a Super Admin makes to a school; and every subscription, invoice and status change made by the school
 * itself, filtered by school and action, and as a CSV. Schools never reach it.
 */
class PlatformAuditIT extends IntegrationTest {

    @Test
    void signInsSuperAdminChangesAndSchoolBillingChangesAreInThePlatformTrail() throws Exception {
        api.post("/api/platform/auth/login", null, """
                {"email":"%s","password":"wrong-password"}""".formatted(PLATFORM_EMAIL))
                .andExpect(status().isUnauthorized());
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        School school = api.signup();
        Session admin = api.login(school);
        billing.paying(root, school.tenantId(), "36");
        api.post("/api/billing/cancel", admin.accessToken(), """
                {"reason":"Budget"}""").andExpect(status().isOk());
        // A school change that is not billing stays in the school's own trail only.
        api.createUser(admin, "Ravi Teacher", "ravi@" + school.code() + ".akshara.test", java.util.List.of("TEACHER"));

        String trail = "/api/platform/audit-events?limit=200&schoolId=" + school.tenantId();
        api.get(trail, root.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].schoolId", everyItem(is(school.tenantId().toString()))))
                .andExpect(jsonPath("$[*].action", hasItem("school.created")))
                .andExpect(jsonPath("$[*].action", hasItem("subscription.started")))
                .andExpect(jsonPath("$[*].action", hasItem("invoice.issued")))
                .andExpect(jsonPath("$[*].action", hasItem("subscription.cancel_scheduled")))
                .andExpect(jsonPath("$[?(@.action == 'subscription.started')].platformAdmin").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.action == 'subscription.cancel_scheduled')].platformAdmin")
                        .value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.action == 'subscription.cancel_scheduled')].schoolCode")
                        .value(hasItem(school.code())))
                .andExpect(jsonPath("$[?(@.action == 'user.created')]").isEmpty());

        api.get("/api/platform/audit-events?action=platform.login_failed&limit=200", root.accessToken())
                .andExpect(jsonPath("$[*].details.email", hasItem(PLATFORM_EMAIL)))
                .andExpect(jsonPath("$[*].details.reason", hasItem("wrong_password")));
        api.get("/api/platform/audit-events?action=platform.login&limit=200", root.accessToken())
                .andExpect(jsonPath("$[*].action", everyItem(is("platform.login"))))
                .andExpect(jsonPath("$[0].schoolId").doesNotExist());

        api.get("/api/platform/audit-events/facets", root.accessToken())
                .andExpect(jsonPath("$.actions", hasItem("platform.login")))
                .andExpect(jsonPath("$.schools[*].code", hasItem(school.code())));

        String csv = new String(api.get("/api/platform/audit-events/export.csv?schoolId=" + school.tenantId(),
                root.accessToken()).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(csv).contains("subscription.cancel_scheduled").contains(school.code());
        api.get("/api/platform/audit-events?action=platform.audit_exported", root.accessToken())
                .andExpect(jsonPath("$[0].details.tenantId").value(school.tenantId().toString()));

        // Schools never reach the platform trail.
        api.get("/api/platform/audit-events", admin.accessToken()).andExpect(status().isForbidden());
        api.get("/api/platform/audit-events/export.csv", admin.accessToken()).andExpect(status().isForbidden());

        // Signing out is recorded too.
        MockHttpServletResponse login = mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(PLATFORM_EMAIL, PLATFORM_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie cookie = login.getCookie(SessionCookies.PLATFORM_NAME);
        mvc.perform(MockMvcRequestBuilders.post("/api/platform/auth/logout").cookie(cookie))
                .andExpect(status().isNoContent());
        api.get("/api/platform/audit-events?action=platform.logout&limit=1", root.accessToken())
                .andExpect(jsonPath("$[0].actorName").isNotEmpty())
                .andExpect(jsonPath("$[0].platformAdmin").value(true));
    }
}
