package com.akshara.academics;

import static org.hamcrest.Matchers.containsInAnyOrder;
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

/** Subjects by class: the board's suggestions and saving every class's subjects at once. */
class SubjectPlanIT extends IntegrationTest {

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
    void suggestsTheSchoolBoardsSubjectsForEachClassLevel() throws Exception {
        fixtures.schoolClass(admin, "LKG");
        fixtures.schoolClass(admin, "Class 7");
        fixtures.schoolClass(admin, "Rose");

        api.get("/api/academics/subject-plan", admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.board").value("CBSE"))
                .andExpect(jsonPath("$.schoolBoard").value("CBSE"))
                .andExpect(jsonPath("$.standard[?(@.name=='Social Science')].code").value(hasItem("SST")))
                .andExpect(jsonPath("$.classes[?(@.name=='LKG')].level").value(hasItem(-1)))
                .andExpect(jsonPath("$.classes[?(@.name=='Class 7')].suggested[*]")
                        .value(hasItems("English", "Hindi", "Mathematics", "Science", "Social Science")))
                .andExpect(jsonPath("$.classes[?(@.name=='Class 7')].optional[*]").value(hasItems("Telugu", "French")))
                .andExpect(jsonPath("$.classes[?(@.name=='Class 7')].suggested[*]").value(not(hasItem("Telugu"))))
                .andExpect(jsonPath("$.classes[?(@.name=='Rose')].suggested[*]").isEmpty());

        // Another board's suggestions can be looked at without changing the school's board.
        api.get("/api/academics/subject-plan?board=STATE", admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.board").value("STATE"))
                .andExpect(jsonPath("$.schoolBoard").value("CBSE"))
                .andExpect(jsonPath("$.classes[?(@.name=='Class 7')].suggested[*]")
                        .value(hasItems("Telugu", "Hindi", "General Science", "Social Studies")));
        api.get("/api/academics/subject-plan?board=MARS", admin.accessToken()).andExpect(status().isBadRequest());
    }

    @Test
    void savesNewAndExistingSubjectsForSeveralClassesAtOnce() throws Exception {
        String one = fixtures.schoolClass(admin, "Class 1");
        String two = fixtures.schoolClass(admin, "Class 2");
        String untouched = fixtures.schoolClass(admin, "Class 3");
        String english = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"English","code":"ENG"}"""), "$.id");
        String drawing = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"Drawing","code":"MAT"}"""), "$.id");
        api.put("/api/academics/classes/" + untouched + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s"]}""".formatted(drawing)).andExpect(status().isOk());

        api.put("/api/academics/subject-plan", admin.accessToken(), """
                {"newSubjects":[{"name":"Mathematics","code":"MAT"},{"name":"english","code":"EN"},
                                {"name":"Environmental Studies","code":"EVS"}],
                 "classes":[{"classId":"%s","subjectIds":["%s"],"newSubjects":["Mathematics","Environmental Studies"]},
                            {"classId":"%s","subjectIds":[],"newSubjects":["mathematics","English"]}]}"""
                .formatted(one, english, two))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectsCreated").value(2))
                .andExpect(jsonPath("$.classesChanged").value(2));

        // "english" matched the existing English; Mathematics lost its code, which Drawing already uses.
        api.get("/api/academics/subjects", admin.accessToken())
                .andExpect(jsonPath("$[*].name")
                        .value(containsInAnyOrder("Drawing", "English", "Environmental Studies", "Mathematics")))
                .andExpect(jsonPath("$[?(@.name=='Mathematics')].code").value(hasItem((Object) null)));
        api.get("/api/academics/classes/" + one + "/subjects", admin.accessToken())
                .andExpect(jsonPath("$[*].name")
                        .value(containsInAnyOrder("English", "Environmental Studies", "Mathematics")));
        api.get("/api/academics/classes/" + two + "/subjects", admin.accessToken())
                .andExpect(jsonPath("$[*].name").value(containsInAnyOrder("English", "Mathematics")));
        api.get("/api/academics/classes/" + untouched + "/subjects", admin.accessToken())
                .andExpect(jsonPath("$[*].name").value(containsInAnyOrder("Drawing")));

