package com.akshara.lessons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * Online tests: built from the question bank by the teacher of the section and subject, published to sections,
 * taken by students one question at a time against a clock the server keeps, auto-marked except short answers that
 * the teacher marks, and released to students and parents. Attempts, deadlines, release rules, the messages a school
 * may switch on, and another school's tests.
 */
class OnlineTestIT extends IntegrationTest {

    private LessonsSchool s;
    private Session ravi;
    private String single;
    private String multi;
    private String trueFalse;
    private String blank;
    private String shortAnswer;

    @BeforeEach
    void school() throws Exception {
        s = new LessonsSchool(api);
        ravi = s.login("ravi");
        single = LessonsSchool.read(s.mcq(ravi, "What is 2 + 2?", "1"), "$.id");
        multi = s.question(ravi, """
                {"classId":"%s","subjectId":"%s","type":"MCQ_MULTI","difficulty":"MEDIUM","text":"Even numbers?",
                 "marks":2,"options":[{"text":"2","correct":true},{"text":"3","correct":false},
                 {"text":"4","correct":true}]}""");
        trueFalse = s.question(ravi, """
                {"classId":"%s","subjectId":"%s","type":"TRUE_FALSE","difficulty":"EASY","text":"10 is more than 9.",
                 "marks":1,"answerText":"TRUE","explanation":"Ten comes after nine."}""");
        blank = s.question(ravi, """
                {"classId":"%s","subjectId":"%s","type":"FILL_BLANK","difficulty":"HARD","text":"100 = ___ tens",
                 "marks":1,"answerText":"10\\nten"}""");
        shortAnswer = s.question(ravi, """
                {"classId":"%s","subjectId":"%s","type":"SHORT_ANSWER","difficulty":"HARD",
                 "text":"Why do we carry in addition?","marks":2}""");
    }

