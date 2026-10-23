package com.akshara.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * Marks entry (who may enter what, validation, drafts, optimistic locking, submitting and unlocking), results and
 * publishing them to families with parent messages, term records and the holistic progress card, report cards,
 * result analysis with its download, the dashboard card, and another school's ids.
 */
class ExamResultsIT extends IntegrationTest {

    private ExamsSchool s;
    private String term;
    private String exam;
    private String maths;
    private String english;

    @BeforeEach
    void schoolWithAnExam() throws Exception {
        s = new ExamsSchool(api);
        term = s.term("Term 1");
        exam = s.exam(term, "Unit Test 1");
        s.papers(exam, s.schoolDay(-7), s.schoolDay(-5));
        String detail = api.get("/api/exams/" + exam + "/classes/" + s.class5, s.admin.accessToken())
                .andReturn().getResponse().getContentAsString();
        maths = ExamsSchool.firstIn(detail, "$.papers[?(@.subjectName == 'Mathematics')].id");
        english = ExamsSchool.firstIn(detail, "$.papers[?(@.subjectName == 'English')].id");
    }

    @Test
    void teachersEnterMarksForTheirOwnSectionsAndSubjectsWithChecks() throws Exception {
        Session ravi = s.login("ravi");
        Session kavitha = s.login("kavitha");
        Session sita = s.login("sita");
        // Ravi (class teacher of 5 A) has both subjects of 5 A; Kavitha English in both sections.
        api.get("/api/exams/marks", ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wholeSchool").value(false))
                .andExpect(jsonPath("$.exams[0].sheets[*].sectionLabel", everyItem(is("Class 5 A"))))
                .andExpect(jsonPath("$.exams[0].sheets[*].subjectName", containsInAnyOrder("Mathematics",
                        "English")))
                .andExpect(jsonPath("$.exams[0].notStarted").value(2));
        api.get("/api/exams/marks", kavitha.accessToken())
                .andExpect(jsonPath("$.exams[0].sheets[*].subjectName", everyItem(is("English"))))
                .andExpect(jsonPath("$.exams[0].sheets[*].sectionLabel", contains("Class 5 A", "Class 5 B")));
        api.get("/api/exams/marks", s.admin.accessToken())
                .andExpect(jsonPath("$.wholeSchool").value(true))
                .andExpect(jsonPath("$.exams[0].total").value(4));

        String mathsA = "/api/exams/papers/" + maths + "/sections/" + s.s5A + "/marks";
        api.get(mathsA, ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheetId").doesNotExist())
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.maxTotal").value(100))
                .andExpect(jsonPath("$.students[*].fullName", contains("Asha Rao", "Bala Rao")));
        api.get(mathsA, kavitha.accessToken()).andExpect(jsonPath("$.canEdit").value(false));
        api.put(mathsA, kavitha.accessToken(), marks(null, line(s.asha, "70", "18", null)))
                .andExpect(status().isForbidden());
        api.put(mathsA, sita.accessToken(), marks(null, line(s.asha, "70", "18", null)))
                .andExpect(status().isForbidden());
        api.put(mathsA, s.login("accounts").accessToken(), marks(null, line(s.asha, "70", "18", null)))
                .andExpect(status().isForbidden());

        // 0 to the maximum per component; a code instead of marks, not both; only the section's students.
        api.put(mathsA, ravi.accessToken(), marks(null, line(s.asha, "81", "18", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.entries").exists());
        api.put(mathsA, ravi.accessToken(), marks(null, line(s.asha, "-1", "18", null)))
                .andExpect(status().isBadRequest());
        api.put(mathsA, ravi.accessToken(), marks(null, line(s.asha, "70", "18", "AB")))
                .andExpect(status().isBadRequest());
        api.put(mathsA, ravi.accessToken(), marks(null, line(s.dev, "70", "18", null)))
                .andExpect(status().isBadRequest());
        api.put(mathsA, ravi.accessToken(), marks(null, line(s.asha, "70", "18", null) + ","
                + line(s.asha, "71", "18", null))).andExpect(status().isBadRequest());

        // A draft, then a second save with the version read; a stale version is refused.
        String saved = api.put(mathsA, ravi.accessToken(), marks(null, line(s.asha, "70.5", "18", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.entered").value(1))
                .andExpect(jsonPath("$.students[0].total").value(88.5))
                .andExpect(jsonPath("$.students[0].grade").value("A2"))
                .andExpect(jsonPath("$.updatedByName").value("Ravi Kumar"))
                .andReturn().getResponse().getContentAsString();
        long version = Long.parseLong(ExamsSchool.read(saved, "$.version"));
        api.put(mathsA, ravi.accessToken(), marks(null, line(s.bala, "10", "5", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.version").exists());
        String second = api.put(mathsA, s.admin.accessToken(), marks(version, line(s.bala, null, null, "ML")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entered").value(2))
                .andReturn().getResponse().getContentAsString();
        api.put(mathsA, ravi.accessToken(), marks(version, line(s.bala, "10", "5", null)))
                .andExpect(status().isConflict());
        long latest = Long.parseLong(ExamsSchool.read(second, "$.version"));

        // Submitting needs every student; it locks the sheet.
        api.put(mathsA, ravi.accessToken(), marks(latest, line(s.bala, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entered").value(1));
        long cleared = latest + 1;
        api.post(mathsA + "/submit", ravi.accessToken(), "{\"version\":" + cleared + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.entries").exists());
        api.put(mathsA, ravi.accessToken(), marks(cleared, line(s.bala, null, null, "AB")))
                .andExpect(status().isOk());
        api.post(mathsA + "/submit", ravi.accessToken(), "{\"version\":" + (cleared + 1) + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.submittedByName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.canUnlock").value(false));
        api.put(mathsA, ravi.accessToken(), marks(cleared + 2, line(s.bala, "5", "5", null)))
                .andExpect(status().isConflict());
        api.get("/api/exams/marks", ravi.accessToken())
                .andExpect(jsonPath("$.exams[0].locked").value(1));

        // Only exams.publish unlocks, with a reason; teachers cannot.
        api.post(mathsA + "/unlock", ravi.accessToken(), "{\"reason\":\"Typo\"}").andExpect(status().isForbidden());
        Session principal = s.login("principal");
        api.get(mathsA, principal.accessToken()).andExpect(jsonPath("$.canUnlock").value(true));
        api.post(mathsA + "/unlock", principal.accessToken(), "{\"reason\":\" \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        api.post(mathsA + "/unlock", principal.accessToken(), "{\"reason\":\"Bala was present; marks to enter\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.unlockedByName").value("Lakshmi Iyer"))
                .andExpect(jsonPath("$.unlockReason").value("Bala was present; marks to enter"));
        api.post(mathsA + "/unlock", principal.accessToken(), "{\"reason\":\"Again\"}")
                .andExpect(status().isConflict());
        api.get("/api/audit-events", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("mark_sheet.saved", "mark_sheet.locked",
                        "mark_sheet.unlocked")))
                .andExpect(jsonPath("$[?(@.action == 'mark_sheet.unlocked')].details.reason",
                        contains("Bala was present; marks to enter")));

        // Another school: paper and section ids are 404.
        Session otherAdmin = api.login(api.signup());
        api.get(mathsA, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.put(mathsA, otherAdmin.accessToken(), marks(null, line(s.asha, "1", "1", null)))
                .andExpect(status().isNotFound());
        api.post(mathsA + "/unlock", otherAdmin.accessToken(), "{\"reason\":\"x\"}")
                .andExpect(status().isNotFound());
        // A section of another class is 404 for this paper.
        String class6 = new com.akshara.support.SchoolFixtures(api).schoolClass(s.admin, "Class 6");
        String s6A = new com.akshara.support.SchoolFixtures(api).section(s.admin, class6, "A", 30);
        api.get("/api/exams/papers/" + maths + "/sections/" + s6A + "/marks", s.admin.accessToken())
                .andExpect(status().isNotFound());
    }

    @Test
    void resultsArePublishedExplicitlyAndOnlyThenReachFamilies() throws Exception {
        Session ravi = s.login("ravi");
        Session principal = s.login("principal");
        Session mother = s.login("mother");
        // Asha 70+18 and 36/40; Bala 20+5 and 12/40 (English below 33%); Dev 60+15 and AB in English.
        enter(s.admin, maths, s.s5A, line(s.asha, "70", "18", null) + "," + line(s.bala, "20", "5", null));
        enter(s.admin, english, s.s5A, line(s.asha, "36", null, null) + "," + line(s.bala, "12", null, null));
        enter(s.admin, maths, s.s5B, line(s.dev, "60", "15", null));
        String publish = "/api/exams/" + exam + "/classes/" + s.class5 + "/results/publish";
        api.post(publish, principal.accessToken(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("not submitted")));
        enter(s.admin, english, s.s5B, line(s.dev, null, null, "AB"));

        String results = "/api/exams/" + exam + "/sections/" + s.s5A + "/results";
        api.get(results, ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheme.name").value("CBSE classes 1 to 8"))
                .andExpect(jsonPath("$.subjects[*].subjectName", contains("Mathematics", "English")))
                .andExpect(jsonPath("$.rows[0].fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.rows[0].obtained").value(124))
                .andExpect(jsonPath("$.rows[0].max").value(140))
                .andExpect(jsonPath("$.rows[0].percent").value(88.57))
                .andExpect(jsonPath("$.rows[0].grade").value("A2"))
                .andExpect(jsonPath("$.rows[0].result").value("PASS"))
                .andExpect(jsonPath("$.rows[0].rank").doesNotExist())
                .andExpect(jsonPath("$.rows[1].subjects[1].passed").value(false))
                .andExpect(jsonPath("$.rows[1].result").value("NEEDS_IMPROVEMENT"))
                .andExpect(jsonPath("$.sheetsPending").value(0));
        api.put("/api/exams/settings", s.admin.accessToken(), """
                {"ranksEnabled":true,"hpcUpToLevel":5}""").andExpect(status().isOk());
        api.get(results, ravi.accessToken())
                .andExpect(jsonPath("$.ranksEnabled").value(true))
                .andExpect(jsonPath("$.rows[0].rank").value(1))
                .andExpect(jsonPath("$.rows[1].rank").value(2));
        api.get(results, s.login("accounts").accessToken()).andExpect(status().isForbidden());

        // Term records by the class teacher only; grades from the co-scholastic scheme; the progress card for 5.
        String records = "/api/exams/terms/" + term + "/sections/" + s.s5A + "/records";
        api.get(records, ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.hpcEnabled").value(true))
                .andExpect(jsonPath("$.coScholasticGrades", contains("A", "B", "C")));
        api.put(records, s.login("kavitha").accessToken(), recordFor(s.asha, "A", "Good"))
                .andExpect(status().isForbidden());
        api.put(records, ravi.accessToken(), recordFor(s.asha, "Z", "Good")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.entries").exists());
        api.put(records, ravi.accessToken(), recordFor(s.dev, "A", "Good")).andExpect(status().isBadRequest());
        api.put(records, ravi.accessToken(), """
                {"entries":[{"studentId":"%s","workEducation":"a","artEducation":"B","healthPhysical":"A",
                  "discipline":"A","remark":"Asha reads widely.","physical":"PROFICIENT","language":"ADVANCED",
                  "teacherReflection":"Asks good questions.","selfReflection":"I like stories."}]}"""
                .formatted(s.asha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students[0].coScholastic.workEducation").value("A"))
                .andExpect(jsonPath("$.students[0].hpc.language").value("ADVANCED"));

        // Before publishing families see nothing of the results.
        api.get("/api/me/children/" + s.asha + "/results", mother.accessToken())
                .andExpect(jsonPath("$.results").isEmpty())
                .andExpect(jsonPath("$.terms[0].published").value(false));
        api.get("/api/me/children/" + s.asha + "/report-card?termId=" + term, mother.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedOnly").value(true))
                .andExpect(jsonPath("$.columns").isEmpty())
                .andExpect(jsonPath("$.cards[0].remark").doesNotExist());

        api.post(publish, ravi.accessToken(), null).andExpect(status().isForbidden());
        api.post(publish, principal.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultsPublishedAt").exists())
                .andExpect(jsonPath("$.resultsPublishedBy").value("Lakshmi Iyer"));
        api.post(publish, principal.accessToken(), null).andExpect(status().isConflict());
        // Parents are told through the outbox: one message per student with a primary guardian.
        api.get("/api/messages?q=Unit", s.admin.accessToken())
                .andExpect(jsonPath("$.items[?(@.templateKey == 'exams.results_published')].recipientName",
                        containsInAnyOrder("Lata Rao", "Uma Rao", "Gita Rao")));

        api.get("/api/me/children/" + s.asha + "/results", mother.accessToken())
                .andExpect(jsonPath("$.results[0].examName").value("Unit Test 1"))
                .andExpect(jsonPath("$.results[0].percent").value(88.57))
                .andExpect(jsonPath("$.results[0].result").value("PASS"))
                .andExpect(jsonPath("$.terms[0].published").value(true));
        api.get("/api/me/children/" + s.asha + "/report-card?termId=" + term, mother.accessToken())
                .andExpect(jsonPath("$.kind").value("TERM"))
                .andExpect(jsonPath("$.columns[*].name", contains("Unit Test 1")))
                .andExpect(jsonPath("$.cards.length()").value(1))
                .andExpect(jsonPath("$.cards[0].subjects[*].subjectName", contains("English", "Mathematics")))
                .andExpect(jsonPath("$.cards[0].subjects[0].percent").value(90))
                .andExpect(jsonPath("$.cards[0].subjects[1].percent").value(88))
                .andExpect(jsonPath("$.cards[0].percent").value(88.57))
                .andExpect(jsonPath("$.cards[0].rank").value(1))
                .andExpect(jsonPath("$.cards[0].coScholastic.artEducation").value("B"))
                .andExpect(jsonPath("$.cards[0].remark").value("Asha reads widely."))
                .andExpect(jsonPath("$.cards[0].hpc.selfReflection").value("I like stories."))
                .andExpect(jsonPath("$.cards[0].attendance").exists());
        api.get("/api/me/children/" + s.asha + "/report-card?yearId=" + s.yearId, mother.accessToken())
                .andExpect(jsonPath("$.kind").value("YEAR"))
                .andExpect(jsonPath("$.columns[*].name", contains("Term 1")))
                .andExpect(jsonPath("$.cards[0].percent").value(88.57));
        api.get("/api/me/children/" + s.asha + "/report-card", mother.accessToken())
                .andExpect(status().isBadRequest());
        api.get("/api/me/children/" + s.dev + "/report-card?termId=" + term, mother.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/me/report-card?termId=" + term, s.login("asha").accessToken())
                .andExpect(jsonPath("$.cards[0].fullName").value("Asha Rao"));
        api.get("/api/me/results", ravi.accessToken()).andExpect(status().isNotFound());

        // Staff report cards for the whole section.
        api.get("/api/exams/report-cards?sectionId=" + s.s5A + "&termId=" + term, ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedOnly").value(false))
                .andExpect(jsonPath("$.classTeacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.cards[*].fullName", contains("Asha Rao", "Bala Rao")))
                .andExpect(jsonPath("$.cards[1].result").value("NEEDS_IMPROVEMENT"));
        api.get("/api/exams/report-cards/students/" + s.dev + "?termId=" + term, ravi.accessToken())
                .andExpect(jsonPath("$.cards[0].fullName").value("Dev Rao"))
                .andExpect(jsonPath("$.cards[0].subjects[0].cells[0].code").value("AB"));

        // Result analysis and its download.
        api.get("/api/exams/" + exam + "/classes/" + s.class5 + "/analysis", ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students").value(3))
                .andExpect(jsonPath("$.appeared").value(3))
                .andExpect(jsonPath("$.passed").value(1))
                .andExpect(jsonPath("$.subjects[0].subjectName").value("Mathematics"))
                .andExpect(jsonPath("$.subjects[0].appeared").value(3))
                .andExpect(jsonPath("$.subjects[0].highest").value(88))
                .andExpect(jsonPath("$.subjects[0].lowest").value(25))
                .andExpect(jsonPath("$.subjects[1].absent").value(1))
                .andExpect(jsonPath("$.subjects[1].appeared").value(2))
                .andExpect(jsonPath("$.subjects[1].passPercent").value(50))
                .andExpect(jsonPath("$.sections[*].label", contains("Class 5 A", "Class 5 B")))
                .andExpect(jsonPath("$.belowPass[*].fullName", containsInAnyOrder("Bala Rao", "Dev Rao")))
                .andExpect(jsonPath("$.previousExamId").doesNotExist());
        ResultActions csv = api.get("/api/exams/" + exam + "/classes/" + s.class5 + "/analysis.csv",
                ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString(".csv")));
        String body = new String(csv.andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿").contains("Result analysis: Unit Test 1").contains("Bala Rao")
                .contains("Mathematics");
        api.get("/api/exams/" + exam + "/classes/" + s.class5 + "/analysis", s.login("accounts").accessToken())
                .andExpect(status().isForbidden());

        // Published marks cannot be unlocked or have their maximum changed; withdrawing hides them again.
        String mathsA = "/api/exams/papers/" + maths + "/sections/" + s.s5A + "/marks";
        api.post(mathsA + "/unlock", principal.accessToken(), "{\"reason\":\"Fix\"}")
                .andExpect(status().isConflict());
        api.put("/api/exams/" + exam + "/classes/" + s.class5 + "/papers", s.admin.accessToken(), """
                {"papers":[{"subjectId":"%s","maxTheory":90,"maxInternal":20},{"subjectId":"%s","maxTheory":40}]}"""
                .formatted(s.maths, s.english)).andExpect(status().isConflict());
        String withdraw = "/api/exams/" + exam + "/classes/" + s.class5 + "/results/withdraw";
        api.post(withdraw, principal.accessToken(), "{}").andExpect(status().isBadRequest());
        api.post(withdraw, principal.accessToken(), "{\"reason\":\"Maths marks to correct\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultsPublishedAt").doesNotExist());
        api.post(withdraw, principal.accessToken(), "{\"reason\":\"Again\"}").andExpect(status().isConflict());
        api.get("/api/me/children/" + s.asha + "/results", mother.accessToken())
                .andExpect(jsonPath("$.results").isEmpty());
        api.post(mathsA + "/unlock", principal.accessToken(), "{\"reason\":\"Fix\"}").andExpect(status().isOk());
        api.get("/api/audit-events", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("results.published", "results.withdrawn",
                        "term_record.saved", "report.exported", "exam_settings.updated")));

        // Another school.
        Session otherAdmin = api.login(api.signup());
        api.post(publish, otherAdmin.accessToken(), null).andExpect(status().isNotFound());
        api.get(results, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.get(records, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/exams/report-cards?sectionId=" + s.s5A + "&termId=" + term, otherAdmin.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/exams/" + exam + "/classes/" + s.class5 + "/analysis", otherAdmin.accessToken())
                .andExpect(status().isNotFound());
    }

    @Test
    void theDashboardShowsMarksToEnterAndResultsToPublish() throws Exception {
        Session ravi = s.login("ravi");
        api.get("/api/dashboard", ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exams.marksToEnter").value(2))
                .andExpect(jsonPath("$.exams.resultsToPublish").value(0));
        api.get("/api/dashboard", s.login("accounts").accessToken())
                .andExpect(jsonPath("$.exams").doesNotExist());
        Session principal = s.login("principal");
        api.get("/api/dashboard", principal.accessToken())
                .andExpect(jsonPath("$.exams.marksToEnter").value(4))
                .andExpect(jsonPath("$.exams.resultsToPublish").value(0));
        enter(s.admin, maths, s.s5A, line(s.asha, "70", "18", null) + "," + line(s.bala, "20", "5", null));
        enter(s.admin, english, s.s5A, line(s.asha, "36", null, null) + "," + line(s.bala, "12", null, null));
        api.get("/api/dashboard", ravi.accessToken()).andExpect(jsonPath("$.exams.marksToEnter").value(0));
        enter(s.admin, maths, s.s5B, line(s.dev, "60", "15", null));
        enter(s.admin, english, s.s5B, line(s.dev, "30", null, null));
        api.get("/api/dashboard", principal.accessToken())
                .andExpect(jsonPath("$.exams.marksToEnter").value(0))
                .andExpect(jsonPath("$.exams.resultsToPublish").value(1));
    }

    @Test
    void parallelPublishesOfOneClassAnswerOneSuccessAndConflicts() throws Exception {
        enter(s.admin, maths, s.s5A, line(s.asha, "70", "18", null) + "," + line(s.bala, "20", "5", null));
        enter(s.admin, english, s.s5A, line(s.asha, "36", null, null) + "," + line(s.bala, "12", null, null));
        enter(s.admin, maths, s.s5B, line(s.dev, "60", "15", null));
        enter(s.admin, english, s.s5B, line(s.dev, "30", null, null));
        Session principal = s.login("principal");
        String publish = "/api/exams/" + exam + "/classes/" + s.class5 + "/results/publish";
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return api.post(publish, principal.accessToken(), null).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(200, 409);
            assertThat(statuses).filteredOn(c -> c == 200).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        // Parents are told once, not once per request.
        String messages = api.get("/api/messages?q=Unit", s.admin.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> told = com.jayway.jsonpath.JsonPath.read(messages,
                "$.items[?(@.templateKey == 'exams.results_published')].recipientName");
        assertThat(told).doesNotHaveDuplicates();
    }

    /** Saves and submits a sheet. */
    private void enter(Session session, String paper, String section, String lines) throws Exception {
        String path = "/api/exams/papers/" + paper + "/sections/" + section + "/marks";
        String saved = api.put(path, session.accessToken(), marks(null, lines)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        api.post(path + "/submit", session.accessToken(), "{\"version\":" + ExamsSchool.read(saved, "$.version")
                + "}").andExpect(status().isOk());
    }

    private static String marks(Long version, String lines) {
        return "{\"version\":" + version + ",\"entries\":[" + lines + "]}";
    }

    private static String line(String student, String theory, String internal, String code) {
        return "{\"studentId\":\"%s\",\"theory\":%s,\"internal\":%s,\"code\":%s}".formatted(student, theory,
                internal, code == null ? "null" : "\"" + code + "\"");
    }

    private static String recordFor(String student, String grade, String remark) {
        return """
                {"entries":[{"studentId":"%s","workEducation":"%s","remark":"%s"}]}""".formatted(student, grade,
                remark);
    }
}
