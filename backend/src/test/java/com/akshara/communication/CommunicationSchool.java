package com.akshara.communication;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * A school for the circulars and calendar tests. Class 5 has sections A (class teacher Ravi) and B (Sita); Class 6 has
 * section A. Asha and Bala (5 A) are siblings with one mother, Rekha, who has a sign-in; Asha also has a father,
 * Suresh, without one, and her own student sign-in. Dev is in 5 B; Esha is in 6 A and her mother Uma has a sign-in.
 * Staff: the admin, Ravi and Sita (teachers), Lakshmi (principal) and Meena (accountant).
 */
final class CommunicationSchool {

    final TestApi api;
    final School school;
    final Session admin;
    final SchoolFixtures fixtures;
    final String class5;
    final String class6;
    final String s5A;
    final String s5B;
    final String s6A;
    final String asha;
    final String bala;
    final String dev;
    final String esha;

    CommunicationSchool(TestApi api, LocalDate today) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fixtures.year(admin, "This year", today.minusDays(150).toString(), today.plusDays(200).toString(), true);
        String ravi = api.createUser(admin, "Ravi Kumar", email("ravi"), List.of("TEACHER"));
        String sita = api.createUser(admin, "Sita Devi", email("sita"), List.of("TEACHER"));
        api.createUser(admin, "Lakshmi Iyer", email("principal"), List.of("PRINCIPAL"));
        api.createUser(admin, "Meena Reddy", email("accounts"), List.of("ACCOUNTANT"));
        class5 = fixtures.schoolClass(admin, "Class 5");
        class6 = fixtures.schoolClass(admin, "Class 6");
        s5A = section(class5, "A", ravi);
        s5B = section(class5, "B", sita);
        s6A = fixtures.section(admin, class6, "A", 40);
        asha = student(s5A, "A-1", "Asha", 1, "Rekha Rao", "9876500011");
        bala = student(s5A, "A-2", "Bala", 2, "Rekha Rao", "9876500011");
        dev = student(s5B, "B-1", "Dev", 1, "Devi Iyer", "9876500021");
        esha = student(s6A, "C-1", "Esha", 1, "Uma Nair", "9876500031");
        api.post("/api/students/" + asha + "/guardians", admin.accessToken(), """
                {"name":"Suresh Rao","relation":"FATHER","phone":"9876500019"}""").andExpect(status().isCreated());
        guardianSignIn(asha, "rekha");
        guardianSignIn(esha, "uma");
        api.createUser(admin, "Asha Rao", email("asha"), List.of("STUDENT"));
        api.post("/api/students/" + asha + "/sign-in", admin.accessToken(), """
                {"mode":"LINK","email":"%s"}""".formatted(email("asha"))).andExpect(status().isOk());
    }

    Session login(String name) throws Exception {
        return api.login(school.code(), email(name), TestApi.PASSWORD);
    }

    String email(String name) {
        return name + "@" + school.code() + ".akshara.test";
    }

    private void guardianSignIn(String student, String name) throws Exception {
        List<String> mothers = JsonPath.read(api.get("/api/students/" + student, admin.accessToken()).andReturn()
                .getResponse().getContentAsString(), "$.guardians[?(@.relation == 'MOTHER')].id");
        String mother = mothers.getFirst();
        api.post("/api/students/" + student + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email(name), TestApi.PASSWORD))
                .andExpect(status().isOk());
    }

    private String section(String classId, String name, String teacherId) throws Exception {
        return TestApi.read(api.post("/api/academics/classes/" + classId + "/sections", admin.accessToken(), """
                {"name":"%s","classTeacherId":"%s"}""".formatted(name, teacherId)).andExpect(status().isCreated()),
                "$.id");
    }

    private String student(String sectionId, String admissionNo, String firstName, int rollNo, String guardian,
            String phone) throws Exception {
        return TestApi.read(api.post("/api/students", admin.accessToken(), SchoolFixtures.studentJson(sectionId,
                admissionNo, firstName, "Rao", guardian, phone, rollNo)).andExpect(status().isCreated()), "$.id");
    }
}
