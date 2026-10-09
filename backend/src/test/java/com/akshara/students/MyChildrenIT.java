package com.akshara.students;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Parent and student sign-ins, and what each of them sees about themselves. */
class MyChildrenIT extends IntegrationTest {

    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String class5A;
    private String class2A;
    private String teacherId;

    @BeforeEach
    void schoolWithClasses() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        teacherId = api.createUser(admin, "Ravi Kumar", email("ravi"), List.of("TEACHER"));
        String class5 = fixtures.schoolClass(admin, "Class 5");
        class5A = TestApi.read(api.post("/api/academics/classes/" + class5 + "/sections", admin.accessToken(), """
                {"name":"A","classTeacherId":"%s"}""".formatted(teacherId)).andExpect(status().isCreated()), "$.id");
        class2A = fixtures.section(admin, fixtures.schoolClass(admin, "Class 2"), "A", null);
    }

    @Test
    void aParentSeesEachOfTheirChildren() throws Exception {
        String arjun = TestApi.read(api.post("/api/students", admin.accessToken(), """
                {"admissionNo":"M-1","firstName":"Arjun","lastName":"Sharma","dateOfBirth":"2015-05-14",
                 "gender":"MALE","admissionDate":"2026-06-02","sectionId":"%s","rollNo":4,
                 "guardians":[{"name":"Anitha Sharma","relation":"MOTHER","phone":"9876500001","primary":true}]}"""
                .formatted(class5A)).andExpect(status().isCreated()), "$.id");
        fixtures.student(admin, class2A, "M-2", "Diya", "Sharma", "Anitha Sharma", "9876500001");
        fixtures.student(admin, class2A, "M-3", "Someone", "Else", "Other Parent", "9876500009");
        String mother = TestApi.read(api.get("/api/students/" + arjun, admin.accessToken()), "$.guardians[0].id");

        api.post("/api/students/" + arjun + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"short"}""".formatted(email("anitha")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        api.post("/api/students/" + arjun + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("anitha"), TestApi.PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasSignIn").value(true))
                .andExpect(jsonPath("$.signInEmail").value(email("anitha")));
        api.post("/api/students/" + arjun + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"LINK","email":"%s"}""".formatted(email("anitha")))
                .andExpect(status().isConflict());

        Session parent = api.login(school.code(), email("anitha"), TestApi.PASSWORD);
        api.get("/api/me/children", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Eldest first.
                .andExpect(jsonPath("$[*].fullName", contains("Arjun Sharma", "Diya Sharma")))
                .andExpect(jsonPath("$[0].className").value("Class 5"))
                .andExpect(jsonPath("$[0].sectionName").value("A"))
                .andExpect(jsonPath("$[0].rollNo").value(4))
                .andExpect(jsonPath("$[0].classTeacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$[0].academicYearName").value("2026-27"))
                .andExpect(jsonPath("$[1].className").value("Class 2"));
        // Parents never reach the school's student records.
        api.get("/api/students", parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/students/" + arjun, parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/me/student", parent.accessToken()).andExpect(status().isNotFound());

        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", org.hamcrest.Matchers.hasItem("guardian.sign_in_linked")));
    }

    @Test
    void aStudentSeesTheirOwnRecord() throws Exception {
        String arjun = fixtures.student(admin, class5A, "S-1", "Arjun", "Sharma", "Anitha Sharma", "9876500001");
        api.createUser(admin, "Arjun Sharma", email("arjun"), List.of("STUDENT"));
        api.createUser(admin, "Not A Student", email("notstudent"), List.of("PARENT"));
        Session student = api.login(school.code(), email("arjun"), TestApi.PASSWORD);
        api.get("/api/me/student", student.accessToken()).andExpect(status().isNotFound());

        api.post("/api/students/" + arjun + "/sign-in", admin.accessToken(), """
                {"mode":"LINK","email":"%s"}""".formatted(email("notstudent")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
        api.post("/api/students/" + arjun + "/sign-in", admin.accessToken(), """
                {"mode":"LINK","email":"%s"}""".formatted(email("nobody")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
        api.post("/api/students/" + arjun + "/sign-in", admin.accessToken(), """
                {"mode":"LINK","email":"%s"}""".formatted(email("arjun")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasSignIn").value(true))
                .andExpect(jsonPath("$.signInEmail").value(email("arjun")));

        api.get("/api/me/student", student.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(arjun))
                .andExpect(jsonPath("$.className").value("Class 5"))
                .andExpect(jsonPath("$.classTeacherName").value("Ravi Kumar"));
        api.get("/api/students", student.accessToken()).andExpect(status().isForbidden());
        api.get("/api/me/children", student.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void aParentWithoutLinkedChildrenSeesNone() throws Exception {
        api.createUser(admin, "New Parent", email("newparent"), List.of("PARENT"));
        Session parent = api.login(school.code(), email("newparent"), TestApi.PASSWORD);
        api.get("/api/me/children", parent.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        Session teacher = api.login(school.code(), email("ravi"), TestApi.PASSWORD);
        api.get("/api/me/children", teacher.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void signInsCannotReachIntoAnotherSchool() throws Exception {
        String arjun = fixtures.student(admin, class5A, "X-1", "Arjun", "Sharma", "Anitha Sharma", "9876500001");
        School other = api.signup();
        Session adminB = api.login(other);
        String parentB = "parent@" + other.code() + ".akshara.test";
        api.createUser(adminB, "Parent B", parentB, List.of("PARENT"));
        // School A cannot link a sign-in that belongs to school B.
        String mother = TestApi.read(api.get("/api/students/" + arjun, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + arjun + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"LINK","email":"%s"}""".formatted(parentB))
                .andExpect(status().isBadRequest());
        api.post("/api/students/" + arjun + "/guardians/" + mother + "/sign-in", adminB.accessToken(), """
                {"mode":"LINK","email":"%s"}""".formatted(parentB))
                .andExpect(status().isNotFound());
        Session parent = api.login(other.code(), parentB, TestApi.PASSWORD);
        api.get("/api/me/children", parent.accessToken()).andExpect(jsonPath("$.length()").value(0));
    }

    private String email(String name) {
        return name + "@" + school.code() + ".akshara.test";
    }
}
