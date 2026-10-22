package com.akshara.health;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * A school set up for the health tests through the API: a nurse, a principal, Tara (class teacher of Class 5 A),
 * Uma (class teacher of Class 6 A), Asha and Bala in 5 A, Chitra in 6 A, and Asha's mother Lata with a sign-in.
 */
final class HealthSchool {

    final TestApi api;
    final School school;
    final String domain;
    final Session admin;
    final Session nurse;
    final String nurseId;
    final Session principal;
    final String principalId;
    final Session teacher;
    final String teacherId;
    final Session otherTeacher;
    final String otherTeacherId;
    final String yearId;
    final String section5A;
    final String section6A;
    /** Asha Rao, whose mother Lata (9876501001) has a sign-in. */
    final String asha;
    final String bala;
    /** Chitra Nair, in 6 A: outside Tara's scope. */
    final String chitra;
    final Session parent;

    HealthSchool(TestApi api) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        domain = "@" + school.code() + ".akshara.test";
        SchoolFixtures fixtures = new SchoolFixtures(api);
        yearId = fixtures.currentYear(admin);
        String class5 = fixtures.schoolClass(admin, "Class 5");
        String class6 = fixtures.schoolClass(admin, "Class 6");
        teacherId = api.createUser(admin, "Tara Teacher", "tara" + domain, List.of("TEACHER"));
        teacher = api.login(school.code(), "tara" + domain, TestApi.PASSWORD);
        otherTeacherId = api.createUser(admin, "Uma Teacher", "uma" + domain, List.of("TEACHER"));
        otherTeacher = api.login(school.code(), "uma" + domain, TestApi.PASSWORD);
        section5A = TestApi.read(api.post("/api/academics/classes/" + class5 + "/sections", admin.accessToken(), """
                {"name":"A","capacity":40,"classTeacherId":"%s"}""".formatted(teacherId))
                .andExpect(status().isCreated()), "$.id");
        section6A = TestApi.read(api.post("/api/academics/classes/" + class6 + "/sections", admin.accessToken(), """
                {"name":"A","capacity":40,"classTeacherId":"%s"}""".formatted(otherTeacherId))
                .andExpect(status().isCreated()), "$.id");
        asha = fixtures.student(admin, section5A, "H-1", "Asha", "Rao", "Lata Rao", "9876501001");
        bala = fixtures.student(admin, section5A, "H-2", "Bala", "Iyer", "Uma Iyer", "9876501002");
        chitra = fixtures.student(admin, section6A, "H-3", "Chitra", "Nair", "Rekha Nair", "9876501003");
        nurseId = api.createUser(admin, "Mary Thomas", "mary" + domain, List.of("NURSE"));
        nurse = api.login(school.code(), "mary" + domain, TestApi.PASSWORD);
        principalId = api.createUser(admin, "Lakshmi Principal", "lakshmi" + domain, List.of("PRINCIPAL"));
        principal = api.login(school.code(), "lakshmi" + domain, TestApi.PASSWORD);
        String guardian = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted("lata" + domain, TestApi.PASSWORD))
                .andExpect(status().isOk());
        parent = api.login(school.code(), "lata" + domain, TestApi.PASSWORD);
    }

    /** Adds a medicine to the stock and returns its id. */
    String medicine(String name, int quantity) throws Exception {
        return TestApi.read(api.post("/api/health/infirmary/medicines", nurse.accessToken(), """
                {"name":"%s","unit":"tablet","quantity":%d,"reorderLevel":5}""".formatted(name, quantity))
                .andExpect(status().isCreated()), "$.id");
    }

    /** The nurse records a student's visit and returns its id. */
    String visit(String studentId, String complaint) throws Exception {
        return TestApi.read(api.post("/api/health/infirmary/visits", nurse.accessToken(), """
                {"personKind":"STUDENT","studentId":"%s","complaint":"%s"}""".formatted(studentId, complaint))
                .andExpect(status().isCreated()), "$.id");
    }
}