        // Unticking removes a subject from a class; saving the same plan again changes nothing.
        api.put("/api/academics/subject-plan", admin.accessToken(), """
                {"classes":[{"classId":"%s","subjectIds":["%s"]}]}""".formatted(two, english))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classesChanged").value(1));
        api.put("/api/academics/subject-plan", admin.accessToken(), """
                {"classes":[{"classId":"%s","subjectIds":["%s"]}]}""".formatted(two, english))
                .andExpect(jsonPath("$.subjectsCreated").value(0))
                .andExpect(jsonPath("$.classesChanged").value(0));
    }

    @Test
    void aBadPlanWritesNothing() throws Exception {
        String one = fixtures.schoolClass(admin, "Class 1");

        // A new subject a class names but the plan does not list.
        api.put("/api/academics/subject-plan", admin.accessToken(), """
                {"newSubjects":[{"name":"Science"}],
                 "classes":[{"classId":"%s","subjectIds":[],"newSubjects":["Science","Hindi"]}]}""".formatted(one))
                .andExpect(status().isBadRequest());
        // A class of another school (or none), the same class twice, a subject id that is not this school's.
        api.put("/api/academics/subject-plan", admin.accessToken(), """
                {"newSubjects":[{"name":"Science"}],
                 "classes":[{"classId":"00000000-0000-0000-0000-000000000001","subjectIds":[]}]}""")
                .andExpect(status().isBadRequest());
        api.put("/api/academics/subject-plan", admin.accessToken(), """
                {"newSubjects":[{"name":"Science"}],
                 "classes":[{"classId":"%s","subjectIds":[]},{"classId":"%s","subjectIds":[]}]}"""
                .formatted(one, one))
                .andExpect(status().isBadRequest());
        api.put("/api/academics/subject-plan", admin.accessToken(), """
                {"newSubjects":[{"name":"Science"}],
                 "classes":[{"classId":"%s","subjectIds":["00000000-0000-0000-0000-000000000002"],
                             "newSubjects":["Science"]}]}""".formatted(one))
                .andExpect(status().isBadRequest());
        api.put("/api/academics/subject-plan", admin.accessToken(), """
                {"newSubjects":[{"name":"Science","code":"no spaces"}],"classes":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").exists());

        api.get("/api/academics/subjects", admin.accessToken()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void teachersMayLookButOnlySetupManagersSave() throws Exception {
        String one = fixtures.schoolClass(admin, "Class 1");
        String email = "tara@" + school.code() + ".akshara.test";
        api.createUser(admin, "Tara Teacher", email, List.of("TEACHER"));
        Session teacher = api.login(school.code(), email, TestApi.PASSWORD);

        api.get("/api/academics/subject-plan", teacher.accessToken()).andExpect(status().isOk());
        api.put("/api/academics/subject-plan", teacher.accessToken(), """
                {"newSubjects":[{"name":"Science"}],"classes":[{"classId":"%s","subjectIds":[],
                 "newSubjects":["Science"]}]}""".formatted(one))
                .andExpect(status().isForbidden());
        api.get("/api/academics/subject-plan", null).andExpect(status().isUnauthorized());
    }

    @Test
    void schoolsOnlySeeTheirOwnClasses() throws Exception {
        String mine = fixtures.schoolClass(admin, "Class 1");
        School other = api.signup();
        Session otherAdmin = api.login(other);

        api.get("/api/academics/subject-plan", otherAdmin.accessToken())
                .andExpect(jsonPath("$.classes.length()").value(0));
        api.put("/api/academics/subject-plan", otherAdmin.accessToken(), """
                {"newSubjects":[{"name":"Science"}],"classes":[{"classId":"%s","subjectIds":[],
                 "newSubjects":["Science"]}]}""".formatted(mine))
                .andExpect(status().isBadRequest());
        api.get("/api/academics/classes/" + mine + "/subjects", admin.accessToken())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
