package com.akshara.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Each person's own language, and messages to a parent in the language the parent chose. */
class PreferencesIT extends IntegrationTest {

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private School school;
    private Session admin;

    @BeforeEach
    void school() throws Exception {
        school = api.signup();
        admin = api.login(school);
    }

    @Test
    void aPersonReadsAndChangesOnlyTheirOwnLanguage() throws Exception {
        api.get("/api/me/preferences", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value(nullValue()));
        api.put("/api/me/preferences", admin.accessToken(), """
                {"language":"te"}""").andExpect(status().isOk()).andExpect(jsonPath("$.language").value("te"));
        api.get("/api/me/preferences", admin.accessToken()).andExpect(jsonPath("$.language").value("te"));
        api.put("/api/me/preferences", admin.accessToken(), """
                {"language":"hi"}""").andExpect(status().isOk()).andExpect(jsonPath("$.language").value("hi"));

        api.put("/api/me/preferences", admin.accessToken(), """
                {"language":"fr"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.language").exists());
        api.put("/api/me/preferences", admin.accessToken(), "{}").andExpect(status().isBadRequest());
        api.get("/api/me/preferences", null).andExpect(status().isUnauthorized());

        api.get("/api/audit-events?limit=10", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("user_language.updated")));

        // Another school's admin sees only their own (empty) choice.
        Session otherAdmin = api.login(api.signup());
        api.get("/api/me/preferences", otherAdmin.accessToken()).andExpect(jsonPath("$.language").value(nullValue()));
    }

    @Test
    void aParentWhoChoseTeluguGetsAlertsInTeluguWhateverTheSchoolDefault() throws Exception {
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.year(admin, "This year", today.minusDays(100).toString(), today.plusDays(200).toString(), true);
        String section = fixtures.section(admin, fixtures.schoolClass(admin, "Class 5"), "A", null);
        String asha = TestApi.read(api.post("/api/students", admin.accessToken(), SchoolFixtures.studentJson(section,
                "N-1", "Asha", "Rao", "Lata Rao", "9876500001", 1)).andExpect(status().isCreated()), "$.id");

        // The school's default is Hindi; Telugu is now an allowed school default too.
        api.put("/api/notifications/settings", admin.accessToken(), settings("te")).andExpect(status().isOk())
                .andExpect(jsonPath("$.alertLanguage").value("te"));
        api.put("/api/notifications/settings", admin.accessToken(), settings("hi")).andExpect(status().isOk());

        // Before the parent chooses, the alert follows the school (Hindi).
        mark(section, asha, today.minusDays(1));
        String first = TestApi.read(api.get("/api/messages", admin.accessToken()), "$.items[0].id");
        api.get("/api/messages/" + first, admin.accessToken()).andExpect(jsonPath("$.language").value("hi"));

        // The parent signs in and chooses Telugu.
        String guardian = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        String email = "lata@" + school.code() + ".akshara.test";
        api.post("/api/students/" + asha + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email, TestApi.PASSWORD))
                .andExpect(status().isOk());
        Session lata = api.login(school.code(), email, TestApi.PASSWORD);
        api.put("/api/me/preferences", lata.accessToken(), """
                {"language":"te"}""").andExpect(status().isOk());

        mark(section, asha, today);
        String id = TestApi.read(api.get("/api/messages?relatedId=" + asha, admin.accessToken())
                .andExpect(jsonPath("$.total").value(2)), "$.items[0].id");
        String body = TestApi.read(api.get("/api/messages/" + id, admin.accessToken())
                .andExpect(jsonPath("$.language").value("te")), "$.body");
        assertThat(body).startsWith("ప్రియమైన Lata Rao, Asha Rao (Class 5 A)").contains("గైర్హాజరు");
    }

    private void mark(String section, String student, LocalDate date) throws Exception {
        api.put("/api/attendance/registers/" + section + "/" + date, admin.accessToken(), """
                {"entries":[{"studentId":"%s","status":"ABSENT"}]}""".formatted(student))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alertsQueued").value(1));
    }

    private static String settings(String language) {
        return """
                {"absenceAlertsEnabled":true,"absenceAlertChannel":"SMS","alertLanguage":"%s","quietHoursEnabled":false,
                 "quietHoursStart":"21:00","quietHoursEnd":"07:00"}""".formatted(language);
    }
}
