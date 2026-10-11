package com.akshara.exams;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * Grading schemes, exam settings and class schemes; terms, exams and weightage; papers, date sheet clashes, publishing
 * to families and the school calendar; hall tickets; who may do what; and another school's ids.
 */
class ExamSetupIT extends IntegrationTest {

    private ExamsSchool s;

    @BeforeEach
    void school() throws Exception {
        s = new ExamsSchool(api);
    }

    @Test
    void everySchoolStartsWithTheBuiltInSchemesAndCanAddItsOwn() throws Exception {
        String token = s.admin.accessToken();
        api.get("/api/exams/grading-schemes", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", containsInAnyOrder("CBSE_1_8", "CBSE_9_10", "CBSE_CO", "ICSE",
                        "STATE")))
                .andExpect(jsonPath("$[?(@.code == 'CBSE_1_8')].bands[*].grade", contains("A1", "A2", "B1", "B2",
                        "C1", "C2", "D", "E")))
                .andExpect(jsonPath("$[?(@.code == 'CBSE_1_8')].bands[0].minPercent", contains(91.0)))
                .andExpect(jsonPath("$[?(@.code == 'CBSE_1_8')].passPercent", contains(33.0)))
                .andExpect(jsonPath("$[?(@.code == 'CBSE_9_10')].bands[*].grade", hasItems("A1", "E1", "E2")))
                .andExpect(jsonPath("$[?(@.code == 'CBSE_CO')].kind", contains("CO_SCHOLASTIC")))
                .andExpect(jsonPath("$[*].builtIn", not(hasItem(false))));
        // Teachers read; only exams.setup changes schemes.
        Session ravi = s.login("ravi");
        api.get("/api/exams/grading-schemes", ravi.accessToken()).andExpect(status().isOk());
        api.post("/api/exams/grading-schemes", ravi.accessToken(), scheme("Pass or fail", "40", "P", "F"))
                .andExpect(status().isForbidden());
        api.get("/api/exams/grading-schemes", s.login("accounts").accessToken()).andExpect(status().isForbidden());

        String custom = TestApi.read(api.post("/api/exams/grading-schemes", token, scheme("Pass or fail", "40", "P",
                "F")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.builtIn").value(false))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.bands[*].grade", contains("P", "F"))), "$.id");
        api.post("/api/exams/grading-schemes", token, scheme(" Pass or fail ", "40", "P", "F"))
                .andExpect(status().isConflict());
        api.post("/api/exams/grading-schemes", token, """
                {"name":"No zero","kind":"SCHOLASTIC","passPercent":33,"overallPassPercent":33,
                 "bands":[{"minPercent":50,"grade":"A"},{"minPercent":10,"grade":"B"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bands").exists());
        api.post("/api/exams/grading-schemes", token, """
                {"name":"Twice","kind":"SCHOLASTIC","passPercent":33,"overallPassPercent":33,
                 "bands":[{"minPercent":50,"grade":"A"},{"minPercent":0,"grade":"a"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bands").exists());
        api.post("/api/exams/grading-schemes", token, """
                {"name":"","kind":"SCHOLASTIC","passPercent":120,"overallPassPercent":33,"bands":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.passPercent").exists());
        api.put("/api/exams/grading-schemes/" + custom, token, """
                {"name":"Pass or fail","kind":"CO_SCHOLASTIC","passPercent":40,"overallPassPercent":40,
                 "bands":[{"minPercent":0,"grade":"F"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.kind").exists());
        api.put("/api/exams/grading-schemes/" + custom, token, scheme("Pass or fail", "35", "Pass", "Fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passPercent").value(35))
                .andExpect(jsonPath("$.bands[*].grade", contains("Pass", "Fail")));

        // Class 5 of a CBSE school uses CBSE classes 1 to 8 until it is given another scheme.
        api.get("/api/exams/class-schemes", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.board").value("CBSE"))
                .andExpect(jsonPath("$.classes[0].className").value("Class 5"))
                .andExpect(jsonPath("$.classes[0].level").value(5))
                .andExpect(jsonPath("$.classes[0].schemeName").value("CBSE classes 1 to 8"))
                .andExpect(jsonPath("$.classes[0].defaultScheme").value(true))
                .andExpect(jsonPath("$.classes[0].hpc").value(true));
        api.put("/api/exams/class-schemes", token, """
                {"classIds":["%s"],"schemeId":"%s"}""".formatted(s.class5, custom))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classes[0].schemeName").value("Pass or fail"))
                .andExpect(jsonPath("$.classes[0].defaultScheme").value(false));
        String co = ExamsSchool.first(api.get("/api/exams/grading-schemes", token), "$[?(@.code == 'CBSE_CO')].id");
        api.put("/api/exams/class-schemes", token, """
                {"classIds":["%s"],"schemeId":"%s"}""".formatted(s.class5, co))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.schemeId").exists());
        api.delete("/api/exams/grading-schemes/" + custom, token).andExpect(status().isConflict());
        api.put("/api/exams/class-schemes", token, """
                {"classIds":["%s"],"schemeId":null}""".formatted(s.class5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classes[0].schemeName").value("CBSE classes 1 to 8"));
        api.delete("/api/exams/grading-schemes/" + custom, token).andExpect(status().isNoContent());

        // Settings: ranks off and the progress card up to Class 5 by default.
        api.get("/api/exams/settings", ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ranksEnabled").value(false))
                .andExpect(jsonPath("$.hpcUpToLevel").value(5))
                .andExpect(jsonPath("$.coScholasticSchemeName").value("CBSE co-scholastic"));
        String cbse = ExamsSchool.first(api.get("/api/exams/grading-schemes", token),
                "$[?(@.code == 'CBSE_1_8')].id");
        api.put("/api/exams/settings", token, """
                {"ranksEnabled":true,"hpcUpToLevel":3,"coScholasticSchemeId":"%s"}""".formatted(cbse))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.coScholasticSchemeId").exists());
        api.put("/api/exams/settings", ravi.accessToken(), """
                {"ranksEnabled":true,"hpcUpToLevel":3}""").andExpect(status().isForbidden());
        api.put("/api/exams/settings", token, """
                {"ranksEnabled":true,"hpcUpToLevel":3,"coScholasticSchemeId":"%s"}""".formatted(co))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ranksEnabled").value(true));
        api.delete("/api/exams/grading-schemes/" + co, token).andExpect(status().isConflict());
        api.get("/api/exams/class-schemes", token).andExpect(jsonPath("$.classes[0].hpc").value(false));

        // A deleted built-in scheme can be added back.
        String state = ExamsSchool.first(api.get("/api/exams/grading-schemes", token), "$[?(@.code == 'STATE')].id");
        api.delete("/api/exams/grading-schemes/" + state, token).andExpect(status().isNoContent());
        api.get("/api/exams/grading-schemes", token).andExpect(jsonPath("$.length()").value(4));
        api.post("/api/exams/grading-schemes/restore", token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("STATE")))
                .andExpect(jsonPath("$.length()").value(5));

        api.get("/api/audit-events", token)
                .andExpect(jsonPath("$[*].action", hasItems("grading_scheme.created", "grading_scheme.updated",
                        "grading_scheme.deleted", "grading_scheme.defaults_added", "class_scheme.updated",
                        "exam_settings.updated")));

        // Another school sees none of it.
        TestApi.School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get("/api/exams/grading-schemes/" + cbse, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/exams/grading-schemes/" + cbse, otherAdmin.accessToken(), scheme("Mine", "33", "A", "B"))
                .andExpect(status().isNotFound());
        api.delete("/api/exams/grading-schemes/" + cbse, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/exams/settings", otherAdmin.accessToken(), """
                {"ranksEnabled":false,"hpcUpToLevel":5,"coScholasticSchemeId":"%s"}""".formatted(co))
                .andExpect(status().isBadRequest());
    }

    @Test
    void termsAndExamsCarryWeightsThatAddUpToAHundred() throws Exception {
        String token = s.admin.accessToken();
        api.get("/api/exams/structure", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year.name").value("This year"))
                .andExpect(jsonPath("$.terms").isEmpty())
                .andExpect(jsonPath("$.classes[*].name", contains("Class 5")))
                .andExpect(jsonPath("$.canSetup").value(true));
        LocalDate start = s.today.minusDays(150);
        LocalDate middle = s.today.plusDays(10);
        String term1 = TestApi.read(api.post("/api/exams/terms", token, term("Term 1", start, middle))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.terms[0].weight").value(0)), "$.terms[0].id");
        api.post("/api/exams/terms", token, term("Term 2", middle, s.today.plusDays(200)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.startsOn").exists());
        api.post("/api/exams/terms", token, term("Term 1", middle.plusDays(1), s.today.plusDays(200)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/exams/terms", token, term("Term 2", middle.plusDays(1), s.today.plusDays(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.startsOn").exists());
        api.post("/api/exams/terms", token, term("Term 2", middle.plusDays(5), middle.plusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.endsOn").exists());
        String term2 = ExamsSchool.first(api.post("/api/exams/terms", token, term("Term 2", middle.plusDays(1),
                s.today.plusDays(200))).andExpect(status().isCreated()), "$.terms[?(@.name == 'Term 2')].id");
        api.post("/api/exams/terms", s.login("ravi").accessToken(), term("Term 3", start, start))
                .andExpect(status().isForbidden());

        api.put("/api/exams/years/" + s.yearId + "/term-weights", token, weights(term1, "40", term2, "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.weights").exists());
        api.put("/api/exams/years/" + s.yearId + "/term-weights", token, """
                {"weights":[{"id":"%s","weight":100}]}""".formatted(term1))
                .andExpect(status().isBadRequest());
        api.put("/api/exams/years/" + s.yearId + "/term-weights", token, weights(term1, "40", term2, "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termWeightTotal").value(100))
                .andExpect(jsonPath("$.terms[0].weight").value(40));

        String unit = s.exam(term1, "Unit Test 1");
        api.post("/api/exams", token, """
                {"termId":"%s","name":"Unit Test 1","classIds":["%s"]}""".formatted(term1, s.class5))
                .andExpect(status().isConflict());
        String midTerm = s.exam(term1, "Mid-term");
        api.put("/api/exams/terms/" + term1 + "/exam-weights", token, weights(unit, "25", midTerm, "75"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms[0].examWeightTotal").value(100))
                .andExpect(jsonPath("$.terms[0].exams[*].name", contains("Unit Test 1", "Mid-term")));
        api.put("/api/exams/terms/" + term1 + "/exam-weights", token, weights(unit, "25", term2, "75"))
                .andExpect(status().isBadRequest());

        // A term with exams cannot be deleted; an exam without marks can.
        api.delete("/api/exams/terms/" + term1, token).andExpect(status().isConflict());
        api.delete("/api/exams/" + midTerm, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.terms[0].exams[*].name", contains("Unit Test 1")));
        api.put("/api/exams/" + unit, token, """
                {"name":"Unit Test 1","instructions":"Bring a pencil.","classIds":["%s"]}""".formatted(s.class5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms[0].exams[0].instructions").value("Bring a pencil."));
        api.delete("/api/exams/terms/" + term2, token).andExpect(status().isOk());

        // Ids of another school: in a body 400, in a path 404.
        TestApi.School other = api.signup();
        Session otherAdmin = api.login(other);
        String otherYear = new com.akshara.support.SchoolFixtures(api).currentYear(otherAdmin);
        String otherClass = new com.akshara.support.SchoolFixtures(api).schoolClass(otherAdmin, "Class 9");
        api.post("/api/exams", token, """
                {"termId":"%s","name":"Stray","classIds":["%s"]}""".formatted(term1, otherClass))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.classIds").exists());
        api.post("/api/exams/terms", token, """
                {"academicYearId":"%s","name":"Stray","startsOn":"2026-06-01","endsOn":"2026-06-30"}"""
                .formatted(otherYear)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.academicYearId").exists());
        api.post("/api/exams", otherAdmin.accessToken(), """
                {"termId":"%s","name":"Stray","classIds":[]}""".formatted(term1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.termId").exists());
        api.put("/api/exams/" + unit, otherAdmin.accessToken(), """
                {"name":"Mine","classIds":[]}""").andExpect(status().isNotFound());
        api.delete("/api/exams/" + unit, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/exams/terms/" + term1, otherAdmin.accessToken(), term("Mine", start, middle))
                .andExpect(status().isNotFound());
        api.put("/api/exams/terms/" + term1 + "/exam-weights", otherAdmin.accessToken(), """
                {"weights":[{"id":"%s","weight":100}]}""".formatted(unit)).andExpect(status().isNotFound());
        api.get("/api/exams/" + unit + "/classes/" + s.class5, otherAdmin.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/exams/structure", otherAdmin.accessToken()).andExpect(jsonPath("$.terms").isEmpty());

        api.get("/api/audit-events", token)
                .andExpect(jsonPath("$[*].action", hasItems("exam_term.created", "exam_term.weights_updated",
                        "exam.created", "exam.weights_updated", "exam.deleted", "exam.updated",
                        "exam_term.deleted")));
    }

    @Test
    void dateSheetsAreCheckedPublishedToFamiliesAndAddedToTheCalendar() throws Exception {
        String token = s.admin.accessToken();
        String term = s.term("Term 1");
        String exam = s.exam(term, "Half-Yearly");
        String path = "/api/exams/" + exam + "/classes/" + s.class5;
        LocalDate first = s.schoolDay(14);
        LocalDate second = s.schoolDay(15);
        if (second.equals(first)) {
            second = s.schoolDay(16);
        }

        // Only the class's subjects; slots all or nothing; inside the year.
        api.put(path + "/papers", token, """
                {"papers":[{"subjectId":"%s","maxTheory":50}]}""".formatted(s.music))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.papers").exists());
        api.put(path + "/papers", token, """
                {"papers":[{"subjectId":"%s","maxTheory":50,"date":"%s"}]}""".formatted(s.maths, first))
                .andExpect(status().isBadRequest());
        api.put(path + "/papers", token, """
                {"papers":[{"subjectId":"%s","maxTheory":50,"date":"%s","startTime":"09:30","durationMinutes":60}]}"""
                .formatted(s.maths, s.today.plusDays(400))).andExpect(status().isBadRequest());
        api.put(path + "/papers", token, """
                {"papers":[{"subjectId":"%s","maxTheory":0}]}""".formatted(s.maths))
                .andExpect(status().isBadRequest());
        api.put(path + "/papers", s.login("ravi").accessToken(), """
                {"papers":[{"subjectId":"%s","maxTheory":50}]}""".formatted(s.maths))
                .andExpect(status().isForbidden());
        api.post(path + "/date-sheet/publish", token, null).andExpect(status().isConflict());

        // Two papers at once is a clash; it blocks publishing until it is moved.
        api.put(path + "/papers", token, """
                {"papers":[
                  {"subjectId":"%s","maxTheory":80,"maxInternal":20,"date":"%s","startTime":"09:30","durationMinutes":120},
                  {"subjectId":"%s","maxTheory":40,"date":"%s","startTime":"10:30","durationMinutes":60}]}"""
                .formatted(s.maths, first, s.english, first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers.length()").value(2))
                .andExpect(jsonPath("$.papers[0].maxTotal").value(100))
                .andExpect(jsonPath("$.papers[0].endTime", startsWith("11:30")))
                .andExpect(jsonPath("$.clashes[*].kind", contains("OVERLAP")));
        api.post(path + "/date-sheet/publish", token, null).andExpect(status().isConflict());
        // A paper on a holiday is a clash too.
        api.post("/api/calendar/entries", token, """
                {"kind":"HOLIDAY","title":"Founders' Day","startsOn":"%s","audience":"SCHOOL"}""".formatted(second))
                .andExpect(status().isCreated());
        s.papers(exam, first, second);
        api.get(path, token).andExpect(jsonPath("$.clashes[*].kind", contains("HOLIDAY")));
        LocalDate third = s.schoolDay(17);
        if (third.equals(second)) {
            third = s.schoolDay(18);
        }
        String detail = s.papers(exam, first, third);
        String mathsPaper = ExamsSchool.first(api.get(path, token), "$.papers[?(@.subjectName == 'Mathematics')].id");
        api.get(path, token).andExpect(jsonPath("$.clashes").isEmpty())
                .andExpect(jsonPath("$.dateSheetPublishedAt").doesNotExist());

        // Families see nothing until it is published.
        Session mother = s.login("mother");
        api.get("/api/me/children/" + s.asha + "/results", mother.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upcoming").isEmpty());
        api.post(path + "/date-sheet/publish", token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateSheetPublishedAt").exists())
                .andExpect(jsonPath("$.dateSheetPublishedBy").exists());
        api.get("/api/me/children/" + s.asha + "/results", mother.accessToken())
                .andExpect(jsonPath("$.classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.upcoming[0].examName").value("Half-Yearly"))
                .andExpect(jsonPath("$.upcoming[0].papers[*].subjectName", contains("Mathematics", "English")));
        api.get("/api/me/results", s.login("asha").accessToken())
                .andExpect(jsonPath("$.upcoming[0].papers.length()").value(2));
        api.get("/api/me/children/" + s.dev + "/results", mother.accessToken()).andExpect(status().isNotFound());
        // The papers are on the class's calendar.
        api.get("/api/calendar/entries", mother.accessToken())
                .andExpect(jsonPath("$.entries[*].title", hasItems("Half-Yearly: Mathematics",
                        "Half-Yearly: English")));
        // Moving a paper moves its calendar entry.
        LocalDate later = s.schoolDay(21);
        api.put(path + "/papers", token, """
                {"papers":[
                  {"subjectId":"%s","maxTheory":80,"maxInternal":20,"date":"%s","startTime":"09:30","durationMinutes":120},
                  {"subjectId":"%s","maxTheory":40,"date":"%s","startTime":"09:30","durationMinutes":120}]}"""
                .formatted(s.maths, later, s.english, third)).andExpect(status().isOk());
        api.get("/api/calendar/entries", token)
                .andExpect(jsonPath("$.entries[?(@.title == 'Half-Yearly: Mathematics')].startsOn",
                        contains(later.toString())));

        // Hall tickets for the class or one section.
        api.get(path + "/hall-tickets", s.login("ravi").accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students[*].fullName", contains("Asha Rao", "Bala Rao", "Dev Rao")))
                .andExpect(jsonPath("$.papers.length()").value(2))
                .andExpect(jsonPath("$.school.name").exists());
        api.get(path + "/hall-tickets?sectionId=" + s.s5B, token)
                .andExpect(jsonPath("$.students[*].fullName", contains("Dev Rao")))
                .andExpect(jsonPath("$.students[0].sectionLabel").value("Class 5 B"));

        // Withdrawing takes it off the calendar and the family app.
        api.post(path + "/date-sheet/withdraw", token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateSheetPublishedAt").doesNotExist());
        api.post(path + "/date-sheet/withdraw", token, null).andExpect(status().isConflict());
        api.get("/api/calendar/entries", token)
                .andExpect(jsonPath("$.entries[?(@.title == 'Half-Yearly: Mathematics')]").isEmpty());
        api.get("/api/me/children/" + s.asha + "/results", mother.accessToken())
                .andExpect(jsonPath("$.upcoming").isEmpty());
        api.get("/api/audit-events", token)
                .andExpect(jsonPath("$[*].action", hasItems("date_sheet.updated", "date_sheet.published",
                        "date_sheet.withdrawn")));

        TestApi.School other = api.signup();
        Session otherAdmin = api.login(other);
        api.get(path + "/hall-tickets", otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.put(path + "/papers", otherAdmin.accessToken(), "{\"papers\":[]}").andExpect(status().isNotFound());
        api.post(path + "/date-sheet/publish", otherAdmin.accessToken(), null).andExpect(status().isNotFound());
        api.get("/api/exams/papers/" + mathsPaper + "/sections/" + s.s5A + "/marks", otherAdmin.accessToken())
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(detail).contains("Mathematics");
    }

    private static String scheme(String name, String pass, String top, String bottom) {
        return """
                {"name":"%s","kind":"SCHOLASTIC","passPercent":%s,"overallPassPercent":%s,
                 "bands":[{"minPercent":%s,"grade":"%s","remark":"Passed"},{"minPercent":0,"grade":"%s"}]}"""
                .formatted(name, pass, pass, pass, top, bottom);
    }

    private String term(String name, LocalDate startsOn, LocalDate endsOn) {
        return """
                {"academicYearId":"%s","name":"%s","startsOn":"%s","endsOn":"%s"}"""
                .formatted(s.yearId, name, startsOn, endsOn);
    }

    private static String weights(String a, String aWeight, String b, String bWeight) {
        return """
                {"weights":[{"id":"%s","weight":%s},{"id":"%s","weight":%s}]}""".formatted(a, aWeight, b, bWeight);
    }
}
