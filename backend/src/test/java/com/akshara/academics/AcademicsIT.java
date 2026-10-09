package com.akshara.academics;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
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

/** School setup: academic years, classes, sections, subjects and who may change them. */
class AcademicsIT extends IntegrationTest {

    private School school;
    private Session admin;
    private SchoolFixtures fixtures;

    @BeforeEach
    void school() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
    }

    @Test
    void aNewSchoolStartsWithNoYearsClassesOrSubjects() throws Exception {
        api.get("/api/academics/years", admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        api.get("/api/academics/classes", admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        api.get("/api/academics/subjects", admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void academicYearsCannotOverlapAndExactlyOneIsCurrent() throws Exception {
        String first = fixtures.year(admin, "2025-26", "2025-06-01", "2026-03-31", false);
        // The first year becomes current even when not asked.
        api.get("/api/academics/years", admin.accessToken())
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[0].current").value(true));

        api.post("/api/academics/years", admin.accessToken(), """
                {"name":"2025-26 B","startsOn":"2026-03-01","endsOn":"2027-02-28"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.startsOn", containsString("overlap 2025-26")));
        api.post("/api/academics/years", admin.accessToken(), """
                {"name":"2026-27","startsOn":"2027-03-31","endsOn":"2026-06-01"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.endsOn").exists());
        api.post("/api/academics/years", admin.accessToken(), """
                {"name":"2025-26","startsOn":"2030-06-01","endsOn":"2031-03-31"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/academics/years", admin.accessToken(), """
                {"name":"","startsOn":null,"endsOn":"2031-03-31"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.startsOn").exists());

        String second = fixtures.year(admin, "2026-27", "2026-06-01", "2027-03-31", false);
        api.get("/api/academics/years", admin.accessToken())
                .andExpect(jsonPath("$[0].name").value("2026-27"))
                .andExpect(jsonPath("$[0].current").value(false))
                .andExpect(jsonPath("$[1].current").value(true));

        api.post("/api/academics/years/" + second + "/set-current", admin.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true));
        api.get("/api/academics/years", admin.accessToken())
                .andExpect(jsonPath("$[?(@.current == true)].id", contains(second)));

        api.put("/api/academics/years/" + first, admin.accessToken(), """
                {"name":"2025-2026","startsOn":"2025-04-01","endsOn":"2026-03-31"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("2025-2026"))
                .andExpect(jsonPath("$.startsOn").value("2025-04-01"));
        api.put("/api/academics/years/" + first, admin.accessToken(), """
                {"name":"2025-2026","startsOn":"2025-04-01","endsOn":"2026-07-01"}""")
                .andExpect(status().isConflict());

        api.delete("/api/academics/years/" + second, admin.accessToken())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("In use"));
        api.delete("/api/academics/years/" + first, admin.accessToken()).andExpect(status().isNoContent());

        api.get("/api/audit-events?limit=200", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("academic_year.created", "academic_year.set_current",
                        "academic_year.updated", "academic_year.deleted")));
    }

    @Test
    void classesSectionsAndSubjects() throws Exception {
        String teacher = api.createUser(admin, "Tara Teacher", "tara@" + school.code() + ".akshara.test",
                List.of("TEACHER"));
        String accountant = api.createUser(admin, "Anil Accounts", "anil@" + school.code() + ".akshara.test",
                List.of("ACCOUNTANT"));

        String classOne = fixtures.schoolClass(admin, "Class 1");
        api.post("/api/academics/classes", admin.accessToken(), """
                {"name":"class 1"}""").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/academics/classes", admin.accessToken(), """
                {"name":"  "}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());

        api.post("/api/academics/classes/" + classOne + "/sections", admin.accessToken(), """
                {"name":"A","capacity":0}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.capacity").value("Use a number from 1 to 500."));
        api.post("/api/academics/classes/" + classOne + "/sections", admin.accessToken(), """
                {"name":"A","capacity":30,"classTeacherId":"%s"}""".formatted(accountant))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.classTeacherId").exists());
        String sectionA = TestApi.read(api.post("/api/academics/classes/" + classOne + "/sections",
                admin.accessToken(), """
                        {"name":"A","capacity":30,"classTeacherId":"%s"}""".formatted(teacher))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.className").value("Class 1"))
                .andExpect(jsonPath("$.classTeacher.name").value("Tara Teacher"))
                .andExpect(jsonPath("$.studentCount").value(0)), "$.id");
        api.post("/api/academics/classes/" + classOne + "/sections", admin.accessToken(), """
                {"name":"a"}""").andExpect(status().isConflict());
        String sectionB = fixtures.section(admin, classOne, "B", null);

        api.put("/api/academics/sections/" + sectionB, admin.accessToken(), """
                {"name":"B","capacity":25,"classTeacherId":"%s"}""".formatted(teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(25))
                .andExpect(jsonPath("$.classTeacher.id").value(teacher));
        api.get("/api/academics/teachers", admin.accessToken())
                .andExpect(jsonPath("$[*].id", hasItem(teacher)))
                .andExpect(jsonPath("$[*].id", not(hasItem(accountant))));

        String english = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"English","code":"ENG"}""").andExpect(status().isCreated()), "$.id");
        String maths = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"Mathematics","code":"MAT"}""").andExpect(status().isCreated()), "$.id");
        api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"English Literature","code":"eng"}""").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.code").exists());
        api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"Art","code":"A R T"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").exists());

        api.put("/api/academics/classes/" + classOne + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s","%s"]}""".formatted(english, maths))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems("English", "Mathematics")));
        api.get("/api/academics/classes", admin.accessToken())
                .andExpect(jsonPath("$[0].name").value("Class 1"))
                .andExpect(jsonPath("$[0].sections.length()").value(2))
                .andExpect(jsonPath("$[0].subjects.length()").value(2));
        api.get("/api/academics/subjects", admin.accessToken())
                .andExpect(jsonPath("$[?(@.name == 'English')].classCount", contains(1)));

        // Nothing in use can be deleted.
        api.delete("/api/academics/subjects/" + english, admin.accessToken()).andExpect(status().isConflict());
        api.delete("/api/academics/classes/" + classOne, admin.accessToken()).andExpect(status().isConflict());

        api.put("/api/academics/classes/" + classOne + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s"]}""".formatted(maths)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        api.delete("/api/academics/subjects/" + english, admin.accessToken()).andExpect(status().isNoContent());
        api.delete("/api/academics/sections/" + sectionA, admin.accessToken()).andExpect(status().isNoContent());
        api.delete("/api/academics/sections/" + sectionB, admin.accessToken()).andExpect(status().isNoContent());
        api.delete("/api/academics/classes/" + classOne, admin.accessToken()).andExpect(status().isNoContent());

        api.get("/api/audit-events?limit=200", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("class.created", "section.created", "section.updated",
                        "subject.created", "class.subjects_updated", "subject.deleted", "section.deleted",
                        "class.deleted")));
    }

    @Test
    void sectionsInUseCannotBeDeletedOrShrunkBelowTheirStudents() throws Exception {
        fixtures.currentYear(admin);
        String classId = fixtures.schoolClass(admin, "Class 2");
        String section = fixtures.section(admin, classId, "A", 2);
        fixtures.student(admin, section, "ADM-1", "Diya", "Rao", "Lakshmi Rao", "9876501001");
        fixtures.student(admin, section, "ADM-2", "Kabir", null, "Imran Khan", "9876501002");

        api.put("/api/academics/sections/" + section, admin.accessToken(), """
                {"name":"A","capacity":1}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.capacity").exists());
        api.delete("/api/academics/sections/" + section, admin.accessToken()).andExpect(status().isConflict());
        api.get("/api/academics/classes/" + classId, admin.accessToken())
                .andExpect(jsonPath("$.sections[0].studentCount").value(2));
    }

    @Test
    void staffCanReadTheSetupButOnlyAdminsChangeIt() throws Exception {
        String classId = fixtures.schoolClass(admin, "Class 3");
        for (String role : List.of("TEACHER", "ACCOUNTANT", "FRONT_OFFICE", "PRINCIPAL")) {
            String email = role.toLowerCase() + "@" + school.code() + ".akshara.test";
            api.createUser(admin, "Staff " + role, email, List.of(role));
            Session staff = api.login(school.code(), email, TestApi.PASSWORD);
            api.get("/api/academics/classes", staff.accessToken()).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(classId));
            api.get("/api/academics/years", staff.accessToken()).andExpect(status().isOk());
            api.post("/api/academics/classes", staff.accessToken(), """
                    {"name":"Class 9"}""").andExpect(status().isForbidden());
            api.put("/api/academics/classes/" + classId, staff.accessToken(), """
                    {"name":"Class 9"}""").andExpect(status().isForbidden());
            api.delete("/api/academics/classes/" + classId, staff.accessToken()).andExpect(status().isForbidden());
            api.post("/api/academics/years", staff.accessToken(), """
                    {"name":"2026-27","startsOn":"2026-06-01","endsOn":"2027-03-31"}""")
                    .andExpect(status().isForbidden());
        }
        for (String role : List.of("PARENT", "STUDENT")) {
            String email = role.toLowerCase() + "@" + school.code() + ".akshara.test";
            api.createUser(admin, "Family " + role, email, List.of(role));
            Session family = api.login(school.code(), email, TestApi.PASSWORD);
            api.get("/api/academics/classes", family.accessToken()).andExpect(status().isForbidden());
            api.get("/api/academics/years", family.accessToken()).andExpect(status().isForbidden());
        }
        api.get("/api/academics/classes", null).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherSchoolsSetupIsInvisible() throws Exception {
        String yearA = fixtures.currentYear(admin);
        String classA = fixtures.schoolClass(admin, "Class 4");
        String sectionA = fixtures.section(admin, classA, "A", 30);
        String subjectA = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"Hindi","code":"HIN"}""").andExpect(status().isCreated()), "$.id");

        School other = api.signup();
        Session adminB = api.login(other);
        String teacherA = api.createUser(admin, "Tara Teacher", "tara@" + school.code() + ".akshara.test",
                List.of("TEACHER"));

        api.get("/api/academics/classes", adminB.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/academics/years", adminB.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/academics/classes/" + classA, adminB.accessToken()).andExpect(status().isNotFound());
        api.get("/api/academics/classes/" + classA + "/sections", adminB.accessToken())
                .andExpect(status().isNotFound());
        api.put("/api/academics/classes/" + classA, adminB.accessToken(), """
                {"name":"Mine"}""").andExpect(status().isNotFound());
        api.put("/api/academics/sections/" + sectionA, adminB.accessToken(), """
                {"name":"Z"}""").andExpect(status().isNotFound());
        api.delete("/api/academics/sections/" + sectionA, adminB.accessToken()).andExpect(status().isNotFound());
        api.post("/api/academics/years/" + yearA + "/set-current", adminB.accessToken(), null)
                .andExpect(status().isNotFound());
        api.delete("/api/academics/years/" + yearA, adminB.accessToken()).andExpect(status().isNotFound());
        api.put("/api/academics/subjects/" + subjectA, adminB.accessToken(), """
                {"name":"Mine"}""").andExpect(status().isNotFound());
        api.post("/api/academics/classes/" + classA + "/sections", adminB.accessToken(), """
                {"name":"Z"}""").andExpect(status().isNotFound());

        String classB = fixtures.schoolClass(adminB, "Class 4");
        // School A's teacher and subject cannot be used in school B.
        api.post("/api/academics/classes/" + classB + "/sections", adminB.accessToken(), """
                {"name":"A","classTeacherId":"%s"}""".formatted(teacherA)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.classTeacherId").exists());
        api.put("/api/academics/classes/" + classB + "/subjects", adminB.accessToken(), """
                {"subjectIds":["%s"]}""".formatted(subjectA)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.subjectIds").exists());

        api.get("/api/academics/classes/" + classA, admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Class 4"));
    }
}
