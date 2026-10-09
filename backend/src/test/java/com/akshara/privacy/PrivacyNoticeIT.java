package com.akshara.privacy;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** The privacy notice and grievance officer: versions, the public page, and who may change them. */
class PrivacyNoticeIT extends PrivacyTestBase {

    @Test
    void theNoticeIsVersionedAndOlderVersionsStayReadable() throws Exception {
        // Before the first version, staff start from the default template with the school's name in it.
        api.get("/api/privacy/notice", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.officer").doesNotExist())
                .andExpect(jsonPath("$.draftIsTemplate").value(true))
                .andExpect(jsonPath("$.draftEn", containsString("Digital Personal Data Protection Act, 2023")))
                .andExpect(jsonPath("$.draftHi", containsString("डिजिटल व्यक्तिगत डेटा संरक्षण अधिनियम")))
                .andExpect(jsonPath("$.versions.length()").value(0));
        // A notice needs a grievance officer, and the officer needs valid contact details.
        api.post("/api/privacy/notice", admin.accessToken(), """
                {"bodyEn":"Text","bodyHi":"पाठ"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.grievanceOfficer").exists());
        api.put("/api/privacy/grievance-officer", admin.accessToken(), """
                {"name":"","email":"not-an-email","phone":"12"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.phone").exists());
        api.post("/api/privacy/notice", admin.accessToken(), """
                {"bodyEn":"","bodyHi":"पाठ"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bodyEn").exists());

        int first = publish(null);
        api.get("/api/privacy/notice", admin.accessToken())
                .andExpect(jsonPath("$.current.version").value(first))
                .andExpect(jsonPath("$.current.current").value(true))
                .andExpect(jsonPath("$.current.grievanceOfficer.name").value("Lakshmi Iyer"))
                .andExpect(jsonPath("$.draftIsTemplate").value(false))
                .andExpect(jsonPath("$.draftEn", containsString("The school.")));
        // Every later version says what changed.
        api.post("/api/privacy/notice", admin.accessToken(), """
                {"bodyEn":"Version two","bodyHi":"दूसरा संस्करण"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.changeSummary").exists());
        api.put("/api/privacy/grievance-officer", admin.accessToken(), """
                {"name":"Ravi Menon","email":"ravi@%s.akshara.test","phone":"9876543210"}""".formatted(school.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ravi Menon"))
                // An Indian mobile number is fine; a list never shows more than it must.
                .andExpect(jsonPath("$.phone").value("9876543210"));
        api.post("/api/privacy/notice", admin.accessToken(), """
                {"bodyEn":"Version two","bodyHi":"दूसरा संस्करण","changeSummary":"WhatsApp updates added"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.grievanceOfficer.name").value("Ravi Menon"));

        // Version 1 keeps the officer named when it was published.
        api.get("/api/privacy/notice/versions/1", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(false))
                .andExpect(jsonPath("$.bodyEn").value("## Who we are\nThe school."))
                .andExpect(jsonPath("$.grievanceOfficer.name").value("Lakshmi Iyer"));
        api.get("/api/privacy/notice/versions/9", admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/privacy/notice", admin.accessToken())
                .andExpect(jsonPath("$.versions[*].version", org.hamcrest.Matchers.contains(2, 1)))
                .andExpect(jsonPath("$.versions[0].changeSummary").value("WhatsApp updates added"));

        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("privacy_notice.published", "grievance_officer.updated")));
    }

    @Test
    void anyoneCanReadTheCurrentNoticeWithoutSigningIn() throws Exception {
        String path = "/api/public/schools/" + school.code() + "/privacy-notice";
        // Nothing published yet.
        mvc.perform(get(path)).andExpect(status().isNotFound());
        publish(null);
        publish("Photos are now optional");

        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schoolCode").value(school.code()))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.changeSummary").value("Photos are now optional"))
                .andExpect(jsonPath("$.grievanceOfficer.email").value("dpo@" + school.code() + ".akshara.test"))
                .andExpect(jsonPath("$.versions.length()").value(2))
                // Staff names stay inside the school.
                .andExpect(jsonPath("$.versions[0].publishedByName").doesNotExist());
        mvc.perform(get(path).param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.currentVersion").value(2));
        mvc.perform(get(path).param("version", "3")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/schools/no-such-school/privacy-notice")).andExpect(status().isNotFound());

        // Another school's page shows only its own notice.
        School other = api.signup();
        mvc.perform(get("/api/public/schools/" + other.code() + "/privacy-notice")).andExpect(status().isNotFound());
    }

    @Test
    void onlyPrivacyManagersChangeTheNotice() throws Exception {
        Session principal = member("PRINCIPAL", "principal");
        Session teacher = member("TEACHER", "teacher");
        Session accounts = member("ACCOUNTANT", "accounts");
        officer(principal);
        api.get("/api/privacy/notice", principal.accessToken()).andExpect(status().isOk());
        api.post("/api/privacy/notice", principal.accessToken(), """
                {"bodyEn":"Text","bodyHi":"पाठ"}""").andExpect(status().isCreated());
        for (Session outsider : new Session[] {teacher, accounts, parent}) {
            api.get("/api/privacy/notice", outsider.accessToken()).andExpect(status().isForbidden());
            api.get("/api/privacy/requests", outsider.accessToken()).andExpect(status().isForbidden());
            api.put("/api/privacy/grievance-officer", outsider.accessToken(), """
                    {"name":"X","email":"x@example.test","phone":"9876543210"}""").andExpect(status().isForbidden());
        }
        // Staff do not use the parent pages.
        api.get("/api/me/privacy", teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/me/privacy", principal.accessToken()).andExpect(status().isForbidden());
        // Without signing in nothing but the public page is open.
        mvc.perform(get("/api/privacy/notice")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/privacy")).andExpect(status().isUnauthorized());
        api.get("/api/me", principal.accessToken())
                .andExpect(jsonPath("$.permissions", hasItems("privacy.manage")));
        api.get("/api/me", admin.accessToken())
                .andExpect(jsonPath("$.permissions", hasItems("privacy.manage")));
    }
}
