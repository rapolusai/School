package com.akshara.privacy;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * A school with two families: Anitha Sharma (Arjun and Diya, both in Class 5 A) and Farah Khan (Kabir), each parent
 * with a sign-in. Helpers publish the notice and raise requests the way the web app does.
 */
abstract class PrivacyTestBase extends IntegrationTest {

    static final String ANITHA_PHONE = "9876500001";
    static final String FARAH_PHONE = "9876500009";

    protected School school;
    protected Session admin;
    protected SchoolFixtures fixtures;
    protected String yearId;
    protected String classId;
    protected String sectionId;
    protected String arjun;
    protected String diya;
    protected String kabir;
    protected Session parent;
    protected Session otherParent;

    @BeforeEach
    void schoolWithTwoFamilies() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        yearId = fixtures.currentYear(admin);
        classId = fixtures.schoolClass(admin, "Class 5");
        sectionId = fixtures.section(admin, classId, "A", 40);
        arjun = fixtures.student(admin, sectionId, "P-1", "Arjun", "Sharma", "Anitha Sharma", ANITHA_PHONE);
        // Younger than Arjun, so the parent's children are always listed Arjun first.
        diya = TestApi.read(api.post("/api/students", admin.accessToken(), SchoolFixtures.studentJson(sectionId, "P-2",
                "Diya", "Sharma", "Anitha Sharma", ANITHA_PHONE, null).replace("2016-04-12", "2019-08-20"))
                .andExpect(status().isCreated()), "$.id");
        kabir = fixtures.student(admin, sectionId, "P-3", "Kabir", "Khan", "Farah Khan", FARAH_PHONE);
        parent = signIn(arjun, "anitha");
        otherParent = signIn(kabir, "farah");
    }

    /** Creates a sign-in for the student's first guardian and signs in with it. */
    protected Session signIn(String studentId, String who) throws Exception {
        String guardian = TestApi.read(api.get("/api/students/" + studentId, admin.accessToken()),
                "$.guardians[0].id");
        api.post("/api/students/" + studentId + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email(who), TestApi.PASSWORD))
                .andExpect(status().isOk());
        return api.login(school.code(), email(who), TestApi.PASSWORD);
    }

    protected Session member(String role, String who) throws Exception {
        api.createUser(admin, who + " Member", email(who), List.of(role));
        return api.login(school.code(), email(who), TestApi.PASSWORD);
    }

    protected String email(String who) {
        return who + "@" + school.code() + ".akshara.test";
    }

    protected void officer(Session session) throws Exception {
        api.put("/api/privacy/grievance-officer", session.accessToken(), """
                {"name":"Lakshmi Iyer","email":"dpo@%s.akshara.test","phone":"+91 40 2345 6789"}"""
                .formatted(school.code())).andExpect(status().isOk());
    }

    /** Publishes the next version (setting the officer first if needed) and returns its number. */
    protected int publish(String changeSummary) throws Exception {
        officer(admin);
        String summary = changeSummary == null ? "null" : "\"" + changeSummary + "\"";
        return Integer.parseInt(TestApi.read(api.post("/api/privacy/notice", admin.accessToken(), """
                {"bodyEn":"## Who we are\\nThe school.","bodyHi":"## हम कौन हैं\\nविद्यालय।","changeSummary":%s}"""
                .formatted(summary)).andExpect(status().isCreated()), "$.version"));
    }

    /** The parent accepts the current notice for both children: photos for Arjun only, WhatsApp for both. */
    protected void acceptForBoth(int version) throws Exception {
        api.post("/api/me/privacy/consent", parent.accessToken(), """
                {"noticeVersion":%d,"acceptEssential":true,"choices":[
                  {"studentId":"%s","photos":true,"whatsapp":true},
                  {"studentId":"%s","photos":false,"whatsapp":true}]}""".formatted(version, arjun, diya))
                .andExpect(status().isOk());
    }

    protected String submit(Session who, String type, String subject, String studentId, String details)
            throws Exception {
        return TestApi.read(api.post("/api/me/privacy/requests", who.accessToken(), """
                {"type":"%s","subject":"%s","studentId":%s,"details":%s}""".formatted(type, subject,
                studentId == null ? "null" : "\"" + studentId + "\"",
                details == null ? "null" : "\"" + details + "\"")).andExpect(status().isCreated()), "$.id");
    }

    protected static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }
}