    @Test
    void aStudentTakesTheTestAndSeesTheScoreOnceTheTeacherReleasesIt() throws Exception {
        String test = draft(ravi, "Numbers quiz", Instant.now().minus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofDays(1)), 20, 1);
        api.put("/api/lessons/tests/" + test + "/questions", ravi.accessToken(), """
                {"questions":[{"questionId":"%s"},{"questionId":"%s"},{"questionId":"%s"},{"questionId":"%s"},
                 {"questionId":"%s"}]}""".formatted(single, multi, trueFalse, blank, shortAnswer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions", hasSize(5)))
                .andExpect(jsonPath("$.totalMarks").value(7.0))
                .andExpect(jsonPath("$.state").value("DRAFT"));

        // Not visible to students until published.
        Session asha = s.login("asha");
        api.get("/api/me/tests/" + test, asha.accessToken()).andExpect(status().isNotFound());
        api.get("/api/me/study", asha.accessToken()).andExpect(jsonPath("$.tests", hasSize(0)));
        api.post("/api/lessons/tests/" + test + "/publish", ravi.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.state").value("OPEN"));
        // Published: settings and questions are fixed.
        api.put("/api/lessons/tests/" + test, ravi.accessToken(), testJson("Renamed", Instant.now(),
                Instant.now().plus(Duration.ofDays(1)), 20, 1, s.s5A)).andExpect(status().isConflict());
        api.put("/api/lessons/tests/" + test + "/questions", ravi.accessToken(), """
                {"questions":[{"questionId":"%s"}]}""".formatted(single)).andExpect(status().isConflict());

        api.get("/api/me/study", asha.accessToken())
                .andExpect(jsonPath("$.tests[0].title").value("Numbers quiz"))
                .andExpect(jsonPath("$.tests[0].state").value("OPEN"))
                .andExpect(jsonPath("$.tests[0].questions").value(5))
                .andExpect(jsonPath("$.tests[0].canStart").value(true))
                .andExpect(jsonPath("$.tests[0].score").doesNotExist());
        // Dev in 5 B: not his test.
        Session dev = s.login("dev");
        api.get("/api/me/study", dev.accessToken()).andExpect(jsonPath("$.tests", hasSize(0)));
        api.get("/api/me/tests/" + test, dev.accessToken()).andExpect(status().isNotFound());
        api.post("/api/me/tests/" + test + "/attempts", dev.accessToken(), null).andExpect(status().isNotFound());

        // One question per screen: the questions in order, the options without the answers, the server's clock.
        String attempt = LessonsSchool.body(api.post("/api/me/tests/" + test + "/attempts", asha.accessToken(),
                null).andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.answered").value(0))
                .andExpect(jsonPath("$.secondsLeft", lessThanOrEqualTo(1200)))
                .andExpect(jsonPath("$.secondsLeft", greaterThan(1100)))
                .andExpect(jsonPath("$.questions[*].text", contains("What is 2 + 2?", "Even numbers?",
                        "10 is more than 9.", "100 = ___ tens", "Why do we carry in addition?")))
                .andExpect(jsonPath("$.questions[0].options[0].correct").doesNotExist())
                .andExpect(jsonPath("$.questions[2].options", hasSize(0))));
        assertThat(attempt).doesNotContain("correct").doesNotContain("Ten comes after nine")
                .doesNotContain("answerText");
        String attemptId = LessonsSchool.read(attempt, "$.id");
        // Starting again returns the attempt in progress.
        api.post("/api/me/tests/" + test + "/attempts", asha.accessToken(), null)
                .andExpect(jsonPath("$.id").value(attemptId));

        // Autosave, one answer at a time; the last save of an answer counts.
        save(asha, attempt, "What is 2 + 2?", options(attempt, "What is 2 + 2?", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answered").value(1));
        save(asha, attempt, "What is 2 + 2?", options(attempt, "What is 2 + 2?", "4")).andExpect(status().isOk());
        save(asha, attempt, "Even numbers?", options(attempt, "Even numbers?", "2")).andExpect(status().isOk());
        save(asha, attempt, "10 is more than 9.", "{\"text\":\"true\"}").andExpect(status().isOk());
        save(asha, attempt, "100 = ___ tens", "{\"text\":\"  Ten \"}").andExpect(status().isOk());
        save(asha, attempt, "Why do we carry in addition?", "{\"text\":\"Ten ones make a ten.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answered").value(5))
                .andExpect(jsonPath("$.questions[4].response").value("Ten ones make a ten."));
        // Answers must fit the question.
        save(asha, attempt, "What is 2 + 2?", options(attempt, "What is 2 + 2?", "3", "4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.optionIds").exists());
        save(asha, attempt, "What is 2 + 2?", options(attempt, "Even numbers?", "2"))
                .andExpect(status().isBadRequest());
        save(asha, attempt, "10 is more than 9.", "{\"text\":\"maybe\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").exists());
        api.put("/api/me/attempts/" + attemptId + "/answers/" + UUID.randomUUID(), asha.accessToken(), "{}")
                .andExpect(status().isNotFound());

        // The teacher sees the attempt in progress; another student cannot open it.
        api.get("/api/lessons/tests/" + test + "/results", ravi.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.students[*].studentName", contains("Asha Rao", "Bala Rao")))
                .andExpect(jsonPath("$.students[*].status", contains("IN_PROGRESS", "NOT_STARTED")))
                .andExpect(jsonPath("$.releaseBlocker", containsString("still open")));
        api.get("/api/me/attempts/" + attemptId, dev.accessToken()).andExpect(status().isNotFound());
        api.post("/api/me/attempts/" + attemptId + "/submit", dev.accessToken(), null)
                .andExpect(status().isNotFound());

        // Submitted: objective answers marked at once; the score stays hidden until released.
        api.post("/api/me/attempts/" + attemptId + "/submit", asha.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.autoSubmitted").value(false))
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.score").doesNotExist())
                .andExpect(jsonPath("$.questions", hasSize(0)))
                .andExpect(jsonPath("$.review", hasSize(0)));
        api.post("/api/me/attempts/" + attemptId + "/submit", asha.accessToken(), null)
                .andExpect(status().isConflict());
        save(asha, attempt, "What is 2 + 2?", options(attempt, "What is 2 + 2?", "3"))
                .andExpect(status().isConflict());
        api.post("/api/me/tests/" + test + "/attempts", asha.accessToken(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You have already taken this test."));
        api.get("/api/me/tests/" + test, asha.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.test.canStart").value(false))
                .andExpect(jsonPath("$.test.submitted").value(true))
                .andExpect(jsonPath("$.attempts[0].score").doesNotExist());
        Session mother = s.login("mother");
        api.get("/api/me/children/" + s.asha + "/study", mother.accessToken())
                .andExpect(jsonPath("$.tests[0].submitted").value(true))
                .andExpect(jsonPath("$.tests[0].score").doesNotExist())
                .andExpect(jsonPath("$.tests[0].canStart").value(false));

        // The teacher's results: 4 of 5 marked; the short answer waits for him (dashboard too).
        api.get("/api/lessons/tests/" + test + "/results", ravi.accessToken())
                .andExpect(jsonPath("$.students[0].status").value("MARKING"))
                .andExpect(jsonPath("$.students[0].score").value(4.0))
                .andExpect(jsonPath("$.pendingMarking").value(1))
                .andExpect(jsonPath("$.questions[*].correct", contains(1, 0, 1, 1, 0)))
                .andExpect(jsonPath("$.questions[1].averageMarks").value(1.0))
                .andExpect(jsonPath("$.questions[4].pendingMarking").value(1));
        api.get("/api/dashboard", ravi.accessToken()).andExpect(jsonPath("$.lessons.answersToMark").value(1));
        String review = LessonsSchool.body(api.get("/api/lessons/tests/" + test + "/attempts/" + attemptId,
                ravi.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.pendingMarking").value(1))
                .andExpect(jsonPath("$.answers[1].marks").value(1.0))
                .andExpect(jsonPath("$.answers[3].autoMarked").value(true))
                .andExpect(jsonPath("$.answers[4].marks").doesNotExist()));
        String shortId = LessonsSchool.read(review, "$.answers[4].answerId");

        // Only the test's teacher marks; not more than the question's marks.
        api.put("/api/lessons/tests/" + test + "/attempts/" + attemptId + "/marks", s.login("sita").accessToken(),
                marks(shortId, "1.5")).andExpect(status().isForbidden());
        api.put("/api/lessons/tests/" + test + "/attempts/" + attemptId + "/marks",
                s.login("principal").accessToken(), marks(shortId, "1.5")).andExpect(status().isForbidden());
        api.put("/api/lessons/tests/" + test + "/attempts/" + attemptId + "/marks", ravi.accessToken(),
                marks(shortId, "3")).andExpect(status().isBadRequest());
        api.put("/api/lessons/tests/" + test + "/attempts/" + attemptId + "/marks", ravi.accessToken(),
                marks(UUID.randomUUID().toString(), "1")).andExpect(status().isBadRequest());
        // Results cannot be released while the test is open.
        api.post("/api/lessons/tests/" + test + "/release", ravi.accessToken(), null)
                .andExpect(status().isConflict());
        api.put("/api/lessons/tests/" + test + "/attempts/" + attemptId + "/marks", ravi.accessToken(),
                marks(shortId, "1.5")).andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(5.5))
                .andExpect(jsonPath("$.pendingMarking").value(0))
                .andExpect(jsonPath("$.answers[4].markedByName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.answers[4].feedback").value("Show an example."));
        api.get("/api/dashboard", ravi.accessToken()).andExpect(jsonPath("$.lessons.answersToMark").value(0));

        // Closed now, then released.
        api.post("/api/lessons/tests/" + test + "/close", ravi.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOSED"));
        api.post("/api/lessons/tests/" + test + "/close", ravi.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/lessons/tests/" + test + "/release", s.login("sita").accessToken(), null)
                .andExpect(status().isForbidden());
        api.post("/api/lessons/tests/" + test + "/release", ravi.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.attempted").value(1))
                .andExpect(jsonPath("$.averagePercent").value(78.6))
                .andExpect(jsonPath("$.students[0].percent").value(78.6));
        api.post("/api/lessons/tests/" + test + "/release", ravi.accessToken(), null)
                .andExpect(status().isConflict());

        // Asha sees her score and, as the teacher chose, the answers with the explanation and feedback.
        api.get("/api/me/attempts/" + attemptId, asha.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.score").value(5.5))
                .andExpect(jsonPath("$.maxScore").value(7.0))
                .andExpect(jsonPath("$.review", hasSize(5)))
                .andExpect(jsonPath("$.review[0].options[*].correct", contains(false, true, false)))
                .andExpect(jsonPath("$.review[2].correctAnswer").value("TRUE"))
                .andExpect(jsonPath("$.review[2].explanation").value("Ten comes after nine."))
                .andExpect(jsonPath("$.review[4].feedback").value("Show an example."));
        api.get("/api/me/study", asha.accessToken())
                .andExpect(jsonPath("$.tests[0].released").value(true))
                .andExpect(jsonPath("$.tests[0].score").value(5.5))
                .andExpect(jsonPath("$.tests[0].reviewAttemptId").value(attemptId));
        // Her mother sees the score, read-only.
        api.get("/api/me/children/" + s.asha + "/study", mother.accessToken())
                .andExpect(jsonPath("$.tests[0].score").value(5.5))
                .andExpect(jsonPath("$.tests[0].maxScore").value(7.0))
                .andExpect(jsonPath("$.tests[0].percent").value(78.6))
                .andExpect(jsonPath("$.tests[0].reviewAttemptId").doesNotExist());
        api.get("/api/me/attempts/" + attemptId, mother.accessToken()).andExpect(status().isNotFound());
        api.post("/api/me/tests/" + test + "/attempts", mother.accessToken(), null)
                .andExpect(status().isNotFound());

        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("online_test.created", "online_test.questions_set",
                        "online_test.published", "online_test.marked", "online_test.closed",
                        "online_test.released", "test_attempt.started", "test_attempt.submitted")));
    }

    @Test
    void theServerKeepsTheClockAndTheNumberOfAttempts() throws Exception {
        String test = draft(ravi, "Two tries", Instant.now().minus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofDays(1)), 10, 2);
        api.put("/api/lessons/tests/" + test + "/questions", ravi.accessToken(), """
                {"questions":[{"questionId":"%s","marks":4},{"questionId":"%s"}]}""".formatted(single, trueFalse))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMarks").value(5.0));
        api.post("/api/lessons/tests/" + test + "/publish", ravi.accessToken(), null).andExpect(status().isOk());
        Session asha = s.login("asha");
        String first = LessonsSchool.body(api.post("/api/me/tests/" + test + "/attempts", asha.accessToken(), null)
                .andExpect(status().isOk()));
        save(asha, first, "What is 2 + 2?", options(first, "What is 2 + 2?", "4")).andExpect(status().isOk());

        // Time runs out (the deadline was a minute ago): the next save is refused and what was saved is submitted.
        String firstId = LessonsSchool.read(first, "$.id");
        moveDeadline(firstId, Duration.ofMinutes(-1));
        save(asha, first, "10 is more than 9.", "{\"text\":\"TRUE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Time is up. Your saved answers were submitted."));
        api.get("/api/me/attempts/" + firstId, asha.accessToken())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.autoSubmitted").value(true))
                .andExpect(jsonPath("$.secondsLeft").value(0));
        api.get("/api/lessons/tests/" + test + "/results", ravi.accessToken())
                .andExpect(jsonPath("$.students[0].score").value(4.0))
                .andExpect(jsonPath("$.students[0].attempts[0].autoSubmitted").value(true));

        // A second attempt is allowed, a third is not.
        api.get("/api/me/tests/" + test, asha.accessToken()).andExpect(jsonPath("$.test.canStart").value(true))
                .andExpect(jsonPath("$.test.attemptsUsed").value(1));
        String second = LessonsSchool.read(LessonsSchool.body(api.post("/api/me/tests/" + test + "/attempts",
                asha.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(2))), "$.id");
        // An attempt that simply runs out is submitted when it is next opened.
        moveDeadline(second, Duration.ofSeconds(-30));
        api.get("/api/me/attempts/" + second, asha.accessToken())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.autoSubmitted").value(true));
        api.post("/api/me/tests/" + test + "/attempts", asha.accessToken(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You have used all 2 attempts."));
        // The best attempt counts.
        api.get("/api/lessons/tests/" + test + "/results", ravi.accessToken())
                .andExpect(jsonPath("$.students[0].score").value(4.0))
                .andExpect(jsonPath("$.students[0].attempts", hasSize(2)));

        // A test that has not opened yet cannot be started; nor one that has closed.
        String later = draft(ravi, "Later", Instant.now().plus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(2)), 10, 1);
        api.put("/api/lessons/tests/" + later + "/questions", ravi.accessToken(), """
                {"questions":[{"questionId":"%s"}]}""".formatted(single)).andExpect(status().isOk());
        api.post("/api/lessons/tests/" + later + "/publish", ravi.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SCHEDULED"));
        api.get("/api/me/tests/" + later, asha.accessToken()).andExpect(jsonPath("$.test.state").value("UPCOMING"))
                .andExpect(jsonPath("$.test.canStart").value(false));
        api.post("/api/me/tests/" + later + "/attempts", asha.accessToken(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This test has not opened yet."));

        // Closing a test ends attempts in progress at the close.
        String closing = draft(ravi, "Closing", Instant.now().minus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofDays(1)), 30, 1);
        api.put("/api/lessons/tests/" + closing + "/questions", ravi.accessToken(), """
                {"questions":[{"questionId":"%s"}]}""".formatted(single)).andExpect(status().isOk());
        api.post("/api/lessons/tests/" + closing + "/publish", ravi.accessToken(), null).andExpect(status().isOk());
        String running = LessonsSchool.read(LessonsSchool.body(api.post("/api/me/tests/" + closing + "/attempts",
                asha.accessToken(), null).andExpect(status().isOk())), "$.id");
        api.post("/api/lessons/tests/" + closing + "/close", ravi.accessToken(), null).andExpect(status().isOk());
        api.get("/api/me/attempts/" + running, asha.accessToken())
                .andExpect(jsonPath("$.secondsLeft").value(0));
        moveClose(closing, Duration.ofMinutes(-1));
        api.get("/api/me/attempts/" + running, asha.accessToken())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.autoSubmitted").value(true));
        api.post("/api/me/tests/" + closing + "/attempts", asha.accessToken(), null)
                .andExpect(status().isConflict());
        // Nothing to mark, closed: the results can be released.
        api.post("/api/lessons/tests/" + closing + "/release", ravi.accessToken(), null).andExpect(status().isOk());
    }

    @Test
    void teachersSetTestsForWhatTheyTeachAndChangeOnlyTheirOwnDrafts() throws Exception {
        Instant opens = Instant.now().minus(Duration.ofHours(1));
        Instant closes = Instant.now().plus(Duration.ofDays(1));
        api.post("/api/lessons/tests", ravi.accessToken(), testJson("Not mine", opens, closes, 10, 1, s.s5B))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.sectionIds").exists());
        api.post("/api/lessons/tests", ravi.accessToken(), testJson("Wrong class", opens, closes, 10, 1, s.s6A))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.sectionIds").exists());
        api.post("/api/lessons/tests", ravi.accessToken(), testJson("Backwards", closes, opens, 10, 1, s.s5A))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.closesAt").exists());
        api.post("/api/lessons/tests", ravi.accessToken(), testJson("Long", opens, closes, 500, 1, s.s5A))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.durationMinutes").exists());
        api.post("/api/lessons/tests", ravi.accessToken(), testJson("Many", opens, closes, 10, 9, s.s5A))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.attemptsAllowed").exists());
        api.post("/api/lessons/tests", s.login("principal").accessToken(),
                testJson("Principal", opens, closes, 10, 1, s.s5A)).andExpect(status().isForbidden());

        String test = draft(ravi, "Draft", opens, closes, 10, 1);
        api.get("/api/lessons/tests/" + test, ravi.accessToken()).andExpect(jsonPath("$.attemptsAllowed").value(1))
                .andExpect(jsonPath("$.showAnswers").value("WITH_RESULTS"))
                .andExpect(jsonPath("$.sections[*].label", contains("Class 5 A")))
                .andExpect(jsonPath("$.canEdit").value(true));
        // Publishing needs questions.
        api.post("/api/lessons/tests/" + test + "/publish", ravi.accessToken(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.questions").exists());
        // Questions of the test's class and subject only, each once.
        String english = TestApi.read(api.post("/api/lessons/questions", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","type":"TRUE_FALSE","difficulty":"EASY","text":"A is a vowel.",
                 "marks":1,"answerText":"T"}""".formatted(s.class5, s.english)).andExpect(status().isCreated()),
                "$.id");
        api.put("/api/lessons/tests/" + test + "/questions", ravi.accessToken(), """
                {"questions":[{"questionId":"%s"}]}""".formatted(english))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.questions").exists());
        api.put("/api/lessons/tests/" + test + "/questions", ravi.accessToken(), """
                {"questions":[{"questionId":"%s"},{"questionId":"%s"}]}""".formatted(single, single))
                .andExpect(status().isBadRequest());
        // Random picks by difficulty, never twice.
        api.post("/api/lessons/tests/" + test + "/questions/random", ravi.accessToken(), """
                {"difficulty":"EASY","count":2}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[*].question.difficulty", contains("EASY", "EASY")));
        api.post("/api/lessons/tests/" + test + "/questions/random", ravi.accessToken(), """
                {"difficulty":"EASY","count":1}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.count").exists());
        api.post("/api/lessons/tests/" + test + "/questions/random", ravi.accessToken(), """
                {"type":"SHORT_ANSWER","count":1}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.questions", hasSize(3)));

        // Sita can see it but not change it.
        Session sita = s.login("sita");
        api.get("/api/lessons/tests/" + test, sita.accessToken()).andExpect(jsonPath("$.canEdit").value(false));
        api.put("/api/lessons/tests/" + test, sita.accessToken(), testJson("Mine now", opens, closes, 10, 1, s.s5A))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.test").exists());
        api.post("/api/lessons/tests/" + test + "/publish", sita.accessToken(), null)
                .andExpect(status().isForbidden());
        api.delete("/api/lessons/tests/" + test, sita.accessToken()).andExpect(status().isForbidden());
        api.get("/api/lessons/tests?mine=true", sita.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        api.get("/api/lessons/tests?mine=true", ravi.accessToken())
                .andExpect(jsonPath("$[*].title", contains("Draft")))
                .andExpect(jsonPath("$[0].questions").value(3))
                .andExpect(jsonPath("$[0].students").value(2));

        // Ravi changes his draft; a stale version is a conflict.
        String version = TestApi.read(api.get("/api/lessons/tests/" + test, ravi.accessToken()), "$.version");
        api.put("/api/lessons/tests/" + test, ravi.accessToken(), testJson("Draft 2", opens, closes, 15, 2, s.s5A)
                .replace("\"attemptsAllowed\"", "\"version\":" + version + ",\"attemptsAllowed\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.durationMinutes").value(15));
        api.put("/api/lessons/tests/" + test, ravi.accessToken(), testJson("Draft 3", opens, closes, 15, 2, s.s5A)
                .replace("\"attemptsAllowed\"", "\"version\":" + version + ",\"attemptsAllowed\""))
                .andExpect(status().isConflict());

        // Published and started: no unpublishing or deleting.
        api.post("/api/lessons/tests/" + test + "/publish", ravi.accessToken(), null).andExpect(status().isOk());
        api.post("/api/lessons/tests/" + test + "/unpublish", ravi.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT"));
        api.post("/api/lessons/tests/" + test + "/publish", ravi.accessToken(), null).andExpect(status().isOk());
        api.post("/api/me/tests/" + test + "/attempts", s.login("asha").accessToken(), null)
                .andExpect(status().isOk());
        api.post("/api/lessons/tests/" + test + "/unpublish", ravi.accessToken(), null)
                .andExpect(status().isConflict());
        api.delete("/api/lessons/tests/" + test, ravi.accessToken()).andExpect(status().isConflict());

        // A draft without attempts can be deleted; one whose close has passed cannot be published.
        String past = draft(ravi, "Past", Instant.now().minus(Duration.ofDays(2)),
                Instant.now().minus(Duration.ofDays(1)), 10, 1);
        api.put("/api/lessons/tests/" + past + "/questions", ravi.accessToken(), """
                {"questions":[{"questionId":"%s"}]}""".formatted(single)).andExpect(status().isOk());
        api.post("/api/lessons/tests/" + past + "/publish", ravi.accessToken(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.closesAt").exists());
        api.post("/api/lessons/tests/" + past + "/release", ravi.accessToken(), null)
                .andExpect(status().isConflict());
        api.delete("/api/lessons/tests/" + past, ravi.accessToken()).andExpect(status().isNoContent());
        api.get("/api/lessons/tests/" + past, ravi.accessToken()).andExpect(status().isNotFound());
        // Families and the accountant have no staff test screens.
        api.get("/api/lessons/tests", s.login("asha").accessToken()).andExpect(status().isForbidden());
        api.get("/api/lessons/tests/" + test + "/results", s.login("mother").accessToken())
                .andExpect(status().isForbidden());
    }

    @Test
    void familiesHearOfANewTestOnlyWhenTheSchoolSwitchesItOn() throws Exception {
        String quiet = published("Quiet test");
        api.get("/api/messages?relatedId=" + quiet, s.admin.accessToken()).andExpect(jsonPath("$.total").value(0));

        api.put("/api/lessons/settings", s.admin.accessToken(), """
                {"testNotificationsEnabled":true}""").andExpect(status().isOk());
        String told = published("Told test");
        // Asha's mother and Bala's mother on the school's channel, and Asha on her own sign-in; not Dev in 5 B.
        String json = LessonsSchool.body(api.get("/api/messages?relatedId=" + told, s.admin.accessToken())
                .andExpect(jsonPath("$.total").value(3)));
        assertThat(json).doesNotContain("Dev Rao").doesNotContain("9876500011");
        String id = LessonsSchool.firstIn(json, "$.items[?(@.channel == 'EMAIL')].id");
        api.get("/api/messages/" + id, s.admin.accessToken())
                .andExpect(jsonPath("$.body", containsString("Told test")))
                .andExpect(jsonPath("$.body", containsString("Mathematics")))
                .andExpect(jsonPath("$.body", not(containsString("{"))));
        // Unpublishing and publishing again does not tell them twice.
        api.post("/api/lessons/tests/" + told + "/unpublish", ravi.accessToken(), null).andExpect(status().isOk());
        api.post("/api/lessons/tests/" + told + "/publish", ravi.accessToken(), null).andExpect(status().isOk());
        api.get("/api/messages?relatedId=" + told, s.admin.accessToken()).andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void anotherSchoolsTestsAreInvisible() throws Exception {
        String test = published("Ours");
        String attempt = LessonsSchool.read(LessonsSchool.body(api.post("/api/me/tests/" + test + "/attempts",
                s.login("asha").accessToken(), null).andExpect(status().isOk())), "$.id");
        LessonsSchool other = new LessonsSchool(api);
        Session otherRavi = other.login("ravi");
        Session otherAsha = other.login("asha");
        api.get("/api/lessons/tests/" + test, otherRavi.accessToken()).andExpect(status().isNotFound());
        api.get("/api/lessons/tests/" + test + "/results", otherRavi.accessToken()).andExpect(status().isNotFound());
        api.get("/api/lessons/tests/" + test + "/attempts/" + attempt, other.admin.accessToken())
                .andExpect(status().isNotFound());
        api.post("/api/lessons/tests/" + test + "/close", other.admin.accessToken(), null)
                .andExpect(status().isNotFound());
        api.delete("/api/lessons/tests/" + test, other.admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/lessons/tests", other.admin.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        api.get("/api/me/tests/" + test, otherAsha.accessToken()).andExpect(status().isNotFound());
        api.post("/api/me/tests/" + test + "/attempts", otherAsha.accessToken(), null)
                .andExpect(status().isNotFound());
        api.get("/api/me/attempts/" + attempt, otherAsha.accessToken()).andExpect(status().isNotFound());
        api.get("/api/me/children/" + s.asha + "/study", other.login("mother").accessToken())
                .andExpect(status().isNotFound());
        // This school's questions and sections in the other school's bodies are field errors.
        String theirs = draft(otherRavi, other, "Theirs", Instant.now(), Instant.now().plus(Duration.ofDays(1)));
        api.put("/api/lessons/tests/" + theirs + "/questions", otherRavi.accessToken(), """
                {"questions":[{"questionId":"%s"}]}""".formatted(single))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.questions").exists());
        api.post("/api/lessons/tests", otherRavi.accessToken(), testJson("Theirs", Instant.now(),
                Instant.now().plus(Duration.ofDays(1)), 10, 1, s.s5A).replace(s.class5, other.class5)
                .replace(s.maths, other.maths))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.sectionIds").exists());
    }

    /** A published test of the five bank questions for 5 A, open now for a day. */
    private String published(String title) throws Exception {
        String test = draft(ravi, title, Instant.now().minus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofDays(1)), 20, 1);
        api.put("/api/lessons/tests/" + test + "/questions", ravi.accessToken(), """
                {"questions":[{"questionId":"%s"},{"questionId":"%s"}]}""".formatted(single, shortAnswer))
                .andExpect(status().isOk());
        api.post("/api/lessons/tests/" + test + "/publish", ravi.accessToken(), null).andExpect(status().isOk());
        return test;
    }

    private String draft(Session teacher, String title, Instant opens, Instant closes, int minutes, int attempts)
            throws Exception {
        return TestApi.read(api.post("/api/lessons/tests", teacher.accessToken(), testJson(title, opens, closes,
                minutes, attempts, s.s5A)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")), "$.id");
    }

    private String draft(Session teacher, LessonsSchool school, String title, Instant opens, Instant closes)
            throws Exception {
        return TestApi.read(api.post("/api/lessons/tests", teacher.accessToken(), """
                {"classId":"%s","subjectId":"%s","sectionIds":["%s"],"title":"%s","opensAt":"%s","closesAt":"%s",
                 "durationMinutes":10}""".formatted(school.class5, school.maths, school.s5A, title, opens, closes))
                .andExpect(status().isCreated()), "$.id");
    }

    private String testJson(String title, Instant opens, Instant closes, int minutes, int attempts,
            String sectionId) {
        return """
                {"classId":"%s","subjectId":"%s","sectionIds":["%s"],"title":"%s","instructions":"Answer all.",
                 "opensAt":"%s","closesAt":"%s","durationMinutes":%d,"attemptsAllowed":%d}"""
                .formatted(s.class5, s.maths, sectionId, title, opens, closes, minutes, attempts);
    }

    /** Saves an answer to the question with this text in the attempt. */
    private ResultActions save(Session student, String attemptJson,
            String questionText, String body) throws Exception {
        String attemptId = LessonsSchool.read(attemptJson, "$.id");
        String answerId = LessonsSchool.firstIn(attemptJson, "$.questions[?(@.text == '" + questionText
                + "')].answerId");
        return api.put("/api/me/attempts/" + attemptId + "/answers/" + answerId, student.accessToken(), body);
    }

    /** {"optionIds":[...]} with the ids of the options with these texts in the question. */
    private static String options(String attemptJson, String questionText, String... texts) {
        StringBuilder ids = new StringBuilder();
        for (String text : texts) {
            String id = LessonsSchool.firstIn(attemptJson, "$.questions[?(@.text == '" + questionText
                    + "')].options[?(@.text == '" + text + "')].id");
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(id).append('"');
        }
        return "{\"optionIds\":[" + ids + "]}";
    }

    private static String marks(String answerId, String marks) {
        return """
                {"marks":[{"answerId":"%s","marks":%s,"feedback":"Show an example."}]}""".formatted(answerId, marks);
    }

    /** Moves an attempt's deadline to now plus the offset (negative for the past), as if time had passed. */
    private static void moveDeadline(String attemptId, Duration offset) throws Exception {
        try (Connection owner = ownerConnection(); PreparedStatement s = owner.prepareStatement(
                "update lessons.test_attempt set deadline_at = now() + make_interval(secs => ?) where id = ?")) {
            s.setDouble(1, offset.toSeconds());
            s.setObject(2, UUID.fromString(attemptId));
            assertThat(s.executeUpdate()).isEqualTo(1);
        }
    }

    /** Moves a test's close to now plus the offset. */
    private static void moveClose(String testId, Duration offset) throws Exception {
        try (Connection owner = ownerConnection(); PreparedStatement s = owner.prepareStatement(
                "update lessons.online_test set closes_at = now() + make_interval(secs => ?) where id = ?")) {
            s.setDouble(1, offset.toSeconds());
            s.setObject(2, UUID.fromString(testId));
            assertThat(s.executeUpdate()).isEqualTo(1);
        }
    }
}
