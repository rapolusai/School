package com.akshara.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Consent per child and purpose: online in the parent app, on paper at admission, and its full history. */
@RecordApplicationEvents
class ConsentIT extends PrivacyTestBase {

    @Autowired
    private ApplicationEvents events;

    @Test
    void parentsAcceptEachNewNoticeAndCanWithdrawOptionalConsent() throws Exception {
        // No notice yet: nothing to accept.
        api.get("/api/me/privacy", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice").doesNotExist())
                .andExpect(jsonPath("$.needsConsent").value(false))
                .andExpect(jsonPath("$.children[*].fullName", contains("Arjun Sharma", "Diya Sharma")));

        int v1 = publish(null);
        api.get("/api/me/privacy", parent.accessToken())
                .andExpect(jsonPath("$.notice.version").value(v1))
                .andExpect(jsonPath("$.notice.grievanceOfficer.name").value("Lakshmi Iyer"))
                .andExpect(jsonPath("$.needsConsent").value(true))
                .andExpect(jsonPath("$.children[*].needsConsent", everyItem(is(true))))
                .andExpect(jsonPath("$.children[0].purposes[*].status", contains("NONE", "NONE", "NONE")));

        // Essential processing must be accepted; only the parent's own children can be listed.
        api.post("/api/me/privacy/consent", parent.accessToken(), """
                {"noticeVersion":%d,"acceptEssential":false,"choices":[{"studentId":"%s","photos":true,"whatsapp":true}]}"""
                .formatted(v1, arjun)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.acceptEssential").exists());
        api.post("/api/me/privacy/consent", parent.accessToken(), """
                {"noticeVersion":%d,"acceptEssential":true,"choices":[{"studentId":"%s","photos":true,"whatsapp":true}]}"""
                .formatted(v1, kabir)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['choices[0].studentId']").exists());
        api.post("/api/me/privacy/consent", parent.accessToken(), """
                {"noticeVersion":%d,"acceptEssential":true,"choices":[{"studentId":"%s","photos":true,"whatsapp":true}]}"""
                .formatted(v1 + 1, arjun)).andExpect(status().isConflict());

        acceptForBoth(v1);
        api.get("/api/me/privacy", parent.accessToken())
                .andExpect(jsonPath("$.needsConsent").value(false))
                .andExpect(jsonPath("$.children[0].purposes[*].status", contains("GIVEN", "GIVEN", "GIVEN")))
                .andExpect(jsonPath("$.children[1].purposes[*].status", contains("GIVEN", "DECLINED", "GIVEN")))
                .andExpect(jsonPath("$.children[0].purposes[*].current", everyItem(is(true))));

        // Withdraw WhatsApp for Arjun, then give it again: each decision is a new row.
        String change = "/api/me/privacy/children/" + arjun + "/consents/";
        api.put(change + "WHATSAPP", parent.accessToken(), """
                {"given":false}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children[0].purposes[2].status").value("WITHDRAWN"));
        // Asking for the state it already has records nothing.
        api.put(change + "WHATSAPP", parent.accessToken(), """
                {"given":false}""").andExpect(status().isOk());
        api.put(change + "WHATSAPP", parent.accessToken(), """
                {"given":true}""")
                .andExpect(jsonPath("$.children[0].purposes[2].status").value("GIVEN"));
        // Essential processing cannot be switched off here, and another family's child does not exist.
        api.put(change + "ESSENTIAL", parent.accessToken(), """
                {"given":false}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.purpose").exists());
        api.put("/api/me/privacy/children/" + kabir + "/consents/PHOTOS", parent.accessToken(), """
                {"given":true}""").andExpect(status().isNotFound());
        api.put(change + "NEWSLETTER", parent.accessToken(), """
                {"given":true}""").andExpect(status().isBadRequest());

        // The history: what, when, by whom, how and against which version.
        api.get("/api/privacy/students/" + arjun + "/consents", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeVersion").value(v1))
                .andExpect(jsonPath("$.history.length()").value(5))
                .andExpect(jsonPath("$.history[0].purpose").value("WHATSAPP"))
                .andExpect(jsonPath("$.history[0].action").value("GIVEN"))
                .andExpect(jsonPath("$.history[1].action").value("WITHDRAWN"))
                .andExpect(jsonPath("$.history[*].method", everyItem(is("ONLINE"))))
                .andExpect(jsonPath("$.history[*].noticeVersion", everyItem(is(v1))))
                .andExpect(jsonPath("$.history[*].givenByName", everyItem(is("Anitha Sharma"))));

        // A new version asks again; consent given before stays in the history.
        int v2 = publish("Photos on the website");
        api.get("/api/me/privacy", parent.accessToken())
                .andExpect(jsonPath("$.needsConsent").value(true))
                .andExpect(jsonPath("$.children[0].purposes[*].current", everyItem(is(false))));
        api.post("/api/me/privacy/consent", parent.accessToken(), """
                {"noticeVersion":%d,"acceptEssential":true,"choices":[{"studentId":"%s","photos":true,"whatsapp":true}]}"""
                .formatted(v1, arjun)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.noticeVersion").exists());
        acceptForBoth(v2);
        api.get("/api/privacy/students/" + arjun + "/consents", admin.accessToken())
                .andExpect(jsonPath("$.history.length()").value(8))
                .andExpect(jsonPath("$.history[0].noticeVersion").value(v2));

        // Another school's staff cannot see this child at all.
        School other = api.signup();
        Session outsider = api.login(other);
        api.get("/api/privacy/students/" + arjun + "/consents", outsider.accessToken())
                .andExpect(status().isNotFound());

        assertThat(events.stream(ConsentChanged.class)
                .filter(e -> e.studentId().equals(UUID.fromString(arjun)) && e.purpose() == PrivacyPurpose.WHATSAPP)
                .map(e -> e.action().name()))
                .containsExactly("GIVEN", "WITHDRAWN", "GIVEN", "GIVEN");
        api.get("/api/audit-events?limit=100", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("consent.given", "consent.withdrawn")));
    }

    @Test
    void staffRecordSignedPaperConsentAtAdmission() throws Exception {
        api.post("/api/privacy/students/" + kabir + "/consents", admin.accessToken(), """
                {"givenByName":"Farah Khan","signedOn":"%s","photos":false,"whatsapp":true}""".formatted(today()))
                .andExpect(status().isConflict());
        int v1 = publish(null);
        api.post("/api/privacy/students/" + kabir + "/consents", admin.accessToken(), """
                {"givenByName":"Farah Khan","signedOn":"%s","photos":false,"whatsapp":true}"""
                .formatted(today().plusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.signedOn").exists());
        api.post("/api/privacy/students/" + kabir + "/consents", admin.accessToken(), """
                {"givenByName":"","signedOn":"%s"}""".formatted(today()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.givenByName").exists())
                .andExpect(jsonPath("$.errors.photos").exists());
        api.post("/api/privacy/students/" + kabir + "/consents", admin.accessToken(), """
                {"givenByName":"Farah Khan","signedOn":"%s","paperReference":"Admission form 2026/17",
                 "photos":false,"whatsapp":true}""".formatted(today()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purposes[*].status", contains("GIVEN", "DECLINED", "GIVEN")))
                .andExpect(jsonPath("$.purposes[*].method", everyItem(is("PAPER"))))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].givenByName").value("Farah Khan"))
                .andExpect(jsonPath("$.history[0].recordedByName").exists())
                .andExpect(jsonPath("$.history[0].paperReference").value("Admission form 2026/17"))
                .andExpect(jsonPath("$.history[0].signedOn").value(today().toString()));

        // Paper consent for the current version counts: Farah is not asked again in the app.
        api.get("/api/me/privacy", otherParent.accessToken())
                .andExpect(jsonPath("$.needsConsent").value(false))
                .andExpect(jsonPath("$.children[0].purposes[1].status").value("DECLINED"));

        // The consent list: every student of the year, and how many active ones agreed to the current version.
        api.get("/api/privacy/consents?size=10", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.noticeVersion").value(v1))
                .andExpect(jsonPath("$.activeStudents").value(3))
                .andExpect(jsonPath("$.essentialGiven").value(1));
        api.get("/api/privacy/consents?q=Kabir", admin.accessToken())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].admissionNo").value("P-3"));

        // Another school's student id is not found.
        School other = api.signup();
        Session outsider = api.login(other);
        api.post("/api/privacy/students/" + kabir + "/consents", outsider.accessToken(), """
                {"givenByName":"X","signedOn":"%s","photos":true,"whatsapp":true}""".formatted(today()))
                .andExpect(status().isNotFound());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("consent.recorded")));
        assertThat(TestApi.read(api.get("/api/privacy/students/" + kabir + "/consents", admin.accessToken()),
                "$.history[0].method")).isEqualTo("PAPER");
    }
}
