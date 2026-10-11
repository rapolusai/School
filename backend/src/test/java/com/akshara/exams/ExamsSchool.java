package com.akshara.exams;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * A CBSE school for the exam tests, built through the API: a current year that started 150 days ago; Class 5 with
 * Mathematics and English and sections A (class teacher Ravi) and B (class teacher Sita); Kavitha teaches English in
 * both; a principal; Asha and Bala in 5 A, Dev in 5 B. Asha and her mother can sign in.
 */
final class ExamsSchool {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    final TestApi api;
    final School school;
    final Session admin;
    final LocalDate today = LocalDate.now(INDIA);
    final String yearId;
    final String ravi;
    final String sita;
    final String kavitha;
    final String maths;
    final String english;
    final String music;
    final String class5;
    final String s5A;
    final String s5B;
    final String asha;
    final String bala;
    final String dev;

    ExamsSchool(TestApi api) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        yearId = fixtures.year(admin, "This year", today.minusDays(150).toString(), today.plusDays(200).toString(),
                true);
        ravi = api.createUser(admin, "Ravi Kumar", email("ravi"), List.of("TEACHER"));
        sita = api.createUser(admin, "Sita Devi", email("sita"), List.of("TEACHER"));
        kavitha = api.createUser(admin, "Kavitha Menon", email("kavitha"), List.of("TEACHER"));
        api.createUser(admin, "Lakshmi Iyer", email("principal"), List.of("PRINCIPAL"));
        api.createUser(admin, "Meena Reddy", email("accounts"), List.of("ACCOUNTANT"));
        maths = subject("Mathematics");
        english = subject("English");
        music = subject("Music");
        class5 = fixtures.schoolClass(admin, "Class 5");
        api.put("/api/academics/classes/" + class5 + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s","%s"]}""".formatted(maths, english)).andExpect(status().isOk());
        s5A = section(class5, "A", ravi);
        s5B = section(class5, "B", sita);
        assign(s5A, english, kavitha);
        assign(s5B, english, kavitha);
        asha = student(s5A, "A-1", "Asha", 1, "Lata Rao", "9876500011");
        bala = student(s5A, "A-2", "Bala", 2, "Uma Rao", "9876500012");
        dev = student(s5B, "B-1", "Dev", 1, "Gita Rao", "9876500021");
        api.post("/api/students/" + asha + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("asha"), TestApi.PASSWORD))
                .andExpect(status().isOk());
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

    /** A Monday-to-Saturday day {@code days} from today (negative for the past). */
    LocalDate schoolDay(int days) {
        LocalDate d = today.plusDays(days);
        return d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.plusDays(days < 0 ? -1 : 1) : d;
    }

    /** Adds a term covering the whole year and returns its id. */
    String term(String name) throws Exception {
        return first(api.post("/api/exams/terms", admin.accessToken(), """
                {"academicYearId":"%s","name":"%s","startsOn":"%s","endsOn":"%s"}""".formatted(yearId, name,
                today.minusDays(150), today.plusDays(200))).andExpect(status().isCreated()),
                "$.terms[?(@.name == '" + name + "')].id");
    }

    /** Adds an exam for Class 5 and returns its id. */
    String exam(String termId, String name) throws Exception {
        return first(api.post("/api/exams", admin.accessToken(), """
                {"termId":"%s","name":"%s","classIds":["%s"]}""".formatted(termId, name, class5))
                .andExpect(status().isCreated()), "$.terms[*].exams[?(@.name == '" + name + "')].id");
    }

    /** Mathematics (theory 80 + internal 20) and English (theory 40) on two days, 9:30 for two hours. */
    String papers(String examId, LocalDate mathsOn, LocalDate englishOn) throws Exception {
        return api.put("/api/exams/" + examId + "/classes/" + class5 + "/papers", admin.accessToken(), """
                {"papers":[
                  {"subjectId":"%s","maxTheory":80,"maxInternal":20,"date":"%s","startTime":"09:30",
                   "durationMinutes":120,"room":"Hall 1"},
                  {"subjectId":"%s","maxTheory":40,"date":"%s","startTime":"09:30","durationMinutes":120}]}"""
                .formatted(maths, mathsOn, english, englishOn)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The first value a JSON path with a filter finds. */
    static String first(ResultActions result, String path) throws Exception {
        List<Object> values = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
        return values.isEmpty() ? null : values.getFirst().toString();
    }

    /** The first value a JSON path with a filter finds in a body. */
    static String firstIn(String json, String path) {
        List<Object> values = JsonPath.read(json, path);
        return values.isEmpty() ? null : values.getFirst().toString();
    }

    /** One value from a JSON body. */
    static String read(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? null : value.toString();
    }

    private String subject(String name) throws Exception {
        return TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"%s"}""".formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private String section(String classId, String name, String teacherId) throws Exception {
        return TestApi.read(api.post("/api/academics/classes/" + classId + "/sections", admin.accessToken(), """
                {"name":"%s","classTeacherId":"%s"}""".formatted(name, teacherId)).andExpect(status().isCreated()),
                "$.id");
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
