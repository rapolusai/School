package com.akshara.homework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.audit.AuditService.Actor;
import com.akshara.homework.HomeworkService.HomeworkInput;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Who may set and see homework, submissions and reviews, reminders to parents, and other schools. */
class HomeworkIT extends IntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);

    @Autowired
    HomeworkReminderRunner reminderRunner;

    @Autowired
    StudentHomeworkService learners;

    @Autowired
    HomeworkService homeworkService;

    private final LocalDate today = LocalDate.now(INDIA);
    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String ravi;
    private String kavitha;
    private String maths;
    private String english;
    private String music;
    private String class5A;
    private String class5B;
    private String asha;
    private String bala;
    private String dev;

    @BeforeEach
    void schoolWithTeachersStudentsAndParents() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fixtures.year(admin, "This year", today.minusDays(150).toString(), today.plusDays(200).toString(), true);
        ravi = api.createUser(admin, "Ravi Kumar", email("ravi"), List.of("TEACHER"));
        kavitha = api.createUser(admin, "Kavitha Menon", email("kavitha"), List.of("TEACHER"));
        String sita = api.createUser(admin, "Sita Devi", email("sita"), List.of("TEACHER"));
        api.createUser(admin, "Lakshmi Iyer", email("principal"), List.of("PRINCIPAL"));
        api.createUser(admin, "Meena Reddy", email("accounts"), List.of("ACCOUNTANT"));
        maths = subject("Mathematics");
        english = subject("English");
        music = subject("Music");
        String class5 = fixtures.schoolClass(admin, "Class 5");
        api.put("/api/academics/classes/" + class5 + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s","%s"]}""".formatted(maths, english)).andExpect(status().isOk());
        class5A = section(class5, "A", ravi);
        class5B = section(class5, "B", sita);
        // Kavitha teaches English in both sections; Ravi is class teacher of 5 A, Sita of 5 B.
        assign(class5A, english, kavitha);
        assign(class5B, english, kavitha);
        asha = fixtures.student(admin, class5A, "A-1", "Asha", "Rao", "Lata Rao", "9876500011");
        bala = fixtures.student(admin, class5A, "A-2", "Bala", "Rao", "Uma Rao", "9876500012");
        dev = fixtures.student(admin, class5B, "B-1", "Dev", "Rao", "Gita Rao", "9876500021");
        signIn(asha, "asha");
        signIn(dev, "dev");
        String mother = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("mother"), TestApi.PASSWORD))
                .andExpect(status().isOk());
    }

    @Test
    void teachersSetHomeworkOnlyForTheirOwnSectionsAndSubjects() throws Exception {
        Session raviSession = login("ravi");
        Session kavithaSession = login("kavitha");
        Session sitaSession = login("sita");

        // A class teacher may set any subject of their section; a subject teacher only their subject.
        api.get("/api/homework/options", raviSession.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[*].label", contains("Class 5 A")))
                .andExpect(jsonPath("$.sections[0].subjects[*].name", containsInAnyOrder("English", "Mathematics")))
                .andExpect(jsonPath("$.maxAttachments").value(5));
        api.get("/api/homework/options", kavithaSession.accessToken())
                .andExpect(jsonPath("$.sections[*].label", contains("Class 5 A", "Class 5 B")))
                .andExpect(jsonPath("$.sections[1].subjects[*].name", contains("English")));

        String mathsHomework = create(raviSession, List.of(class5A), maths, "Fractions", today.plusDays(2), true);
        api.post("/api/homework", raviSession.accessToken(), homework(List.of(class5B), maths, "Not mine",
                today.plusDays(2), true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.sectionIds").exists());
        api.post("/api/homework", kavithaSession.accessToken(), homework(List.of(class5A), maths, "Not mine",
                today.plusDays(2), true)).andExpect(status().isForbidden());
        String englishHomework = create(kavithaSession, List.of(class5A, class5B), english, "Letter writing",
                today.plusDays(3), true);

        // Ravi sees both 5 A items but can change only his own; Sita sees only what was set for 5 B.
        api.get("/api/homework", raviSession.accessToken())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].title", contains("Fractions", "Letter writing")))
                .andExpect(jsonPath("$.items[0].canEdit").value(true))
                .andExpect(jsonPath("$.items[1].canEdit").value(false))
                .andExpect(jsonPath("$.items[1].sections[*].label", contains("Class 5 A", "Class 5 B")))
                .andExpect(jsonPath("$.items[0].counts.students").value(2));
        api.get("/api/homework", sitaSession.accessToken())
                .andExpect(jsonPath("$.items[*].title", contains("Letter writing")));
        api.get("/api/homework/" + mathsHomework, sitaSession.accessToken()).andExpect(status().isNotFound());
        api.put("/api/homework/" + mathsHomework, sitaSession.accessToken(), homework(List.of(class5A), maths, "X",
                today.plusDays(2), true)).andExpect(status().isNotFound());
        api.put("/api/homework/" + englishHomework, raviSession.accessToken(), homework(List.of(class5A), english,
                "Mine now", today.plusDays(2), true)).andExpect(status().isForbidden());
        api.get("/api/homework?sectionId=" + class5B, login("principal").accessToken())
                .andExpect(jsonPath("$.total").value(1));
        api.get("/api/homework", login("accounts").accessToken()).andExpect(status().isForbidden());

        // The form's rules.
        api.post("/api/homework", raviSession.accessToken(), homework(List.of(class5A), maths, "Late",
                today.minusDays(1), true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dueOn").exists());
        api.post("/api/homework", raviSession.accessToken(), """
                {"sectionIds":["%s"],"subjectId":"%s","title":"Future","assignedOn":"%s","dueOn":"%s",
                 "onlineSubmission":true}""".formatted(class5A, maths, today.plusDays(1), today.plusDays(3)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.assignedOn").exists());
        api.post("/api/homework", admin.accessToken(), homework(List.of(class5A), music, "Song", today.plusDays(1),
                true)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.subjectId").exists());
        api.post("/api/homework", admin.accessToken(), homework(List.of(class5A), maths, "  ", today.plusDays(1),
                true)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.title").exists());
        api.post("/api/homework", admin.accessToken(), homework(List.of(), maths, "None", today.plusDays(1), true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.sectionIds").exists());

        api.put("/api/homework/" + mathsHomework, raviSession.accessToken(), homework(List.of(class5A), maths,
                "Fractions and decimals", today.plusDays(4), false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Fractions and decimals"))
                .andExpect(jsonPath("$.dueOn").value(today.plusDays(4).toString()))
                .andExpect(jsonPath("$.onlineSubmission").value(false))
                .andExpect(jsonPath("$.canDelete").value(true));
        api.delete("/api/homework/" + mathsHomework, raviSession.accessToken()).andExpect(status().isNoContent());
        api.get("/api/homework/" + mathsHomework, raviSession.accessToken()).andExpect(status().isNotFound());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("homework.created", "homework.updated",
                        "homework.deleted")));
    }

    @Test
    void studentsSubmitAndResubmitUntilATeacherReviews() throws Exception {
        Session raviSession = login("ravi");
        Session ashaSession = login("asha");
        Session devSession = login("dev");
        Session parent = login("mother");
        String hw = create(raviSession, List.of(class5A), maths, "Fractions", today.plusDays(2), true);
        String attachment = TestApi.read(upload("/api/homework/" + hw + "/attachments", raviSession,
                file("file", "worksheet.pdf", PDF)).andExpect(status().isCreated()), "$.id");

        // The student sees their section's homework and downloads its file; a student of 5 B does not.
        api.get("/api/me/homework", ashaSession.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.items[0].title").value("Fractions"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].attachments").value(1));
        api.get("/api/me/homework/" + hw, ashaSession.accessToken())
                .andExpect(jsonPath("$.canSubmit").value(true))
                .andExpect(jsonPath("$.attachments[0].name").value("worksheet.pdf"));
        api.get("/api/files/" + attachment, ashaSession.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        api.get("/api/files/" + attachment, parent.accessToken()).andExpect(status().isOk());
        api.get("/api/me/homework", devSession.accessToken()).andExpect(jsonPath("$.items.length()").value(0));
        api.get("/api/me/homework/" + hw, devSession.accessToken()).andExpect(status().isNotFound());
        api.get("/api/files/" + attachment, devSession.accessToken()).andExpect(status().isNotFound());

        // Submitting: something is needed; text and a file; then again, keeping the file.
        upload("/api/me/homework/" + hw + "/submission", ashaSession)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.text").exists());
        String fileId = TestApi.read(upload("/api/me/homework/" + hw + "/submission", ashaSession,
                file("files", "answers.txt", "1) 3/4\n2) 7/8\n".getBytes(StandardCharsets.UTF_8)))
                .param("text", "My answers are attached.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submission.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submission.late").value(false))
                .andExpect(jsonPath("$.submission.attempts").value(1))
                .andExpect(jsonPath("$.submission.files[0].name").value("answers.txt")), "$.submission.files[0].id");
        upload("/api/me/homework/" + hw + "/submission", ashaSession)
                .param("text", "Corrected question 2.").param("keepFileIds", fileId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submission.attempts").value(2))
                .andExpect(jsonPath("$.submission.body").value("Corrected question 2."))
                .andExpect(jsonPath("$.submission.files.length()").value(1));
        api.get("/api/files/" + fileId, ashaSession.accessToken()).andExpect(status().isOk());
        api.get("/api/files/" + fileId, devSession.accessToken()).andExpect(status().isNotFound());
        // Parents see their child's work read-only.
        upload("/api/me/homework/" + hw + "/submission", parent).param("text", "Done by mum")
                .andExpect(status().isNotFound());
        api.get("/api/me/children/" + asha + "/homework", parent.accessToken())
                .andExpect(jsonPath("$.items[0].status").value("SUBMITTED"));
        api.get("/api/me/children/" + asha + "/homework/" + hw, parent.accessToken())
                .andExpect(jsonPath("$.canSubmit").value(false))
                .andExpect(jsonPath("$.submission.attempts").value(2));
        api.get("/api/me/children/" + dev + "/homework", parent.accessToken()).andExpect(status().isNotFound());
        api.get("/api/files/" + fileId, parent.accessToken()).andExpect(status().isOk());

        // The tracker: one submitted, one missing. Kavitha does not teach Mathematics in 5 A.
        String submission = TestApi.read(api.get("/api/homework/" + hw + "/submissions", raviSession.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.students").value(2))
                .andExpect(jsonPath("$.counts.submitted").value(1))
                .andExpect(jsonPath("$.counts.missing").value(1))
                .andExpect(jsonPath("$.rows[*].fullName", contains("Asha Rao", "Bala Rao")))
                .andExpect(jsonPath("$.rows[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.rows[0].files.length()").value(1))
                .andExpect(jsonPath("$.rows[1].status").value("MISSING")), "$.rows[0].submissionId");
        api.get("/api/files/" + fileId, raviSession.accessToken()).andExpect(status().isOk());
        api.get("/api/homework/" + hw + "/submissions", login("kavitha").accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/files/" + fileId, login("kavitha").accessToken()).andExpect(status().isNotFound());

        // Needs redo: the student can send it again; reviewed: no more changes.
        review(raviSession, hw, submission, "NEEDS_REDO", null, "Show your working.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_REDO"));
        api.get("/api/me/homework/" + hw, ashaSession.accessToken())
                .andExpect(jsonPath("$.submission.status").value("NEEDS_REDO"))
                .andExpect(jsonPath("$.submission.remark").value("Show your working."))
                .andExpect(jsonPath("$.canSubmit").value(true));
        upload("/api/me/homework/" + hw + "/submission", ashaSession).param("text", "Working shown.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submission.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submission.attempts").value(3))
                .andExpect(jsonPath("$.submission.files.length()").value(0));
        review(raviSession, hw, submission, "SUBMITTED", null, null).andExpect(status().isBadRequest());
        review(raviSession, hw, submission, "REVIEWED", "A", "Well done").andExpect(status().isOk())
                .andExpect(jsonPath("$.grade").value("A"))
                .andExpect(jsonPath("$.reviewedByName").value("Ravi Kumar"));
        upload("/api/me/homework/" + hw + "/submission", ashaSession).param("text", "One more change")
                .andExpect(status().isConflict());
        api.get("/api/me/homework/" + hw, ashaSession.accessToken())
                .andExpect(jsonPath("$.canSubmit").value(false))
                .andExpect(jsonPath("$.submission.grade").value("A"));
        // Homework with submissions cannot be deleted.
        api.delete("/api/homework/" + hw, raviSession.accessToken()).andExpect(status().isConflict());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("homework.submitted", "homework.reviewed",
                        "homework.attachment_added")));
    }

    @Test
    void submissionsAfterTheDueDateAreLateAndHomeworkLocksAfterIt() throws Exception {
        Session raviSession = login("ravi");
        String hw = create(raviSession, List.of(class5A), maths, "Due today", today, true);
        UUID ashaUser = UUID.fromString(TestApi.read(api.get("/api/me", login("asha").accessToken()), "$.id"));
        Instant tomorrowMorning = today.plusDays(1).atTime(9, 0).atZone(INDIA).toInstant();
        var detail = TenantContext.runAs(school.tenantId(), () -> learners.submit(ashaUser, UUID.fromString(hw),
                "Sorry, a day late.", List.of(), List.of(), new Actor(ashaUser, "Asha Rao"), tomorrowMorning));
        assertThat(detail.submission().late()).isTrue();
        api.get("/api/homework/" + hw + "/submissions", raviSession.accessToken())
                .andExpect(jsonPath("$.counts.late").value(1))
                .andExpect(jsonPath("$.rows[0].late").value(true));
        // After the due date the homework can no longer be changed.
        HomeworkInput change = new HomeworkInput(List.of(UUID.fromString(class5A)), UUID.fromString(maths),
                "Changed", "", null, today.plusDays(5), true);
        assertThatThrownBy(() -> TenantContext.runAs(school.tenantId(), () -> homeworkService.update(
                HomeworkScope.WHOLE_SCHOOL, UUID.fromString(hw), change, today.plusDays(1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        // Once students have submitted, sections can be added but not removed.
        api.put("/api/homework/" + hw, admin.accessToken(), homework(List.of(class5B), maths, "Moved", today,
                true)).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.sectionIds").exists());
        api.put("/api/homework/" + hw, admin.accessToken(), homework(List.of(class5A, class5B), maths, "Both",
                today, true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(2));
    }

    @Test
    void remindersReachParentsOnceAndOnlyWhenTheSchoolTurnsThemOn() throws Exception {
        Session raviSession = login("ravi");
        api.get("/api/homework/settings", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersEnabled").value(false));
        api.put("/api/homework/settings", raviSession.accessToken(), "{\"remindersEnabled\":true}")
                .andExpect(status().isForbidden());

        // Off (the default): nothing is queued, on assigning or the evening before.
        LocalDate tomorrow = today.plusDays(1);
        create(raviSession, List.of(class5A), maths, "Quiet homework", tomorrow, true);
        assertThat(reminderRunner.remindDue(tomorrow, Instant.now())).isZero();
        assertThat(queued()).isZero();

        api.put("/api/homework/settings", admin.accessToken(), "{\"remindersEnabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersEnabled").value(true));
        // On: setting homework tells the parents of both students of 5 A.
        String hw = create(raviSession, List.of(class5A), maths, "Table of 12", tomorrow, true);
        assertThat(queued()).isEqualTo(2);
        UUID ashaUser = UUID.fromString(TestApi.read(api.get("/api/me", login("asha").accessToken()), "$.id"));
        TenantContext.runAs(school.tenantId(), () -> learners.submit(ashaUser, UUID.fromString(hw), "Done",
                List.of(), List.of(), new Actor(ashaUser, "Asha Rao"), Instant.now()));
        // Handed in at school: no evening reminder.
        create(raviSession, List.of(class5A), english, "Bring a leaf", tomorrow, false);
        assertThat(queued()).isEqualTo(4);

        // The evening before: parents of students who have not submitted, once per homework and student.
        int sent = reminderRunner.remindDue(tomorrow, Instant.now());
        assertThat(sent).isEqualTo(3);
        assertThat(queued()).isEqualTo(7);
        assertThat(reminderRunner.remindDue(tomorrow, Instant.now())).isZero();
        assertThat(queued()).isEqualTo(7);
        api.get("/api/messages?size=20", admin.accessToken())
                .andExpect(jsonPath("$.items[*].templateKey", hasItems("homework.assigned", "homework.due")));
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("homework_settings.updated")));
    }

    @Test
    void anotherSchoolsHomeworkIsNotFound() throws Exception {
        String hw = create(admin, List.of(class5A), maths, "Ours", today.plusDays(2), true);
        String attachment = TestApi.read(upload("/api/homework/" + hw + "/attachments", admin,
                file("file", "sheet.pdf", PDF)).andExpect(status().isCreated()), "$.id");
        School other = api.signup();
        Session otherAdmin = api.login(other);
        new SchoolFixtures(api).year(otherAdmin, "This year", today.minusDays(150).toString(),
                today.plusDays(200).toString(), true);
        api.get("/api/homework/" + hw, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/homework/" + hw, otherAdmin.accessToken(), homework(List.of(class5A), maths, "Theirs",
                today.plusDays(2), true)).andExpect(status().isNotFound());
        api.delete("/api/homework/" + hw, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/homework/" + hw + "/submissions", otherAdmin.accessToken()).andExpect(status().isNotFound());
        upload("/api/homework/" + hw + "/attachments", otherAdmin, file("file", "x.pdf", PDF))
                .andExpect(status().isNotFound());
        api.get("/api/files/" + attachment, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.post("/api/homework", otherAdmin.accessToken(), homework(List.of(class5A), maths, "Theirs",
                today.plusDays(2), true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.sectionIds").exists());
        api.get("/api/homework?when=all", otherAdmin.accessToken()).andExpect(jsonPath("$.total").value(0));
    }

    // ------------------------------------------------------------------ helpers

    static String homework(List<String> sections, String subjectId, String title, LocalDate dueOn, boolean online) {
        return """
                {"sectionIds":[%s],"subjectId":"%s","title":"%s","instructions":"Page 12.\\nAll questions.",
                 "dueOn":"%s","onlineSubmission":%s}""".formatted(sections.stream().map(s -> "\"" + s + "\"")
                .reduce((a, b) -> a + "," + b).orElse(""), subjectId, title, dueOn, online);
    }

    private String create(Session session, List<String> sections, String subjectId, String title, LocalDate dueOn,
            boolean online) throws Exception {
        return TestApi.read(api.post("/api/homework", session.accessToken(), homework(sections, subjectId, title,
                dueOn, online)).andExpect(status().isCreated()), "$.id");
    }

    private ResultActions review(Session session, String hw, String submission, String state, String grade,
            String remark) throws Exception {
        return api.put("/api/homework/" + hw + "/submissions/" + submission + "/review", session.accessToken(), """
                {"status":"%s","grade":%s,"remark":%s}""".formatted(state, grade == null ? "null" : "\"" + grade + "\"",
                remark == null ? "null" : "\"" + remark + "\""));
    }

    static MockMultipartFile file(String part, String name, byte[] bytes) {
        return new MockMultipartFile(part, name, "application/octet-stream", bytes);
    }

    /** A multipart POST with files; add form fields with {@link Upload#param} before the expectations. */
    private Upload upload(String path, Session session, MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart(path);
        for (MockMultipartFile f : files) {
            request.file(f);
        }
        request.header("Authorization", "Bearer " + session.accessToken());
        return new Upload(request);
    }

    /** A multipart request still being built: form fields, then expectations. */
    private final class Upload {

        private final MockMultipartHttpServletRequestBuilder request;

        Upload(MockMultipartHttpServletRequestBuilder request) {
            this.request = request;
        }

        Upload param(String name, String value) {
            request.param(name, value);
            return this;
        }

        ResultActions andExpect(ResultMatcher matcher) throws Exception {
            return mvc.perform(request).andExpect(matcher);
        }
    }

    private long queued() throws Exception {
        return Long.parseLong(TestApi.read(api.get("/api/messages?status=QUEUED", admin.accessToken()), "$.total"));
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

    private void signIn(String studentId, String name) throws Exception {
        api.post("/api/students/" + studentId + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email(name), TestApi.PASSWORD))
                .andExpect(status().isOk());
    }

    private Session login(String name) throws Exception {
        return api.login(school.code(), email(name), TestApi.PASSWORD);
    }

    private String email(String name) {
        return name + "@" + school.code() + ".akshara.test";
    }
}
