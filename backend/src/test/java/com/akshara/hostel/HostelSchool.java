package com.akshara.hostel;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * A school set up for the hostel tests through the API: a warden (Wasim) of the boys' hostel, a second warden
 * (Wendy) of the girls' hostel, a principal (reads), a teacher (no hostel access), two students in Class 5 A, and
 * the first student's mother with a sign-in. The boys' hostel has block A with room 101 (two beds A and B).
 */
final class HostelSchool {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    final TestApi api;
    final School school;
    final String domain;
    final Session admin;
    final Session warden;
    final String wardenId;
    final Session warden2;
    final String warden2Id;
    final Session principal;
    final Session teacher;
    final String section;
    /** Asha Rao, whose mother Lata (9876501001) has a sign-in. */
    final String asha;
    final String bala;
    final Session parent;
    final String boys;
    final String girls;
    final String blockA;
    final String room101;
    final String bedA;
    final String bedB;

    HostelSchool(TestApi api) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        domain = "@" + school.code() + ".akshara.test";
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        String classId = fixtures.schoolClass(admin, "Class 5");
        section = fixtures.section(admin, classId, "A", 40);
        asha = fixtures.student(admin, section, "HO-1", "Asha", "Rao", "Lata Rao", "9876501001");
        bala = fixtures.student(admin, section, "HO-2", "Bala", "Iyer", "Uma Iyer", "9876501002");
        wardenId = api.createUser(admin, "Wasim Warden", "wasim" + domain, List.of("WARDEN"));
        warden = api.login(school.code(), "wasim" + domain, TestApi.PASSWORD);
        warden2Id = api.createUser(admin, "Wendy Warden", "wendy" + domain, List.of("WARDEN"));
        warden2 = api.login(school.code(), "wendy" + domain, TestApi.PASSWORD);
        api.createUser(admin, "Pria Principal", "pria" + domain, List.of("PRINCIPAL"));
        principal = api.login(school.code(), "pria" + domain, TestApi.PASSWORD);
        api.createUser(admin, "Tara Teacher", "tara" + domain, List.of("TEACHER"));
        teacher = api.login(school.code(), "tara" + domain, TestApi.PASSWORD);
        String guardian = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted("lata" + domain, TestApi.PASSWORD))
                .andExpect(status().isOk());
        parent = api.login(school.code(), "lata" + domain, TestApi.PASSWORD);
        boys = hostel("Boys Hostel", "BOYS", wardenId);
        girls = hostel("Girls Hostel", "GIRLS", warden2Id);
        blockA = TestApi.read(api.post("/api/hostel/hostels/" + boys + "/blocks", admin.accessToken(), """
                {"name":"Block A","floors":2}""").andExpect(status().isCreated()), "$.id");
        room101 = room(boys, blockA, "101", 2, 250000);
        String beds = api.post("/api/hostel/hostels/" + boys + "/rooms/" + room101 + "/beds", admin.accessToken(),
                """
                {"labels":["A","B"]}""").andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        bedA = com.jayway.jsonpath.JsonPath.read(beds, "$[0].id");
        bedB = com.jayway.jsonpath.JsonPath.read(beds, "$[1].id");
    }

    String hostel(String name, String type, String wardenUserId) throws Exception {
        return TestApi.read(api.post("/api/hostel/hostels", admin.accessToken(), """
                {"name":"%s","type":"%s","wardenUserId":%s}""".formatted(name, type,
                wardenUserId == null ? "null" : "\"" + wardenUserId + "\"")).andExpect(status().isCreated()), "$.id");
    }

    String room(String hostelId, String blockId, String number, int capacity, long feePaise) throws Exception {
        return TestApi.read(api.post("/api/hostel/hostels/" + hostelId + "/blocks/" + blockId + "/rooms",
                admin.accessToken(), """
                {"floor":1,"number":"%s","capacity":%d,"type":"DOUBLE","monthlyFeePaise":%d}"""
                .formatted(number, capacity, feePaise)).andExpect(status().isCreated()), "$.id");
    }

    /** Allots the student to the bed from today with the guardian's consent; returns the allotment id. */
    String allot(String studentId, String bedId) throws Exception {
        return TestApi.read(api.post("/api/hostel/allotments", admin.accessToken(), """
                {"studentId":"%s","bedId":"%s","fromDate":"%s","guardianConsent":true}"""
                .formatted(studentId, bedId, TODAY)).andExpect(status().isCreated()), "$.id");
    }

    /** The parent asks for an outing for Asha tomorrow; returns the pass id. */
    String requestOuting() throws Exception {
        return TestApi.read(api.post("/api/me/children/" + asha + "/hostel-passes", parent.accessToken(), """
                {"kind":"OUTING","fromDate":"%s","reason":"Family lunch"}""".formatted(TODAY.plusDays(1)))
                .andExpect(status().isCreated()), "$.id");
    }
}
