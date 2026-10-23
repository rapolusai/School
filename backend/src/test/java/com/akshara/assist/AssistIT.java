package com.akshara.assist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akshara.shared.TenantContext;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * The assistance API end to end: settings and the feature switch, remark drafts for a section (what was used, edit,
 * accept into the report card, the monthly limit), the early warning watch list (run now, reasons, assignment,
 * notes, parent informed through the outbox), draft papers from the question bank, the writing helper, the usage
 * log, the reports, permissions and another school's ids.
 */
class AssistIT extends IntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String ON = """
            {"enabled":true,"remarksEnabled":true,"earlyWarningEnabled":true,"papersEnabled":true,
             "writingEnabled":true,"monthlyLimit":500,"language":"en",
             "weights":{"attendance30":25,"attendance90":15,"marksTrend":25,"feeDues":10,"homework":25,
             "threshold":30}}""";

    @Autowired
    RiskRunner runner;

    private final LocalDate today = LocalDate.now(INDIA);
    private School school;
    private Session admin;
    private Session ravi;
    private Session principal;
    private Session accountant;
    private String raviId;
    private String yearId;
    private String class5;
    private String s5A;
    private String maths;
    private String english;
    private String asha;
    private String bala;
    private String term;
    private String exam;

    @BeforeEach
    void schoolWithAnExamAndMarks() throws Exception {
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        yearId = fixtures.year(admin, "This year", today.minusDays(150).toString(), today.plusDays(200).toString(),
                true);
        raviId = api.createUser(admin, "Ravi Kumar", email("ravi"), List.of("TEACHER"));
        api.createUser(admin, "Lakshmi Iyer", email("principal"), List.of("PRINCIPAL"));
        api.createUser(admin, "Meena Reddy", email("accounts"), List.of("ACCOUNTANT"));
        ravi = login("ravi");
        principal = login("principal");
        accountant = login("accounts");
        maths = subject("Mathematics");
        english = subject("English");
        class5 = fixtures.schoolClass(admin, "Class 5");
        api.put("/api/academics/classes/" + class5 + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s","%s"]}""".formatted(maths, english)).andExpect(status().isOk());
        s5A = TestApi.read(api.post("/api/academics/classes/" + class5 + "/sections", admin.accessToken(), """
                {"name":"A","classTeacherId":"%s"}""".formatted(raviId)).andExpect(status().isCreated()), "$.id");
        asha = TestApi.read(api.post("/api/students", admin.accessToken(), SchoolFixtures.studentJson(s5A, "A-1",
                "Asha", "Rao", "Lata Rao", "9876500011", 1)).andExpect(status().isCreated()), "$.id");
        bala = TestApi.read(api.post("/api/students", admin.accessToken(), SchoolFixtures.studentJson(s5A, "A-2",
                "Bala", "Rao", "Uma Rao", "9876500012", 2)).andExpect(status().isCreated()), "$.id");
        // A term with one exam, written last week: Asha does well, Bala poorly.
        term = TestApi.read(api.post("/api/exams/terms", admin.accessToken(), """
                {"academicYearId":"%s","name":"Term 1","startsOn":"%s","endsOn":"%s"}"""
                .formatted(yearId, today.minusDays(150), today.plusDays(200))).andExpect(status().isCreated()),
                "$.terms[0].id");
        exam = TestApi.read(api.post("/api/exams", admin.accessToken(), """
                {"termId":"%s","name":"Unit Test 1","classIds":["%s"]}""".formatted(term, class5))
                .andExpect(status().isCreated()), "$.terms[0].exams[0].id");
        String papers = api.put("/api/exams/" + exam + "/classes/" + class5 + "/papers", admin.accessToken(), """
                {"papers":[{"subjectId":"%s","maxTheory":40,"date":"%s","startTime":"09:30","durationMinutes":90},
                  {"subjectId":"%s","maxTheory":40,"date":"%s","startTime":"09:30","durationMinutes":90}]}"""
                .formatted(maths, schoolDay(-7), english, schoolDay(-6))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String mathsPaper = first(papers, "$.papers[?(@.subjectId == '" + maths + "')].id");
        String englishPaper = first(papers, "$.papers[?(@.subjectId == '" + english + "')].id");
        marks(mathsPaper, 36, 10);
        marks(englishPaper, 30, 12);
        // Bala missed most of the last two weeks.
        for (int d = 1; d <= 12; d++) {
            LocalDate day = today.minusDays(d);
            if (day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            api.put("/api/attendance/registers/" + s5A + "/" + day, admin.accessToken(), """
                    {"entries":[{"studentId":"%s","status":"PRESENT"},{"studentId":"%s","status":"%s"}]}"""
                    .formatted(asha, bala, d <= 9 ? "ABSENT" : "PRESENT")).andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ settings and switches

    @Test
    void assistanceIsOffUntilTheSchoolAdminSwitchesItOn() throws Exception {
        api.get("/api/assist/settings", ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.provider").value("simulated"))
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.monthlyLimit").value(500));
        api.get("/api/assist/settings", accountant.accessToken()).andExpect(status().isForbidden());
        api.put("/api/assist/settings", ravi.accessToken(), ON).andExpect(status().isForbidden());
        api.put("/api/assist/settings", principal.accessToken(), ON).andExpect(status().isForbidden());
        // Off: every feature answers 409 with a clear message.
        api.post("/api/assist/remarks/drafts", ravi.accessToken(), """
                {"termId":"%s","sectionId":"%s","tone":"ENCOURAGING"}""".formatted(term, s5A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(AssistSettingsService.OFF_TYPE))
                .andExpect(jsonPath("$.detail").value("AI assistance is switched off for this school. A School "
                        + "Admin can switch it on under Assistance settings."));
        api.post("/api/assist/watch-list/run", principal.accessToken(), null).andExpect(status().isConflict());
        api.post("/api/assist/writing/improve", ravi.accessToken(), """
                {"text":"hello"}""").andExpect(status().isConflict());
        // On, but with papers off.
        api.put("/api/assist/settings", admin.accessToken(), ON.replace("\"papersEnabled\":true",
                "\"papersEnabled\":false")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.papersEnabled").value(false))
                .andExpect(jsonPath("$.saved").value(true));
        api.post("/api/assist/papers/draft", ravi.accessToken(), paperDraft("Unit test")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(AssistSettingsService.FEATURE_OFF_TYPE));
        api.put("/api/assist/settings", admin.accessToken(), ON.replace("\"language\":\"en\"", "\"language\":\"xx\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.language").exists());
        api.put("/api/assist/settings", admin.accessToken(), ON.replace("\"attendance30\":25", "\"attendance30\":0")
                .replace("\"attendance90\":15", "\"attendance90\":0").replace("\"marksTrend\":25", "\"marksTrend\":0")
                .replace("\"feeDues\":10", "\"feeDues\":0").replace("\"homework\":25", "\"homework\":0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['weights.attendance30']").exists());
        assertThat(audit("assist_settings.updated")).hasSize(1);
    }

    // ------------------------------------------------------------------ remark drafts

    @Test
    void aTeacherDraftsEditsAndAcceptsRemarksForTheirSection() throws Exception {
        api.put("/api/assist/settings", admin.accessToken(), ON).andExpect(status().isOk());
        api.get("/api/assist/remarks?termId=" + term + "&sectionId=" + s5A, ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.canAccept").value(true))
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.students", hasSize(2)))
                .andExpect(jsonPath("$.students[0].draft").doesNotExist());
        String drafts = api.post("/api/assist/remarks/drafts", ravi.accessToken(), """
                {"termId":"%s","sectionId":"%s","tone":"ENCOURAGING"}""".formatted(term, s5A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].provider").value("simulated"))
                .andExpect(jsonPath("$[0].simulated").value(true))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].inputs.first").value("Asha"))
                .andExpect(jsonPath("$[0].inputs.classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$[0].inputs.percent").value("82.5"))
                .andExpect(jsonPath("$[0].inputs.best").value("Mathematics"))
                .andExpect(jsonPath("$[0].inputLines", hasItems("Mathematics: 90% (A2)", "English: 75% (B1)")))
                .andReturn().getResponse().getContentAsString();
        String ashaDraft = JsonPath.read(drafts, "$[0].id");
        String text = JsonPath.read(drafts, "$[0].draftText");
        assertThat(text).startsWith("Asha has done very well in Class 5 A this term, scoring 82.5% overall");
        // What was used never includes the surname, admission number or the guardian.
        Map<String, String> inputs = JsonPath.read(drafts, "$[0].inputs");
        assertThat(inputs.keySet()).containsExactlyInAnyOrder("first", "classLabel", "tone", "percent", "grade",
                "attendance", "best", "weak");
        assertThat(inputs.values()).noneMatch(v -> v.contains("Rao") || v.contains("A-1") || v.contains("9876"));
        // Drafting again replaces the drafts (same text: deterministic) and the sheet shows them.
        api.post("/api/assist/remarks/drafts", ravi.accessToken(), """
                {"termId":"%s","sectionId":"%s","tone":"FORMAL","studentIds":["%s"]}""".formatted(term, s5A, bala))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tone").value("FORMAL"))
                .andExpect(jsonPath("$[0].draftText").value(org.hamcrest.Matchers.startsWith("Bala secured 27.5% "
                        + "overall")));
        api.get("/api/assist/remarks?termId=" + term + "&sectionId=" + s5A, ravi.accessToken())
                .andExpect(jsonPath("$.students[0].draft.id").value(ashaDraft))
                .andExpect(jsonPath("$.students[1].draft.tone").value("FORMAL"));
        // A subject remark.
        api.post("/api/assist/remarks/drafts", ravi.accessToken(), """
                {"termId":"%s","sectionId":"%s","tone":"ENCOURAGING","studentIds":["%s"],"subjectId":"%s"}"""
                .formatted(term, s5A, asha, english))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subjectName").value("English"))
                .andExpect(jsonPath("$[0].draftText").value("In English, Asha scored 75% (B1). A strong grasp of "
                        + "the subject; keep it up!"));
        // Edit, then accept: the remark lands on the report card.
        api.put("/api/assist/remarks/" + ashaDraft, ravi.accessToken(), """
                {"text":"  "}""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.text").exists());
        api.put("/api/assist/remarks/" + ashaDraft, ravi.accessToken(), """
                {"text":"%s Keep reading daily."}""".formatted(text)).andExpect(status().isOk())
                .andExpect(jsonPath("$.draftText").value(text + " Keep reading daily."));
        api.post("/api/assist/remarks/" + ashaDraft + "/accept", ravi.accessToken(), """
                {"text":"%s Keep reading daily."}""".formatted(text)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.acceptedByName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.acceptedText").value(text + " Keep reading daily."));
        api.get("/api/exams/terms/" + term + "/sections/" + s5A + "/records", ravi.accessToken())
                .andExpect(jsonPath("$.students[?(@.studentId == '" + asha + "')].remark")
                        .value(hasItem(text + " Keep reading daily.")));
        api.get("/api/assist/remarks?termId=" + term + "&sectionId=" + s5A, ravi.accessToken())
                .andExpect(jsonPath("$.students[0].currentRemark").value(text + " Keep reading daily."))
                .andExpect(jsonPath("$.students[0].draft.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.students[0].subjectDrafts", hasSize(1)));
        api.put("/api/assist/remarks/" + ashaDraft, ravi.accessToken(), """
                {"text":"Changed"}""").andExpect(status().isConflict());
        // Rules: students of another section, a subject the class does not take, and the audit trail.
        api.post("/api/assist/remarks/drafts", ravi.accessToken(), """
                {"termId":"%s","sectionId":"%s","tone":"ENCOURAGING","studentIds":["%s"]}"""
                .formatted(term, s5A, UUID.randomUUID())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentIds").exists());
        api.post("/api/assist/remarks/drafts", ravi.accessToken(), """
                {"termId":"%s","sectionId":"%s","tone":"ENCOURAGING","subjectId":"%s"}"""
                .formatted(term, s5A, UUID.randomUUID())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.subjectId").exists());
        api.get("/api/assist/remarks?termId=" + term + "&sectionId=" + s5A, accountant.accessToken())
                .andExpect(status().isForbidden());
        assertThat(audit("assist_remark.drafted")).hasSize(4)
                .allSatisfy(e -> assertThat(details(e).get("provider")).isEqualTo("simulated"));
        assertThat(audit("assist_remark.accepted")).hasSize(1)
                .allSatisfy(e -> assertThat(details(e).get("savedToReportCard")).isEqualTo(true));
        // Usage: four drafts this month, each with a hash and never the text.
        api.get("/api/assist/usage", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(4))
                .andExpect(jsonPath("$.limit").value(500))
                .andExpect(jsonPath("$.byFeature[0].feature").value("REMARK"))
                .andExpect(jsonPath("$.byFeature[0].calls").value(4))
                .andExpect(jsonPath("$.rows", hasSize(4)))
                .andExpect(jsonPath("$.rows[0].userName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.rows[0].requestHash").value(org.hamcrest.Matchers.matchesRegex("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.rows[0].tokenEstimate").value(org.hamcrest.Matchers.greaterThan(0)));
        api.get("/api/assist/usage?month=2026-13", admin.accessToken()).andExpect(status().isBadRequest());
        api.get("/api/assist/usage", ravi.accessToken()).andExpect(status().isForbidden());
        api.get("/api/assist/settings", ravi.accessToken()).andExpect(jsonPath("$.usedThisMonth").value(4));
    }

    @Test
    void theMonthlyLimitStopsDraftsBeforeTheProviderIsCalledAndOnlyTheClassTeacherAccepts() throws Exception {
        api.put("/api/assist/settings", admin.accessToken(), ON.replace("\"monthlyLimit\":500",
                "\"monthlyLimit\":1")).andExpect(status().isOk());
        api.post("/api/assist/remarks/drafts", principal.accessToken(), """
                {"termId":"%s","sectionId":"%s","tone":"ENCOURAGING"}""".formatted(term, s5A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(UsageService.LIMIT_TYPE))
                .andExpect(jsonPath("$.detail").value("This month's limit of 1 drafts has been reached (0 used). "
                        + "A School Admin can raise it under Assistance settings."));
        String id = TestApi.read(api.post("/api/assist/remarks/drafts", principal.accessToken(), """
                {"termId":"%s","sectionId":"%s","tone":"ENCOURAGING","studentIds":["%s"]}"""
                .formatted(term, s5A, asha)).andExpect(status().isOk()), "$[0].id");
        api.post("/api/assist/writing/improve", principal.accessToken(), """
                {"text":"hello"}""").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(UsageService.LIMIT_TYPE));
        api.get("/api/assist/usage", admin.accessToken()).andExpect(jsonPath("$.used").value(1));
        // Another teacher (not class teacher of 5 A) may read drafts but the exams module refuses the write.
        api.createUser(admin, "Sita Devi", email("sita"), List.of("TEACHER"));
        Session sita = login("sita");
        api.get("/api/assist/remarks?termId=" + term + "&sectionId=" + s5A, sita.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.canAccept").value(false));
        api.post("/api/assist/remarks/" + id + "/accept", sita.accessToken(), """
                {"text":"Fine."}""").andExpect(status().isForbidden());
        api.post("/api/assist/remarks/" + id + "/accept", principal.accessToken(), """
                {"text":"Fine."}""").andExpect(status().isOk());
    }

    @Test
    void anAcceptedRemarkCannotBeAcceptedAgainEvenByParallelRequests() throws Exception {
        api.put("/api/assist/settings", admin.accessToken(), ON).andExpect(status().isOk());
        String drafts = api.post("/api/assist/remarks/drafts", ravi.accessToken(), """
                {"termId":"%s","sectionId":"%s","tone":"ENCOURAGING"}""".formatted(term, s5A))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String ashaDraft = JsonPath.read(drafts, "$[0].id");
        String balaDraft = JsonPath.read(drafts, "$[1].id");
        api.post("/api/assist/remarks/" + ashaDraft + "/accept", ravi.accessToken(), """
                {"text":"Reads well."}""").andExpect(status().isOk());
        api.post("/api/assist/remarks/" + ashaDraft + "/accept", ravi.accessToken(), """
                {"text":"Overwritten after accept"}""").andExpect(status().isConflict());
        api.get("/api/exams/terms/" + term + "/sections/" + s5A + "/records", ravi.accessToken())
                .andExpect(jsonPath("$.students[?(@.studentId == '" + asha + "')].remark")
                        .value(hasItem("Reads well.")));

        // Five accepts of one draft at once: one wins, the others are told it was accepted (never a 500).
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                String text = "Attempt " + i + ".";
                results.add(pool.submit(() -> {
                    start.await();
                    return api.post("/api/assist/remarks/" + balaDraft + "/accept", ravi.accessToken(), """
                            {"text":"%s"}""".formatted(text)).andReturn().getResponse().getStatus();
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
        assertThat(audit("assist_remark.accepted")).hasSize(2);
    }

    @Test
    void theWritingHelperRefusesPhoneAndAadhaarNumbersWrittenWithSpacesOrDashes() throws Exception {
        api.put("/api/assist/settings", admin.accessToken(), ON).andExpect(status().isOk());
        for (String text : List.of("Call 98765 43210", "phone: 98765-43210", "Aadhaar 2345-6789-0123",
                "Ring +91 98765 43210 today", "Call 987-654-3210", "Aadhaar 234567890123")) {
            api.post("/api/assist/writing/improve", ravi.accessToken(), """
                    {"text":"%s"}""".formatted(text)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.text").exists());
        }
        // Ordinary numbers in a circular still go through.
        for (String text : List.of("Fees of Rs 12,500 are due on 2026-11-15 for classes 6 7 8 9 10",
                "Marks: 78 65 90 45 88 and 92.5% overall", "Bus 12 leaves at 7 30 from stop 4")) {
            api.post("/api/assist/writing/improve", ravi.accessToken(), """
                    {"text":"%s"}""".formatted(text)).andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ early warning

    @Test
    void thePrincipalRunsTheEarlyWarningAndFollowsAStudentUp() throws Exception {
        api.put("/api/assist/settings", admin.accessToken(), ON).andExpect(status().isOk());
        api.get("/api/assist/watch-list", principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastRunAt").doesNotExist())
                .andExpect(jsonPath("$.entries", hasSize(0)))
                .andExpect(jsonPath("$.assignees[*].name", hasItems("Ravi Kumar", "Lakshmi Iyer", "Asha Admin")));
        api.post("/api/assist/watch-list/run", principal.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students").value(2))
                .andExpect(jsonPath("$.listed").value(1));
        String list = api.get("/api/assist/watch-list", principal.accessToken())
                .andExpect(jsonPath("$.lastRunStudents").value(2))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].studentName").value("Bala Rao"))
                .andExpect(jsonPath("$.entries[0].classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.entries[0].status").value("OPEN"))
                .andExpect(jsonPath("$.entries[0].reasons[*].signal", hasItems("ATTENDANCE_30", "MARKS_LOW")))
                .andExpect(jsonPath("$.entries[0].reasons[?(@.signal == 'MARKS_LOW')].detail")
                        .value(hasItem("27.5% in Unit Test 1")))
                .andReturn().getResponse().getContentAsString();
        String entry = JsonPath.read(list, "$.entries[0].id");
        int score = JsonPath.read(list, "$.entries[0].score");
        assertThat(score).isGreaterThanOrEqualTo(40);
        api.get("/api/assist/watch-list?classId=" + UUID.randomUUID(), principal.accessToken())
                .andExpect(jsonPath("$.entries", hasSize(0)));
        api.get("/api/assist/watch-list?status=FOLLOWING", principal.accessToken())
                .andExpect(jsonPath("$.entries", hasSize(0)));
        // Assign to Ravi, add a note, inform the parent, resolve.
        api.put("/api/assist/watch-list/" + entry + "/assignment", principal.accessToken(), """
                {"userId":"%s"}""".formatted(UUID.randomUUID())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.userId").exists());
        api.put("/api/assist/watch-list/" + entry + "/assignment", principal.accessToken(), """
                {"userId":"%s"}""".formatted(raviId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.assignedToName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.entry.status").value("FOLLOWING"));
        api.post("/api/assist/watch-list/" + entry + "/notes", ravi.accessToken(), """
                {"body":"Spoke to Bala; will sit with him after class."}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.notes", hasSize(1)))
                .andExpect(jsonPath("$.notes[0].authorName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.entry.notes").value(1));
        api.post("/api/assist/watch-list/" + entry + "/notes", ravi.accessToken(), """
                {"body":"   "}""").andExpect(status().isBadRequest());
        api.post("/api/assist/watch-list/" + entry + "/inform-parent", ravi.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.parentInformedAt").exists());
        // Bala's absences already queued absence alerts; the circular to the parent is the one from the watch list.
        api.get("/api/messages?relatedId=" + bala, admin.accessToken())
                .andExpect(jsonPath("$.items[?(@.templateKey == 'communication.circular')]", hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.templateKey == 'communication.circular')].recipient", hasItem("98765•••12")))
                .andExpect(jsonPath("$.items[?(@.templateKey == 'communication.circular')].relatedLabel", hasItem("Bala Rao")));
        String message = TestApi.read(api.get("/api/messages?relatedId=" + bala, admin.accessToken()),
                "$.items[0].id");
        api.get("/api/messages/" + message, admin.accessToken())
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("would like to talk with "
                        + "you about Bala's progress")))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("%"))));
        api.put("/api/assist/watch-list/" + entry + "/status", ravi.accessToken(), """
                {"status":"RESOLVED"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.status").value("RESOLVED"));
        // A resolved, assigned entry stays after the next run even when the score drops; the nightly runner works
        // across schools without a school selected.
        for (int d = 1; d <= 12; d++) {
            LocalDate day = today.minusDays(d);
            if (day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                api.put("/api/attendance/registers/" + s5A + "/" + day, admin.accessToken(), """
                        {"entries":[{"studentId":"%s","status":"PRESENT"},{"studentId":"%s","status":"PRESENT"}]}"""
                        .formatted(asha, bala)).andExpect(status().isOk());
            }
        }
        assertThat(runner.runAll(today, Instant.now())).isGreaterThanOrEqualTo(1);
        api.get("/api/assist/watch-list/" + entry, principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.status").value("RESOLVED"))
                .andExpect(jsonPath("$.entry.reasons[*].signal", hasItem("MARKS_LOW")));
        assertThat(audit("assist_watch.computed")).hasSize(2);
        assertThat(audit("assist_watch.assigned")).hasSize(1);
        assertThat(audit("assist_watch.parent_informed")).hasSize(1);
        // Nothing for families or the accountant; another school sees nothing.
        api.get("/api/assist/watch-list", accountant.accessToken()).andExpect(status().isForbidden());
        Session other = api.login(api.signup());
        api.put("/api/assist/settings", other.accessToken(), ON).andExpect(status().isOk());
        api.get("/api/assist/watch-list/" + entry, other.accessToken()).andExpect(status().isNotFound());
        api.get("/api/assist/watch-list", other.accessToken()).andExpect(jsonPath("$.entries", hasSize(0)));
        // The at-risk report and its CSV.
        api.get("/api/assist/reports/at-risk", principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.classes[0].classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.entries[0].studentName").value("Bala Rao"));
        api.get("/api/assist/reports/at-risk?classId=" + class5, principal.accessToken())
                .andExpect(jsonPath("$.total").value(1));
        byte[] csv = api.get("/api/assist/reports/at-risk.csv", principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"at-risk-students.csv\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(csv[0] & 0xFF).isEqualTo(0xEF);
        assertThat(new String(csv, 3, csv.length - 3, java.nio.charset.StandardCharsets.UTF_8))
                .startsWith("Class,Student,Score,Level,Status,Assigned to,Parent informed,Reasons\r\n")
                .contains("Class 5 A,Bala Rao,");
    }

    // ------------------------------------------------------------------ draft papers

    @Test
    void aTeacherBuildsEditsSavesAndDeletesADraftPaper() throws Exception {
        api.put("/api/assist/settings", admin.accessToken(), ON).andExpect(status().isOk());
        api.post("/api/assist/papers/draft", ravi.accessToken(), paperDraft("Unit test")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("No questions"));
        for (int i = 1; i <= 6; i++) {
            api.post("/api/lessons/questions", admin.accessToken(), """
                    {"classId":"%s","subjectId":"%s","type":"SHORT_ANSWER","difficulty":"%s","text":"Question %d",
                     "marks":%d,"shared":true}""".formatted(class5, maths, i <= 3 ? "EASY" : i <= 5 ? "MEDIUM"
                    : "HARD", i, i <= 4 ? 1 : 3)).andExpect(status().isCreated());
        }
        api.post("/api/lessons/questions", admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","type":"MCQ_SINGLE","difficulty":"EASY","text":"Pick one","marks":1,
                 "shared":true,"options":[{"text":"Two","correct":true},{"text":"Three","correct":false}]}"""
                .formatted(class5, maths)).andExpect(status().isCreated());
        String draft = api.post("/api/assist/papers/draft", ravi.accessToken(), paperDraft("Unit test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.className").value("Class 5"))
                .andExpect(jsonPath("$.subjectName").value("Mathematics"))
                .andExpect(jsonPath("$.totalMarks").value(10))
                .andExpect(jsonPath("$.instructions").value(org.hamcrest.Matchers.containsString(
                        "Time allowed: 60 minutes. Maximum marks: 10.")))
                .andExpect(jsonPath("$.sections[0].questions", hasSize(4)))
                .andExpect(jsonPath("$.sections[0].instructions").value("Answer all 4 questions. Each carries 1 mark."))
                .andExpect(jsonPath("$.sections[1].questions", hasSize(2)))
                .andExpect(jsonPath("$.sections[1].questions[0].marks").value(3))
                .andExpect(jsonPath("$.warnings", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        api.post("/api/assist/papers/draft", ravi.accessToken(), paperDraft("Unit test").replace("\"questions\":4",
                "\"questions\":9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasSize(3)));
        api.post("/api/assist/papers/draft", ravi.accessToken(), paperDraft("Unit test").replace("\"easyPercent\":50",
                "\"easyPercent\":70")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['blueprint.easyPercent']").exists());
        api.post("/api/assist/papers/draft", ravi.accessToken(), paperDraft("Unit test").replace(maths, english))
                .andExpect(status().isConflict());
        api.post("/api/assist/papers/draft", ravi.accessToken(), paperDraft("Unit test").replace(maths,
                UUID.randomUUID().toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.subjectId").exists());
        // Save the draft as it is, with a question removed.
        String save = draft.replaceFirst("\"id\":null,", "").replace("\"title\":\"Unit test\"",
                "\"title\":\"Unit test 1\"");
        String id = TestApi.read(api.post("/api/assist/papers", ravi.accessToken(), save)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Unit test 1"))
                .andExpect(jsonPath("$.totalMarks").value(10)), "$.id");
        api.get("/api/assist/papers", ravi.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].createdByName").value("Ravi Kumar"));
        String saved = api.get("/api/assist/papers/" + id, principal.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        api.put("/api/assist/papers/" + id, ravi.accessToken(), saved.replace("\"title\":\"Unit test 1\"",
                "\"title\":\"Unit test 1 (final)\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Unit test 1 (final)"))
                .andExpect(jsonPath("$.version").value(1));
        api.put("/api/assist/papers/" + id, ravi.accessToken(), saved).andExpect(status().isConflict());
        api.put("/api/assist/papers/" + id, ravi.accessToken(), saved.replace("\"version\":0", "\"version\":1")
                .replace("\"sections\":[", "\"sections\":[{\"name\":\"\",\"questions\":[]},"))
                .andExpect(status().isBadRequest());
        Session other = api.login(api.signup());
        api.put("/api/assist/settings", other.accessToken(), ON).andExpect(status().isOk());
        api.get("/api/assist/papers/" + id, other.accessToken()).andExpect(status().isNotFound());
        api.delete("/api/assist/papers/" + id, other.accessToken()).andExpect(status().isNotFound());
        api.delete("/api/assist/papers/" + id, ravi.accessToken()).andExpect(status().isNoContent());
        api.get("/api/assist/papers/" + id, ravi.accessToken()).andExpect(status().isNotFound());
        assertThat(audit("assist_paper.drafted")).hasSize(2);
        assertThat(audit("assist_paper.saved")).hasSize(1);
        assertThat(audit("assist_paper.deleted")).hasSize(1);
        api.get("/api/assist/usage", admin.accessToken()).andExpect(jsonPath("$.byFeature[0].feature").value("PAPER"))
                .andExpect(jsonPath("$.byFeature[0].calls").value(2));
    }

    // ------------------------------------------------------------------ writing help and the accepted report

    @Test
    void theWritingHelperTidiesACircularAndTheAcceptedReportCountsDrafts() throws Exception {
        api.put("/api/assist/settings", admin.accessToken(), ON).andExpect(status().isOk());
        api.post("/api/assist/writing/improve", ravi.accessToken(), """
                {"text":"dear parents,\\n\\nthe school   will remain closed on monday"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.provider").value("simulated"))
                .andExpect(jsonPath("$.text").value("Dear parents,\n\nThe school will remain closed on monday."
                        + "\n\nWarm regards,\nThe School Office"));
        api.post("/api/assist/writing/improve", ravi.accessToken(), """
                {"text":"call us on 9876543210"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").exists());
        api.post("/api/assist/writing/improve", ravi.accessToken(), """
                {"text":"   "}""").andExpect(status().isBadRequest());
        api.post("/api/assist/writing/improve", accountant.accessToken(), """
                {"text":"hello"}""").andExpect(status().isForbidden());
        // Hindi drafts once the school chooses Hindi.
        api.put("/api/assist/settings", admin.accessToken(), ON.replace("\"language\":\"en\"", "\"language\":\"hi\""))
                .andExpect(status().isOk());
        api.post("/api/assist/writing/improve", ravi.accessToken(), """
                {"text":"कल विद्यालय बंद रहेगा"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("कल विद्यालय बंद रहेगा।\n\nसादर,\nविद्यालय कार्यालय"));
        String id = TestApi.read(api.post("/api/assist/remarks/drafts", ravi.accessToken(), """
                {"termId":"%s","sectionId":"%s","tone":"ENCOURAGING","studentIds":["%s"]}"""
                .formatted(term, s5A, asha)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].language").value("hi"))
                .andExpect(jsonPath("$[0].draftText").value(org.hamcrest.Matchers.containsString("Asha ने"))),
                "$[0].id");
        api.post("/api/assist/remarks/" + id + "/accept", ravi.accessToken(), """
                {"text":"ठीक है।"}""").andExpect(status().isOk());
        String month = java.time.YearMonth.now(INDIA).toString();
        api.get("/api/assist/reports/drafts-accepted", principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value(month))
                .andExpect(jsonPath("$.drafted").value(1))
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rows[0].teacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.rows[0].month").value(month));
        api.get("/api/assist/reports/drafts-accepted?from=" + month + "&to=2020-01", principal.accessToken())
                .andExpect(status().isBadRequest());
        api.get("/api/assist/reports/drafts-accepted?from=bad", principal.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.from").exists());
        byte[] csv = api.get("/api/assist/reports/drafts-accepted.csv", principal.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(csv, java.nio.charset.StandardCharsets.UTF_8))
                .startsWith("﻿Teacher,Month,Drafted,Accepted\r\nRavi Kumar," + month + ",1,1\r\n");
        assertThat(audit("assist_writing.improved")).hasSize(2);
        assertThat(TenantContext.current()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private String paperDraft(String title) {
        return """
                {"classId":"%s","subjectId":"%s","title":"%s","durationMinutes":60,
                 "blueprint":{"sections":[{"name":"Section A","questions":4,"marksEach":1},
                   {"name":"Section B","questions":2,"marksEach":3}],"easyPercent":50,"mediumPercent":25,
                   "hardPercent":25,"bloomLevels":[],"topicIds":[]}}""".formatted(class5, maths, title);
    }

    private void marks(String paperId, int ashaMarks, int balaMarks) throws Exception {
        api.put("/api/exams/papers/" + paperId + "/sections/" + s5A + "/marks", admin.accessToken(), """
                {"version":null,"entries":[{"studentId":"%s","theory":%d},{"studentId":"%s","theory":%d}]}"""
                .formatted(asha, ashaMarks, bala, balaMarks)).andExpect(status().isOk());
    }

    /** A Monday-to-Saturday day {@code days} from today (negative for the past). */
    private LocalDate schoolDay(int days) {
        LocalDate d = today.plusDays(days);
        return d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.plusDays(days < 0 ? -1 : 1) : d;
    }

    private Session login(String name) throws Exception {
        return api.login(school.code(), email(name), TestApi.PASSWORD);
    }

    private String email(String name) {
        return name + "@" + school.code() + ".akshara.test";
    }

    private String subject(String name) throws Exception {
        return TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"%s"}""".formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private static String first(String json, String path) {
        List<Object> values = JsonPath.read(json, path);
        return values.isEmpty() ? null : values.getFirst().toString();
    }

    private List<Map<String, Object>> audit(String action) throws Exception {
        String json = api.get("/api/audit-events?limit=200", admin.accessToken()).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(json, "$[?(@.action == '" + action + "')]");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> event) {
        return (Map<String, Object>) event.get("details");
    }
}
