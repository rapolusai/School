package com.akshara.lessons;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * The question bank: every question type with its checks, search and filters, sharing within the school, the CSV
 * import, locking questions that tests use, and another school's questions.
 */
class QuestionBankIT extends IntegrationTest {

    private LessonsSchool s;

    @BeforeEach
    void school() throws Exception {
        s = new LessonsSchool(api);
    }

    @Test
    void everyTypeIsCheckedAndStored() throws Exception {
        Session ravi = s.login("ravi");
        String topic = LessonsSchool.topic(s.mathsSyllabus(ravi), "Place value");
        String mcq = s.mcq(ravi, "What is 2 + 2?", "1");
        api.get("/api/lessons/questions/" + LessonsSchool.read(mcq, "$.id"), ravi.accessToken())
                .andExpect(jsonPath("$.type").value("MCQ_SINGLE"))
                .andExpect(jsonPath("$.options[*].text", contains("3", "4", "5")))
                .andExpect(jsonPath("$.options[*].correct", contains(false, true, false)))
                .andExpect(jsonPath("$.marks").value(1.0))
                .andExpect(jsonPath("$.shared").value(true))
                .andExpect(jsonPath("$.createdByName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.locked").value(false));
        s.question(ravi, """
                {"classId":"%s","subjectId":"%s","type":"MCQ_MULTI","difficulty":"MEDIUM","text":"Even numbers?",
                 "marks":2,"bloomLevel":"UNDERSTAND","options":[{"text":"2","correct":true},
                 {"text":"3","correct":false},{"text":"4","correct":true}]}""");
        s.question(ravi, """
                {"classId":"%s","subjectId":"%s","type":"TRUE_FALSE","difficulty":"EASY","text":"10 > 9",
                 "marks":1,"answerText":"true","explanation":"Ten is more than nine."}""");
        String blank = s.question(ravi, """
                {"classId":"%s","subjectId":"%s","type":"FILL_BLANK","difficulty":"HARD","text":"100 = ___ tens",
                 "marks":1.5,"answerText":"10\\n ten "}""");
        s.question(ravi, ("""
                {"classId":"%s","subjectId":"%s","type":"SHORT_ANSWER","difficulty":"HARD","text":"Explain place value.",
                 "marks":3,"topicId":"TOPIC","answerText":"Each digit's value depends on its place."}""")
                .replace("TOPIC", topic));
        api.get("/api/lessons/questions/" + blank, ravi.accessToken())
                .andExpect(jsonPath("$.answerText").value("10\nten"))
                .andExpect(jsonPath("$.marks").value(1.5));

        // The checks of each type.
        api.post("/api/lessons/questions", ravi.accessToken(), question("MCQ_SINGLE", """
                "options":[{"text":"A","correct":true},{"text":"B","correct":true}]"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.options").exists());
        api.post("/api/lessons/questions", ravi.accessToken(), question("MCQ_MULTI", """
                "options":[{"text":"A","correct":false},{"text":"B","correct":false}]"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.options").exists());
        api.post("/api/lessons/questions", ravi.accessToken(), question("MCQ_SINGLE", """
                "options":[{"text":"A","correct":true}]"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.options").exists());
        api.post("/api/lessons/questions", ravi.accessToken(), question("MCQ_SINGLE", """
                "options":[{"text":"A","correct":true},{"text":" a ","correct":false}]"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.options").exists());
        api.post("/api/lessons/questions", ravi.accessToken(), question("TRUE_FALSE", """
                "answerText":"perhaps\""""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.answerText").exists());
        api.post("/api/lessons/questions", ravi.accessToken(), question("FILL_BLANK", """
                "answerText":"  \""""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.answerText").exists());
        api.post("/api/lessons/questions", ravi.accessToken(), question("SHORT_ANSWER", """
                "options":[{"text":"A","correct":true},{"text":"B","correct":false}]"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.options").exists());
        api.post("/api/lessons/questions", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","type":"SHORT_ANSWER","difficulty":"EASY","text":"Why?",
                 "marks":0}""".formatted(s.class5, s.maths))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.marks").exists());
        api.post("/api/lessons/questions", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","type":"ESSAY","difficulty":"EASY","text":"Why?","marks":1}"""
                .formatted(s.class5, s.maths)).andExpect(status().isBadRequest());
        // A topic of another subject's syllabus is refused.
        api.post("/api/lessons/questions", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","type":"SHORT_ANSWER","difficulty":"EASY","text":"Why?",
                 "marks":1,"topicId":"%s"}""".formatted(s.class5, s.english, topic))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.topicId").exists());

        // Search and filters.
        api.get("/api/lessons/questions?classId=" + s.class5 + "&subjectId=" + s.maths, ravi.accessToken())
                .andExpect(jsonPath("$", hasSize(5)));
        api.get("/api/lessons/questions?difficulty=HARD", ravi.accessToken())
                .andExpect(jsonPath("$[*].type", containsInAnyOrder("FILL_BLANK", "SHORT_ANSWER")));
        api.get("/api/lessons/questions?type=TRUE_FALSE", ravi.accessToken())
                .andExpect(jsonPath("$[*].text", contains("10 > 9")));
        api.get("/api/lessons/questions?topicId=" + topic, ravi.accessToken())
                .andExpect(jsonPath("$[*].topicTitle", contains("Place value")));
        api.get("/api/lessons/questions?q=PLACE", ravi.accessToken()).andExpect(jsonPath("$", hasSize(1)));
        // Reading the bank needs lessons.manage.
        api.get("/api/lessons/questions", s.login("principal").accessToken()).andExpect(status().isForbidden());
        api.get("/api/lessons/questions", s.login("asha").accessToken()).andExpect(status().isForbidden());
        api.get("/api/audit-events?limit=20", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("question.created")));
    }

    @Test
    void sharedQuestionsAreForEveryTeacherAndOnlyTheAuthorChangesThem() throws Exception {
        Session ravi = s.login("ravi");
        Session sita = s.login("sita");
        String shared = LessonsSchool.read(s.mcq(ravi, "Shared?", "1"), "$.id");
        String own = s.question(ravi, """
                {"classId":"%s","subjectId":"%s","type":"TRUE_FALSE","difficulty":"EASY","text":"Private",
                 "marks":1,"answerText":"F","shared":false}""");
        api.get("/api/lessons/questions", sita.accessToken())
                .andExpect(jsonPath("$[*].id", contains(shared)))
                .andExpect(jsonPath("$[0].canEdit").value(false));
        api.get("/api/lessons/questions/" + own, sita.accessToken()).andExpect(status().isNotFound());
        api.get("/api/lessons/questions?mine=true", ravi.accessToken()).andExpect(jsonPath("$", hasSize(2)));
        // The School Admin sees every question and may change them.
        api.get("/api/lessons/questions", s.admin.accessToken()).andExpect(jsonPath("$", hasSize(2)));

        api.put("/api/lessons/questions/" + shared, sita.accessToken(), question("TRUE_FALSE", """
                "answerText":"T\"""")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.question").exists());
        api.delete("/api/lessons/questions/" + shared, sita.accessToken()).andExpect(status().isForbidden());
        api.put("/api/lessons/questions/" + own, sita.accessToken(), question("TRUE_FALSE", """
                "answerText":"T\"""")).andExpect(status().isNotFound());
        api.put("/api/lessons/questions/" + shared, ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","type":"MCQ_SINGLE","difficulty":"MEDIUM","text":"Shared, changed",
                 "marks":2,"options":[{"text":"Yes","correct":true},{"text":"No","correct":false}]}"""
                .formatted(s.class5, s.maths)).andExpect(status().isOk())
                .andExpect(jsonPath("$.options[*].text", contains("Yes", "No")))
                .andExpect(jsonPath("$.difficulty").value("MEDIUM"));

        // A question in a test cannot be deleted; in a published test it cannot be changed either.
        String test = TestApi.read(api.post("/api/lessons/tests", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","sectionIds":["%s"],"title":"Quiz","opensAt":"%s",
                 "closesAt":"%s","durationMinutes":10}""".formatted(s.class5, s.maths, s.s5A,
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86_400)))
                .andExpect(status().isCreated()), "$.id");
        api.put("/api/lessons/tests/" + test + "/questions", ravi.accessToken(), """
                {"questions":[{"questionId":"%s"}]}""".formatted(shared)).andExpect(status().isOk());
        api.delete("/api/lessons/questions/" + shared, ravi.accessToken()).andExpect(status().isConflict());
        api.post("/api/lessons/tests/" + test + "/publish", ravi.accessToken(), null).andExpect(status().isOk());
        api.get("/api/lessons/questions/" + shared, ravi.accessToken())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.usedInTests").value(1));
        api.put("/api/lessons/questions/" + shared, ravi.accessToken(), question("TRUE_FALSE", """
                "answerText":"T\"""")).andExpect(status().isConflict());
        api.delete("/api/lessons/questions/" + own, ravi.accessToken()).andExpect(status().isNoContent());
        api.get("/api/lessons/questions/" + own, ravi.accessToken()).andExpect(status().isNotFound());
    }

    @Test
    void aCsvImportAddsQuestionsOnlyWhenEveryRowIsRight() throws Exception {
        Session ravi = s.login("ravi");
        s.mathsSyllabus(ravi);
        String csv = """
                class,subject,type,difficulty,marks,question,option_1,option_2,option_3,answer,explanation,topic,bloom
                Class 5,Mathematics,mcq,easy,1,What is 5 + 5?,9,10,11,2,,Addition,remember
                Class 5,Mathematics,several,medium,2,Odd numbers?,1,2,3,1;3,,,
                Class 5,Mathematics,true/false,easy,1,0 is even,,,,yes,,,
                Class 5,Mathematics,fill blank,hard,1,1000 = ___ hundreds,,,,10|ten,,,analyze
                Class 5,Mathematics,short answer,hard,3,Why do we carry?,,,,,Because tens make a hundred.,,
                """;
        api.post("/api/lessons/questions/import", ravi.accessToken(), importJson(csv)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.validRows").value(5))
                .andExpect(jsonPath("$.invalidRows").value(0))
                .andExpect(jsonPath("$.preview[*].type", contains("MCQ_SINGLE", "MCQ_MULTI", "TRUE_FALSE",
                        "FILL_BLANK", "SHORT_ANSWER")));
        api.get("/api/lessons/questions", ravi.accessToken()).andExpect(jsonPath("$", hasSize(0)));

        String bad = """
                class,subject,type,difficulty,marks,question,option_1,option_2,answer
                Class 5,Mathematics,mcq,easy,1,Pick,A,B,7
                Class 5,Mathematics,essay,easy,1,Write,,,
                Class 5,Science,mcq,easy,1,Pick,A,B,1
                Class 5,Mathematics,mcq,impossible,0,Pick,A,B,1
                """;
        api.post("/api/lessons/questions/import?dryRun=false", ravi.accessToken(), importJson(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.invalidRows").value(4))
                .andExpect(jsonPath("$.result.errors[*].column", hasItem("type")))
                .andExpect(jsonPath("$.result.errors[*].column", hasItem("subject")))
                .andExpect(jsonPath("$.result.errors[*].column", hasItem("difficulty")))
                .andExpect(jsonPath("$.result.errors[*].column", hasItem("marks")));
        api.get("/api/lessons/questions", ravi.accessToken()).andExpect(jsonPath("$", hasSize(0)));

        api.post("/api/lessons/questions/import?dryRun=false", ravi.accessToken(), importJson(csv))
                .andExpect(status().isOk()).andExpect(jsonPath("$.committed").value(true));
        api.get("/api/lessons/questions", ravi.accessToken()).andExpect(jsonPath("$", hasSize(5)));
        api.get("/api/lessons/questions?type=MCQ_MULTI", ravi.accessToken())
                .andExpect(jsonPath("$[0].options[*].correct", contains(true, false, true)));
        api.get("/api/lessons/questions?type=MCQ_SINGLE", ravi.accessToken())
                .andExpect(jsonPath("$[0].topicTitle").value("Addition"))
                .andExpect(jsonPath("$[0].bloomLevel").value("REMEMBER"));
        api.get("/api/lessons/questions?type=FILL_BLANK", ravi.accessToken())
                .andExpect(jsonPath("$[0].answerText").value("10\nten"))
                .andExpect(jsonPath("$[0].bloomLevel").value("ANALYSE"));
        api.get("/api/lessons/questions?type=TRUE_FALSE", ravi.accessToken())
                .andExpect(jsonPath("$[0].answerText").value("TRUE"));
        api.get("/api/audit-events?limit=20", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("question.imported")));
    }

    @Test
    void anotherSchoolsQuestionsAreInvisible() throws Exception {
        String id = LessonsSchool.read(s.mcq(s.login("ravi"), "Ours", "1"), "$.id");
        LessonsSchool other = new LessonsSchool(api);
        Session otherRavi = other.login("ravi");
        api.get("/api/lessons/questions/" + id, other.admin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/lessons/questions/" + id, other.admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","type":"TRUE_FALSE","difficulty":"EASY","text":"X","marks":1,
                 "answerText":"T"}""".formatted(other.class5, other.maths)).andExpect(status().isNotFound());
        api.delete("/api/lessons/questions/" + id, other.admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/lessons/questions", otherRavi.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        api.post("/api/lessons/questions", otherRavi.accessToken(), question("TRUE_FALSE", """
                "answerText":"T\"""")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.classId").exists());
    }

    /** A Class 5 Mathematics question of this school with the given type and extra fields. */
    private String question(String type, String extra) {
        return """
                {"classId":"%s","subjectId":"%s","type":"%s","difficulty":"EASY","text":"Question","marks":1,%s}"""
                .formatted(s.class5, s.maths, type, extra);
    }

    private static String importJson(String csv) {
        return "{\"csv\":\"" + csv.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
    }
}
