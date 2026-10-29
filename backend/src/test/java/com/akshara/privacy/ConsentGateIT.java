package com.akshara.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * Parent consent is enforced by the API: once a notice is published, a parent or student whose children have no
 * essential consent for it gets 403 consent-required from family endpoints, while their privacy page, who-am-I,
 * refresh and sign-out keep working. Staff are never checked.
 */
class ConsentGateIT extends PrivacyTestBase {

    private static final String TYPE = "urn:akshara:problem:consent-required";

    @Test
    void familyEndpointsWaitForConsentAndOpenOnceItIsGiven() throws Exception {
        Session student = studentSignIn(arjun, "arjun");
        // No notice yet: nothing to accept, nothing blocked.
        api.get("/api/me/children", parent.accessToken()).andExpect(status().isOk());
        api.get("/api/me/student", student.accessToken()).andExpect(status().isOk());

        int version = publish(null);

        api.get("/api/me/children", parent.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(TYPE))
                .andExpect(jsonPath("$.title").value("Consent needed"));
        api.get("/api/me/children/" + arjun + "/attendance", parent.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(TYPE));
        api.get("/api/me/student", student.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(TYPE));
        api.get("/api/me/children", otherParent.accessToken()).andExpect(status().isForbidden());

        // What the consent screen needs stays open: who they are, the notice and consent, refresh and sign-out.
        api.get("/api/me", parent.accessToken()).andExpect(status().isOk());
        api.get("/api/me/preferences", parent.accessToken()).andExpect(status().isOk());
        api.get("/api/me/privacy", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsConsent").value(true));
        api.get("/api/me/privacy/requests", parent.accessToken()).andExpect(status().isOk());
        api.refresh(parent.refreshToken()).andExpect(status().isOk());
        api.get("/api/me", student.accessToken()).andExpect(status().isOk());

        // Staff are never asked.
        api.get("/api/students", admin.accessToken()).andExpect(status().isOk());
        api.get("/api/me/children", admin.accessToken()).andExpect(status().isOk());

        // The parent accepts for both children: their app opens, and so does the child's own.
        acceptForBoth(version);
        api.get("/api/me/children", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        api.get("/api/me/student", student.accessToken()).andExpect(status().isOk());
        // Another family is still waiting for its own consent.
        api.get("/api/me/children", otherParent.accessToken()).andExpect(status().isForbidden());

        // A new version asks again.
        publish("Photos on the website");
        api.get("/api/me/children", parent.accessToken()).andExpect(status().isForbidden());
        // Paper consent entered by the office counts as well.
        int v2 = publish("Bus tracking");
        for (String child : new String[] {arjun, diya}) {
            api.post("/api/privacy/students/" + child + "/consents", admin.accessToken(), """
                    {"givenByName":"Anitha Sharma","signedOn":"%s","photos":false,"whatsapp":false}"""
                    .formatted(today())).andExpect(status().isCreated());
        }
        api.get("/api/me/privacy", parent.accessToken()).andExpect(jsonPath("$.notice.version").value(v2));
        api.get("/api/me/children", parent.accessToken()).andExpect(status().isOk());
    }

    @Test
    void oddSpellingsOfAFamilyPathAreStillAskedForConsent() throws Exception {
        publish(null);
        // Negative sweep 2026-10-29: percent-encoded letters and a trailing slash must not slip past the gate.
        for (String path : new String[] {"/api/me/%63hildren", "/api/%6De/children", "/api/me/children/"}) {
            int code = mvc.perform(MockMvcRequestBuilders.get(URI.create(path))
                    .header("Authorization", "Bearer " + parent.accessToken()))
                    .andReturn().getResponse().getStatus();
            assertThat(code).as(path).isIn(403, 404);
        }
    }

    @Test
    void aChildWhoLeftNeedsNoConsent() throws Exception {
        publish(null);
        api.get("/api/me/children", otherParent.accessToken()).andExpect(status().isForbidden());
        api.post("/api/students/" + kabir + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"%s","reason":"Moved"}""".formatted(today()))
                .andExpect(status().isOk());
        api.get("/api/me/children", otherParent.accessToken()).andExpect(status().isOk());
    }

    private Session studentSignIn(String studentId, String who) throws Exception {
        api.post("/api/students/" + studentId + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email(who), TestApi.PASSWORD))
                .andExpect(status().isOk());
        return api.login(school.code(), email(who), TestApi.PASSWORD);
    }
}
