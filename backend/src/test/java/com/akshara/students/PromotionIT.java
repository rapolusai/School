package com.akshara.students;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
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

/** The year-end move of whole sections into the next year, and Class 10 leaving as alumni. */
class PromotionIT extends IntegrationTest {

    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String lastYear;
    private String nextYear;
    private String class1A;
    private String class2A;
    private String class10A;

    @BeforeEach
    void twoYears() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        lastYear = fixtures.year(admin, "2025-26", "2025-06-01", "2026-03-31", true);
        class1A = fixtures.section(admin, fixtures.schoolClass(admin, "Class 1"), "A", 30);
        class2A = fixtures.section(admin, fixtures.schoolClass(admin, "Class 2"), "A", 3);
        class10A = fixtures.section(admin, fixtures.schoolClass(admin, "Class 10"), "A", 30);
        nextYear = fixtures.year(admin, "2026-27", "2026-06-01", "2027-03-31", false);
    }

    @Test
    void aSectionMovesUpWithRollNumbersInNameOrder() throws Exception {
        String zara = admit(class1A, "P-1", "Zara", 1);
        admit(class1A, "P-2", "Aarav", 2);
        admit(class1A, "P-3", "Meera", 3);
        String left = admit(class1A, "P-4", "Kabir", 4);
        api.post("/api/students/" + left + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"2025-12-01","reason":"Moved"}""").andExpect(status().isOk());

        api.post("/api/students/promote", admin.accessToken(), """
                {"fromSectionId":"%s","toSectionId":"%s","toYearId":"%s"}""".formatted(class1A, class2A, nextYear))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promoted").value(3))
                .andExpect(jsonPath("$.graduated").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(0));

        api.get("/api/students?yearId=" + nextYear + "&sectionId=" + class2A, admin.accessToken())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].firstName", contains("Aarav", "Meera", "Zara")))
                .andExpect(jsonPath("$.items[*].rollNo", contains(1, 2, 3)));
        // Last year's class list is unchanged.
        api.get("/api/students?sectionId=" + class1A, admin.accessToken()).andExpect(jsonPath("$.total").value(4));

        // Running it again changes nothing.
        api.post("/api/students/promote", admin.accessToken(), """
                {"fromSectionId":"%s","fromYearId":"%s","toSectionId":"%s","toYearId":"%s"}"""
                .formatted(class1A, lastYear, class2A, nextYear))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promoted").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(3))
                .andExpect(jsonPath("$.skipped[0].reason", containsString("2026-27")));

        api.post("/api/academics/years/" + nextYear + "/set-current", admin.accessToken(), null)
                .andExpect(status().isOk());
        api.get("/api/students/" + zara, admin.accessToken())
                .andExpect(jsonPath("$.currentEnrollment.className").value("Class 2"))
                .andExpect(jsonPath("$.currentEnrollment.rollNo").value(3))
                .andExpect(jsonPath("$.enrollments.length()").value(2))
                .andExpect(jsonPath("$.enrollments[*].academicYearName", contains("2026-27", "2025-26")));
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'student.promoted')].details.to",
                        hasItems("Class 2 A (2026-27)")));
    }

    @Test
    void promotionChecksTheTargetYearAndSeats() throws Exception {
        admit(class1A, "Q-1", "Asha", null);
        admit(class1A, "Q-2", "Bala", null);

        api.post("/api/students/promote", admin.accessToken(), """
                {"fromSectionId":"%s","toSectionId":"%s","toYearId":"%s"}""".formatted(class1A, class2A, lastYear))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.toYearId").exists());
        api.post("/api/students/promote", admin.accessToken(), """
                {"fromSectionId":"%s","toSectionId":"%s"}""".formatted(class1A, class2A))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.toYearId").exists());
        api.post("/api/students/promote", admin.accessToken(), """
                {"fromSectionId":"%s","toYearId":"%s"}""".formatted(class1A, nextYear))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.toSectionId").exists());
        api.post("/api/students/promote", admin.accessToken(), """
                {"toSectionId":"%s","toYearId":"%s"}""".formatted(class2A, nextYear))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fromSectionId").exists());

        // Class 2 A seats three; two are taken by new admissions next year, so two more do not fit.
        api.post("/api/academics/years/" + nextYear + "/set-current", admin.accessToken(), null)
                .andExpect(status().isOk());
        admit(class2A, "Q-3", "Chitra", null);
        admit(class2A, "Q-4", "Deepa", null);
        api.post("/api/students/promote", admin.accessToken(), """
                {"fromSectionId":"%s","fromYearId":"%s","toSectionId":"%s","toYearId":"%s"}"""
                .formatted(class1A, lastYear, class2A, nextYear))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.toSectionId", containsString("full")));
        api.get("/api/students?sectionId=" + class2A, admin.accessToken()).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void classTenGraduatesAsAlumni() throws Exception {
        String senior = admit(class10A, "R-1", "Tanvi", 1);
        api.post("/api/students/promote", admin.accessToken(), """
                {"fromSectionId":"%s","graduate":true}""".formatted(class10A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graduated").value(1))
                .andExpect(jsonPath("$.promoted").value(0));
        api.get("/api/students/" + senior, admin.accessToken())
                .andExpect(jsonPath("$.status").value("ALUMNI"))
                .andExpect(jsonPath("$.leftOn").value("2026-03-31"));
        api.get("/api/students?status=ALUMNI", admin.accessToken()).andExpect(jsonPath("$.total").value(1));
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("student.graduated")));
        // Alumni cannot be transferred.
        api.post("/api/students/" + senior + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"2026-04-01","reason":"x"}""").andExpect(status().isConflict());
    }

    @Test
    void onlyStudentManagersPromoteAndOnlyTheirOwnSchool() throws Exception {
        admit(class1A, "S-1", "Asha", null);
        String body = """
                {"fromSectionId":"%s","toSectionId":"%s","toYearId":"%s"}""".formatted(class1A, class2A, nextYear);
        String email = "teacher@" + school.code() + ".akshara.test";
        api.createUser(admin, "Tara Teacher", email, List.of("TEACHER"));
        Session teacher = api.login(school.code(), email, TestApi.PASSWORD);
        api.post("/api/students/promote", teacher.accessToken(), body).andExpect(status().isForbidden());

        School other = api.signup();
        Session adminB = api.login(other);
        fixtures.year(adminB, "2025-26", "2025-06-01", "2026-03-31", true);
        api.post("/api/students/promote", adminB.accessToken(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fromSectionId").exists());
        api.get("/api/students?yearId=" + nextYear, admin.accessToken()).andExpect(jsonPath("$.total").value(0));
    }

    private String admit(String sectionId, String admissionNo, String firstName, Integer rollNo) throws Exception {
        return TestApi.read(api.post("/api/students", admin.accessToken(), """
                {"admissionNo":"%s","firstName":"%s","dateOfBirth":"2015-08-20","gender":"FEMALE",
                 "admissionDate":"2025-06-02","sectionId":"%s","rollNo":%s,
                 "guardians":[{"name":"Parent of %s","relation":"GUARDIAN","phone":"9876502%03d","primary":true}]}
                """.formatted(admissionNo, firstName, sectionId, rollNo, firstName,
                Math.abs(admissionNo.hashCode()) % 1000)).andExpect(status().isCreated()), "$.id");
    }
}
