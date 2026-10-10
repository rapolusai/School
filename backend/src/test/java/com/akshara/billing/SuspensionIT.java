package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.admissions.AdmissionsService;
import com.akshara.identity.AuthService;
import com.akshara.platform.TenantStatus;
import com.akshara.support.BillingFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Suspension by the Super Admin: never automatic, always with a reason, audited both ways. While a school is suspended
 * nobody of it signs in or refreshes a session, and its public enquiry form is closed; reactivating restores where it
 * was.
 */
@RecordApplicationEvents
class SuspensionIT extends IntegrationTest {

    static final String REASON = "Subscription invoice unpaid for 60 days";

    @Autowired
    ApplicationEvents events;

    private BillingFixtures billing;
    private Session root;
    private School school;
    private Session admin;
    private String base;

    @BeforeEach
    void schoolWithAClass() throws Exception {
        billing = new BillingFixtures(api);
        root = billing.root();
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        fixtures.schoolClass(admin, "Class 1");
        base = BillingFixtures.school(school.tenantId());
    }

    @Test
    void aSuspendedSchoolCannotSignInRefreshOrTakeEnquiriesUntilItIsReactivated() throws Exception {
        info(school.code()).andExpect(status().isOk());
        api.post(base + "/reactivate", root.accessToken(), "{}").andExpect(status().isConflict());
        api.post(base + "/suspend", root.accessToken(), """
                {"reason":"   "}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());

        api.post(base + "/suspend", root.accessToken(), """
                {"reason":"  %s "}""".formatted(REASON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.suspension.reason").value(REASON))
                .andExpect(jsonPath("$.suspension.suspendedAt").isNotEmpty())
                .andExpect(jsonPath("$.notice.kind").isEmpty());
        api.post(base + "/suspend", root.accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());

        // The right password learns that sign-in is paused; anything else gets the usual message.
        login(school.code(), school.adminEmail(), TestApi.PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(AuthService.SCHOOL_PAUSED_TYPE))
                .andExpect(jsonPath("$.detail").value(containsString("paused")))
                .andExpect(jsonPath("$.detail").value(not(containsString(REASON))));
        login(school.code(), school.adminEmail(), "Wrong-password-1")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(AuthService.SCHOOL_PAUSED_TYPE))))
                .andExpect(jsonPath("$.detail").value(containsString("don't match")));
        login(school.code(), "nobody@" + school.code() + ".akshara.test", TestApi.PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(AuthService.SCHOOL_PAUSED_TYPE))));

        // Open sessions die at their next refresh (the access token itself lives 15 minutes at most), and the
        // refused refresh revokes the session, so it stays dead after reactivation too.
        api.refresh(admin.refreshToken()).andExpect(status().isUnauthorized());

        // The public enquiry form refuses politely, as for a school that does not exist.
        info(school.code())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("School was not found."));
        enquire(school.code()).andExpect(status().isNotFound());

        // The Super Admin still sees the school, and nothing else changes until it is reactivated.
        api.get(base, root.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suspension.reason").value(REASON));
        api.post(base + "/subscription", root.accessToken(), """
                {"plan":"GROWTH","billingCycle":"YEARLY","billedStudents":10}""")
                .andExpect(status().isConflict());
        api.get("/api/platform/billing/renewals", root.accessToken())
                .andExpect(jsonPath("$[?(@.tenantId == '%s')]".formatted(school.tenantId())).isEmpty());

        api.post(base + "/reactivate", root.accessToken(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.status").value("TRIAL"))
                .andExpect(jsonPath("$.suspension").isEmpty());
        Session again = api.login(school);
        api.refresh(again.refreshToken()).andExpect(status().isOk());
        api.refresh(admin.refreshToken()).andExpect(status().isUnauthorized());
        info(school.code()).andExpect(status().isOk());

        // Both actions are in the school's own trail, by the Super Admin, and so is the refused sign-in.
        api.get("/api/audit-events?limit=30", again.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'school.suspended')].details.reason").value(hasItem(REASON)))
                .andExpect(jsonPath("$[?(@.action == 'school.suspended')].details.previousStatus")
                        .value(hasItem("TRIAL")))
                .andExpect(jsonPath("$[?(@.action == 'school.suspended')].actorName")
                        .value(hasItem("Test Platform Admin")))
                .andExpect(jsonPath("$[?(@.action == 'school.reactivated')].details.status").value(hasItem("TRIAL")))
                .andExpect(jsonPath("$[?(@.action == 'auth.login_failed')].details.reason")
                        .value(hasItem("school_suspended")));
        assertThat(events.stream(SchoolSuspended.class)).extracting(SchoolSuspended::tenantId)
                .containsExactly(school.tenantId());
        assertThat(events.stream(SchoolReactivated.class))
                .containsExactly(new SchoolReactivated(school.tenantId(), TenantStatus.TRIAL));
    }

    @Test
    void aPayingSchoolGoesBackToTheStatusItHadAndOtherSchoolsAreUntouched() throws Exception {
        School other = api.signup();
        billing.paying(root, school.tenantId(), "36");
        api.post(base + "/status", root.accessToken(), """
                {"status":"PAST_DUE"}""").andExpect(status().isOk());
        api.post(base + "/suspend", root.accessToken(), """
                {"reason":"%s"}""".formatted(REASON)).andExpect(status().isOk());
        // Suspended schools are not moved between statuses by hand.
        api.post(base + "/status", root.accessToken(), """
                {"status":"ACTIVE"}""").andExpect(status().isConflict());

        Session otherAdmin = api.login(other);
        api.refresh(otherAdmin.refreshToken()).andExpect(status().isOk());

        api.post(base + "/reactivate", root.accessToken(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school.status").value("PAST_DUE"))
                .andExpect(jsonPath("$.subscription.billingCycle").value("YEARLY"));
        api.login(school);
    }

    @Test
    void onlyTheSuperAdminSuspendsAndReactivates() throws Exception {
        api.post(base + "/suspend", admin.accessToken(), """
                {"reason":"%s"}""".formatted(REASON)).andExpect(status().isForbidden());
        api.post(base + "/reactivate", admin.accessToken(), "{}").andExpect(status().isForbidden());
        api.post(base + "/suspend", null, """
                {"reason":"%s"}""".formatted(REASON)).andExpect(status().isUnauthorized());
        api.post(BillingFixtures.school(UUID.randomUUID()) + "/suspend", root.accessToken(), """
                {"reason":"%s"}""".formatted(REASON)).andExpect(status().isNotFound());
        api.login(school);
    }

    private ResultActions login(String code, String email, String password) throws Exception {
        return api.post("/api/auth/login", null, """
                {"schoolCode":"%s","email":"%s","password":"%s"}""".formatted(code, email, password));
    }

    private ResultActions info(String code) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/public/schools/" + code + "/admission-info")
                .with(request -> {
                    request.setRemoteAddr(randomIp());
                    return request;
                }));
    }

    private ResultActions enquire(String code) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/public/schools/" + code + "/enquiries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"parentName":"Sneha Kulkarni","relation":"MOTHER","mobile":"+91 98760 54321",
                         "email":"sneha@family.test","childFirstName":"Anaya","childLastName":"Kulkarni",
                         "dateOfBirth":"2021-07-04","className":"Class 1","academicYear":"CURRENT","consent":true,
                         "consentVersion":"%s","website":""}""".formatted(AdmissionsService.CONSENT_VERSION))
                .with(request -> {
                    request.setRemoteAddr(randomIp());
                    return request;
                }));
    }

    private static String randomIp() {
        var r = java.util.concurrent.ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }
}
