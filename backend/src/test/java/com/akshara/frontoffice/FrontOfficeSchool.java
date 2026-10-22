package com.akshara.frontoffice;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * A school set up for the front office tests through the API: a front office clerk, a security guard, a teacher who
 * is Class 5 A's class teacher, two students in that section, and the first student's mother with a sign-in.
 */
final class FrontOfficeSchool {

    final TestApi api;
    final School school;
    final String domain;
    final Session admin;
    final Session office;
    final String officeId;
    final Session security;
    final String securityId;
    final Session teacher;
    final String teacherId;
    final String yearId;
    final String classId;
    final String section;
    /** Asha Rao, whose mother Lata (9876501001) has a sign-in. */
    final String asha;
    final String bala;
    final Session parent;

    FrontOfficeSchool(TestApi api) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        domain = "@" + school.code() + ".akshara.test";
        SchoolFixtures fixtures = new SchoolFixtures(api);
        yearId = fixtures.currentYear(admin);
        classId = fixtures.schoolClass(admin, "Class 5");
        teacherId = api.createUser(admin, "Tara Teacher", "tara" + domain, List.of("TEACHER"));
        teacher = api.login(school.code(), "tara" + domain, TestApi.PASSWORD);
        section = TestApi.read(api.post("/api/academics/classes/" + classId + "/sections", admin.accessToken(), """
                {"name":"A","capacity":40,"classTeacherId":"%s"}""".formatted(teacherId))
                .andExpect(status().isCreated()), "$.id");
        asha = fixtures.student(admin, section, "FO-1", "Asha", "Rao", "Lata Rao", "9876501001");
        bala = fixtures.student(admin, section, "FO-2", "Bala", "Iyer", "Uma Iyer", "9876501002");
        officeId = api.createUser(admin, "Olivia Office", "olivia" + domain, List.of("FRONT_OFFICE"));
        office = api.login(school.code(), "olivia" + domain, TestApi.PASSWORD);
        securityId = api.createUser(admin, "Sam Security", "sam" + domain, List.of("SECURITY"));
        security = api.login(school.code(), "sam" + domain, TestApi.PASSWORD);
        String guardian = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted("lata" + domain, TestApi.PASSWORD))
                .andExpect(status().isOk());
        parent = api.login(school.code(), "lata" + domain, TestApi.PASSWORD);
    }

    /** A pickup a few minutes from now, so it is today in India unless the test runs right at midnight. */
    static Instant soon() {
        return Instant.now().plus(Duration.ofMinutes(5));
    }

    /** Checks a visitor in at the gate and returns the visitor's id. */
    String visitor(Session by, String name, String phone, String purpose) throws Exception {
        return TestApi.read(api.post("/api/frontoffice/visitors", by.accessToken(), """
                {"name":"%s","phone":%s,"purpose":"%s","peopleCount":1,"idType":"NONE"}""".formatted(name,
                phone == null ? "null" : "\"" + phone + "\"", purpose)).andExpect(status().isCreated()), "$.id");
    }

    /** The parent asks for Asha to be picked up early by herself, and returns the pass id. */
    String requestPickup() throws Exception {
        return TestApi.read(api.post("/api/me/children/" + asha + "/gate-passes", parent.accessToken(), """
                {"pickupAt":"%s","reason":"Dentist appointment","collectorKind":"SELF"}""".formatted(soon()))
                .andExpect(status().isCreated()), "$.id");
    }

    /** The office approves the pass; the OTP goes to Lata's phone through the simulated message log. */
    void approve(String passId) throws Exception {
        api.post("/api/frontoffice/gate-passes/" + passId + "/approve", office.accessToken(), """
                {"note":"Mother confirmed on the phone"}""").andExpect(status().isOk());
    }

    /** Reads the OTP out of the simulated message sent to the guardian, the way the e2e test does. */
    String otpOf(String passId) throws Exception {
        String messageId = TestApi.read(api.get("/api/messages?relatedId=" + passId, admin.accessToken())
                .andExpect(status().isOk()), "$.items[0].id");
        String body = TestApi.read(api.get("/api/messages/" + messageId, admin.accessToken()), "$.body");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("OTP ([0-9]{6})").matcher(body);
        if (!m.find()) {
            throw new AssertionError("No OTP in the message: " + body);
        }
        return m.group(1);
    }

    static String releaseJson(String otp) {
        return """
                {"otp":"%s","collectedByName":"Lata Rao"}""".formatted(otp);
    }
}
