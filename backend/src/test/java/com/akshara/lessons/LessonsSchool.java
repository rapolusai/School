package com.akshara.lessons;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * A school for the lessons tests, built through the API: a current year that started 150 days ago and last year
 * before it; Class 5 with Mathematics and English, sections A and B; Ravi teaches Mathematics in 5 A, Sita in 5 B,
 * Kavitha English in both; Class 6 with Mathematics and one section nobody teaches. A principal and an accountant.
 * Asha and Bala in 5 A, Dev in 5 B; Asha, her mother and Dev can sign in.
 */
final class LessonsSchool {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    final TestApi api;
    final School school;
    final Session admin;
    final LocalDate today = LocalDate.now(INDIA);
    final YearMonth month = YearMonth.from(today);
    final String yearId;
    final String lastYearId;
    final String ravi;
    final String sita;
    final String kavitha;
    final String principal;
    final String maths;
    final String english;
    final String class5;
    final String class6;
    final String s5A;
    final String s5B;
    final String s6A;
    final String asha;
    final String bala;
    final String dev;

    LessonsSchool(TestApi api) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        yearId = fixtures.year(admin, "This year", today.minusDays(150).toString(), today.plusDays(200).toString(),
                true);
        lastYearId = fixtures.year(admin, "Last year", today.minusDays(515).toString(),
                today.minusDays(151).toString(), false);
        ravi = api.createUser(admin, "Ravi Kumar", email("ravi"), List.of("TEACHER"));
        sita = api.createUser(admin, "Sita Devi", email("sita"), List.of("TEACHER"));
        kavitha = api.createUser(admin, "Kavitha Menon", email("kavitha"), List.of("TEACHER"));
        principal = api.createUser(admin, "Lakshmi Iyer", email("principal"), List.of("PRINCIPAL"));
        api.createUser(admin, "Meena Reddy", email("accounts"), List.of("ACCOUNTANT"));
        maths = subject("Mathematics");
        english = subject("English");
        class5 = fixtures.schoolClass(admin, "Class 5");
        class6 = fixtures.schoolClass(admin, "Class 6");
        api.put("/api/academics/classes/" + class5 + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s","%s"]}""".formatted(maths, english)).andExpect(status().isOk());
        api.put("/api/academics/classes/" + class6 + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s"]}""".formatted(maths)).andExpect(status().isOk());
        s5A = fixtures.section(admin, class5, "A", 40);
        s5B = fixtures.section(admin, class5, "B", 40);
        s6A = fixtures.section(admin, class6, "A", 40);
        assign(s5A, maths, ravi);
        assign(s5B, maths, sita);
        assign(s5A, english, kavitha);
        assign(s5B, english, kavitha);
        asha = student(s5A, "A-1", "Asha", 1, "Lata Rao", "9876500011");
        bala = student(s5A, "A-2", "Bala", 2, "Uma Rao", "9876500012");
        dev = student(s5B, "B-1", "Dev", 1, "Gita Rao", "9876500021");
        signIn(asha, "asha");
        signIn(dev, "dev");
        String mother = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("mother"), TestApi.PASSWORD))
                .andExpect(status().isOk());
    }

    Session login(String name) throws Exception {
        return api.login(school.code(), email(name), TestApi.PASSWORD);
    }

    String email(String name) {
        return name + "@" + school.code() + ".akshara.test";
    }

    /** The Monday of the week {@code weeks} from this one (negative for earlier weeks). */
    LocalDate monday(int weeks) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weeks);
    }

    /**
     * The Class 5 Mathematics syllabus of this year: "Numbers" with Place value (three months ago) and Addition
     * (last month), and "Fractions" with Halves (this month) and Decimals (no target month). Returns its JSON.
     */
    String mathsSyllabus(Session session) throws Exception {
        return api.post("/api/lessons/syllabi", session.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[
                  {"title":"Numbers","topics":[
                    {"title":"Place value","plannedPeriods":4,"targetMonth":"%s"},
                    {"title":"Addition","plannedPeriods":3,"targetMonth":"%s"}]},
                  {"title":"Fractions","topics":[
                    {"title":"Halves","plannedPeriods":2,"targetMonth":"%s"},
                    {"title":"Decimals","plannedPeriods":5}]}]}"""
                .formatted(class5, maths, month.minusMonths(3), month.minusMonths(1), month))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    /** A topic id of a syllabus JSON, by title. */
    static String topic(String syllabusJson, String title) {
        return firstIn(syllabusJson, "$.units[*].topics[?(@.title == '" + title + "')].id");
    }

    /** A draft lesson plan for this week; returns its id. */
    String plan(Session teacher, String sectionId, String subjectId, String topicId) throws Exception {
        return TestApi.read(api.post("/api/lessons/plans", teacher.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","topicId":%s,"title":"Lesson","weekStart":"%s","periods":2,
                 "objectives":"Read and write numbers.","resourceLinks":"https://ncert.nic.in"}"""
                .formatted(sectionId, subjectId, topicId == null ? "null" : "\"" + topicId + "\"", monday(0)))
                .andExpect(status().isCreated()), "$.id");
    }

    /** A Mathematics multiple choice question for Class 5 (one correct option, "4"); returns its JSON. */
    String mcq(Session teacher, String text, String marks) throws Exception {
        return api.post("/api/lessons/questions", teacher.accessToken(), """
                {"classId":"%s","subjectId":"%s","type":"MCQ_SINGLE","difficulty":"EASY","text":"%s",
                 "marks":%s,"options":[{"text":"3","correct":false},{"text":"4","correct":true},
                 {"text":"5","correct":false}]}""".formatted(class5, maths, text, marks))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    /** A question of any type for Class 5 Mathematics; returns its id. */
    String question(Session teacher, String json) throws Exception {
        return TestApi.read(api.post("/api/lessons/questions", teacher.accessToken(), json.formatted(class5, maths))
                .andExpect(status().isCreated()), "$.id");
    }

    /** The first value a JSON path with a filter finds in a body. */
    static String firstIn(String json, String path) {
        List<Object> values = JsonPath.read(json, path);
        return values.isEmpty() ? null : values.getFirst().toString();
    }

    /** The first value a JSON path with a filter finds in a response. */
    static String first(ResultActions result, String path) throws Exception {
        return firstIn(result.andReturn().getResponse().getContentAsString(), path);
    }

    static String read(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? null : value.toString();
    }

    static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private void signIn(String studentId, String name) throws Exception {
        api.post("/api/students/" + studentId + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email(name), TestApi.PASSWORD))
                .andExpect(status().isOk());
    }

    private String subject(String name) throws Exception {
        return TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"%s"}""".formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private void assign(String sectionId, String subjectId, String teacherId) throws Exception {
        api.post("/api/timetable/assignments", admin.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","teacherId":"%s","periodsPerWeek":5}"""
                .formatted(sectionId, subjectId, teacherId)).andExpect(status().isCreated());
    }

    private String student(String sectionId, String admissionNo, String firstName, int rollNo, String guardian,
            String phone) throws Exception {
        return TestApi.read(api.post("/api/students", admin.accessToken(), SchoolFixtures.studentJson(sectionId,
                admissionNo, firstName, "Rao", guardian, phone, rollNo)).andExpect(status().isCreated()), "$.id");
    }
}
