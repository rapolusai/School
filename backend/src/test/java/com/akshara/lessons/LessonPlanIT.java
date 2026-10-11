package com.akshara.lessons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi.Session;

/**
 * Lesson plans and their approval: a teacher plans only what they teach and changes only their own drafts; approvers
 * approve or return with a comment, never their own; taught plans make the syllabus coverage report (with its CSV
 * and the per-topic detail) and the dashboard cards. Another school's plans are invisible.
 */
class LessonPlanIT extends IntegrationTest {

    private LessonsSchool s;
    private String syllabus;

    @BeforeEach
    void school() throws Exception {
        s = new LessonsSchool(api);
        syllabus = s.mathsSyllabus(s.admin);
    }

    @Test
    void aTeacherSubmitsAPlanAndThePrincipalReturnsThenApprovesIt() throws Exception {
        Session ravi = s.login("ravi");
        Session principal = s.login("principal");
        String placeValue = LessonsSchool.topic(syllabus, "Place value");
        String plan = s.plan(ravi, s.s5A, s.maths, placeValue);
        api.get("/api/lessons/plans/" + plan, ravi.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.teacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.topicTitle").value("Place value"))
                .andExpect(jsonPath("$.unitTitle").value("Numbers"))
                .andExpect(jsonPath("$.weekStart").value(s.monday(0).toString()))
                .andExpect(jsonPath("$.resourceLinks", contains("https://ncert.nic.in")))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.canReview").value(false));

        // Only for what he teaches: not Mathematics in 5 B, not English in 5 A.
        api.post("/api/lessons/plans", ravi.accessToken(), planJson(s.s5B, s.maths, null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.sectionId").exists());
        api.post("/api/lessons/plans", ravi.accessToken(), planJson(s.s5A, s.english, null))
                .andExpect(status().isForbidden());
        // Validation: a day outside the week, a topic of another subject, no title and no topic.
        api.post("/api/lessons/plans", ravi.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","title":"X","weekStart":"%s","planDate":"%s","periods":1}"""
                .formatted(s.s5A, s.maths, s.monday(0), s.monday(1).plusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.planDate").exists());
        api.post("/api/lessons/plans", ravi.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","weekStart":"%s","periods":1}"""
                .formatted(s.s5A, s.maths, s.monday(0)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.title").exists());
        api.post("/api/lessons/plans", ravi.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","title":"X","weekStart":"%s","periods":0}"""
                .formatted(s.s5A, s.maths, s.monday(0)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.periods").exists());
        api.post("/api/lessons/plans", ravi.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","title":"X","weekStart":"%s","periods":1,
                 "resourceLinks":"not a link"}""".formatted(s.s5A, s.maths, s.monday(0)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.resourceLinks").exists());
        api.post("/api/lessons/plans", ravi.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","title":"X","weekStart":"%s","periods":1}"""
                .formatted(s.s5A, s.maths, s.today.plusYears(3)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.weekStart").exists());

        // Sita may read the plan but not change it; the principal approves but does not write plans.
        Session sita = s.login("sita");
        api.get("/api/lessons/plans/" + plan, sita.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(false));
        api.put("/api/lessons/plans/" + plan, sita.accessToken(), planJson(s.s5A, s.maths, placeValue))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.plan").exists());
        api.post("/api/lessons/plans/" + plan + "/submit", sita.accessToken(), null)
                .andExpect(status().isForbidden());
        api.post("/api/lessons/plans", principal.accessToken(), planJson(s.s5A, s.maths, null))
                .andExpect(status().isForbidden());

        // A draft this week is waiting to be submitted.
        api.get("/api/dashboard", ravi.accessToken()).andExpect(jsonPath("$.lessons.plansToSubmit").value(1))
                .andExpect(jsonPath("$.lessons.plansToApprove").value(0));
        api.put("/api/lessons/plans/" + plan, ravi.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","topicId":"%s","weekStart":"%s","periods":3,
                 "objectives":"Place value to thousands.","teachingMethod":"Abacus","homeworkIdea":"Write 5 numbers",
                 "assessmentNote":"Oral quiz"}""".formatted(s.s5A, s.maths, placeValue, s.monday(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods").value(3))
                .andExpect(jsonPath("$.title").value("Place value"));
        api.post("/api/lessons/plans/" + plan + "/submit", ravi.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submittedAt").exists());
        // Submitted: no more changes until it comes back.
        api.put("/api/lessons/plans/" + plan, ravi.accessToken(), planJson(s.s5A, s.maths, placeValue))
                .andExpect(status().isConflict());
        api.delete("/api/lessons/plans/" + plan, ravi.accessToken()).andExpect(status().isConflict());
        api.post("/api/lessons/plans/" + plan + "/submit", ravi.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/lessons/plans/" + plan + "/taught", ravi.accessToken(), """
                {"taughtOn":"%s"}""".formatted(s.today)).andExpect(status().isConflict());

        // The approval queue and the dashboard.
        api.get("/api/lessons/plans/queue", principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(plan)))
                .andExpect(jsonPath("$[0].teacherName").value("Ravi Kumar"));
        api.get("/api/lessons/plans/queue", ravi.accessToken()).andExpect(status().isForbidden());
        api.get("/api/dashboard", principal.accessToken()).andExpect(jsonPath("$.lessons.plansToApprove").value(1));
        api.get("/api/dashboard", ravi.accessToken()).andExpect(jsonPath("$.lessons.plansToSubmit").value(0));
        api.get("/api/lessons/plans/" + plan, principal.accessToken())
                .andExpect(jsonPath("$.canReview").value(true));
        api.post("/api/lessons/plans/" + plan + "/approve", ravi.accessToken(), null)
                .andExpect(status().isForbidden());

        // Returned with a comment (required), changed and submitted again, then approved.
        api.post("/api/lessons/plans/" + plan + "/return", principal.accessToken(), "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.comment").exists());
        api.post("/api/lessons/plans/" + plan + "/return", principal.accessToken(), """
                {"comment":"Add an activity with the abacus."}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.reviewComment").value("Add an activity with the abacus."))
                .andExpect(jsonPath("$.reviewedByName").value("Lakshmi Iyer"));
        api.post("/api/lessons/plans/" + plan + "/approve", principal.accessToken(), null)
                .andExpect(status().isConflict());
        api.get("/api/dashboard", ravi.accessToken()).andExpect(jsonPath("$.lessons.plansToSubmit").value(1));
        api.put("/api/lessons/plans/" + plan, ravi.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","topicId":"%s","weekStart":"%s","periods":3,
                 "teachingMethod":"Abacus activity in pairs"}""".formatted(s.s5A, s.maths, placeValue, s.monday(0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RETURNED"));
        api.post("/api/lessons/plans/" + plan + "/submit", ravi.accessToken(), null).andExpect(status().isOk());
        api.post("/api/lessons/plans/" + plan + "/approve", principal.accessToken(), """
                {"comment":"Good."}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.canMarkTaught").value(false));
        api.get("/api/lessons/plans/" + plan, ravi.accessToken()).andExpect(jsonPath("$.canMarkTaught").value(true));
        api.post("/api/lessons/plans/" + plan + "/return", principal.accessToken(), """
                {"comment":"Again"}""").andExpect(status().isConflict());
        api.get("/api/lessons/plans/queue", principal.accessToken()).andExpect(jsonPath("$", hasSize(0)));

        // Taught: not in the future; it can be undone.
        api.post("/api/lessons/plans/" + plan + "/taught", ravi.accessToken(), """
                {"taughtOn":"%s"}""".formatted(s.today.plusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.taughtOn").exists());
        api.post("/api/lessons/plans/" + plan + "/taught", ravi.accessToken(), """
                {"taughtOn":"%s"}""".formatted(s.today)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TAUGHT"))
                .andExpect(jsonPath("$.taughtOn").value(s.today.toString()));
        api.post("/api/lessons/plans/" + plan + "/untaught", ravi.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.taughtOn").doesNotExist());
        api.post("/api/lessons/plans/" + plan + "/untaught", ravi.accessToken(), null)
                .andExpect(status().isConflict());

        // Lists: his own, by status; everyone with lessons.read sees the school's plans.
        api.get("/api/lessons/plans?mine=true", ravi.accessToken())
                .andExpect(jsonPath("$[*].id", contains(plan))).andExpect(jsonPath("$[0].mine").value(true));
        api.get("/api/lessons/plans?status=DRAFT", principal.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        api.get("/api/lessons/plans?sectionId=" + s.s5A + "&status=APPROVED", principal.accessToken())
                .andExpect(jsonPath("$[*].id", contains(plan)));
        api.get("/api/lessons/plans?from=" + s.monday(1), principal.accessToken())
                .andExpect(jsonPath("$", hasSize(0)));
        api.get("/api/lessons/plans", s.login("asha").accessToken()).andExpect(status().isForbidden());
        api.get("/api/lessons/plans", s.login("accounts").accessToken()).andExpect(status().isForbidden());

        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("lesson_plan.created", "lesson_plan.updated",
                        "lesson_plan.submitted", "lesson_plan.returned", "lesson_plan.approved",
                        "lesson_plan.taught", "lesson_plan.untaught")));
    }

    @Test
    void approversNeverReviewTheirOwnPlansAndDraftsCanBeDeleted() throws Exception {
        // The School Admin plans for any section, but someone else approves.
        String plan = s.plan(s.admin, s.s5B, s.maths, null);
        api.post("/api/lessons/plans/" + plan + "/submit", s.admin.accessToken(), null).andExpect(status().isOk());
        api.get("/api/lessons/plans/queue", s.admin.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        api.post("/api/lessons/plans/" + plan + "/approve", s.admin.accessToken(), null)
                .andExpect(status().isForbidden());
        api.post("/api/lessons/plans/" + plan + "/approve", s.login("principal").accessToken(), null)
                .andExpect(status().isOk());

        Session sita = s.login("sita");
        String draft = s.plan(sita, s.s5B, s.maths, null);
        api.delete("/api/lessons/plans/" + draft, s.login("ravi").accessToken()).andExpect(status().isForbidden());
        api.delete("/api/lessons/plans/" + draft, sita.accessToken()).andExpect(status().isNoContent());
        api.get("/api/lessons/plans/" + draft, sita.accessToken()).andExpect(status().isNotFound());
        api.post("/api/lessons/plans/" + draft + "/submit", sita.accessToken(), null)
                .andExpect(status().isNotFound());
    }

    @Test
    void coverageCountsTaughtTopicsAndFlagsSectionsBehindSchedule() throws Exception {
        Session ravi = s.login("ravi");
        Session principal = s.login("principal");
        teach(ravi, principal, s.s5A, LessonsSchool.topic(syllabus, "Place value"));

        // 5 A: one of four topics (25%); Place value and Addition are due by now, Addition is not taught yet.
        // 5 B: nothing taught, two topics overdue.
        api.get("/api/lessons/coverage", principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.yearId").value(s.yearId))
                .andExpect(jsonPath("$.month").value(s.month.toString()))
                .andExpect(jsonPath("$.sections").value(2))
                .andExpect(jsonPath("$.behind").value(2))
                .andExpect(jsonPath("$.averagePercent").value(12.5))
                .andExpect(jsonPath("$.rows[*].sectionLabel", contains("Class 5 A", "Class 5 B")))
                .andExpect(jsonPath("$.rows[0].topics").value(4))
                .andExpect(jsonPath("$.rows[0].taught").value(1))
                .andExpect(jsonPath("$.rows[0].percent").value(25.0))
                .andExpect(jsonPath("$.rows[0].due").value(2))
                .andExpect(jsonPath("$.rows[0].overdue").value(1))
                .andExpect(jsonPath("$.rows[0].expectedPercent").value(50.0))
                .andExpect(jsonPath("$.rows[0].behind").value(true))
                .andExpect(jsonPath("$.rows[0].teacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.rows[0].lastTaughtOn").value(s.today.toString()))
                .andExpect(jsonPath("$.rows[1].percent").value(0.0))
                .andExpect(jsonPath("$.rows[1].overdue").value(2));

        teach(ravi, principal, s.s5A, LessonsSchool.topic(syllabus, "Addition"));
        api.get("/api/lessons/coverage?behindOnly=true", principal.accessToken())
                .andExpect(jsonPath("$.rows[*].sectionLabel", contains("Class 5 B")));
        api.get("/api/lessons/coverage?sectionId=" + s.s5A, ravi.accessToken())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].percent").value(50.0))
                .andExpect(jsonPath("$.rows[0].behind").value(false));
        api.get("/api/lessons/coverage?subjectId=" + s.english, principal.accessToken())
                .andExpect(jsonPath("$.rows", hasSize(0)));

        // Topic by topic for one section.
        api.get("/api/lessons/coverage/detail?sectionId=" + s.s5A + "&subjectId=" + s.maths,
                principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.taught").value(2))
                .andExpect(jsonPath("$.units[*].title", contains("Numbers", "Fractions")))
                .andExpect(jsonPath("$.units[0].topics[*].taught", contains(true, true)))
                .andExpect(jsonPath("$.units[0].topics[0].teacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.units[1].topics[*].taught", contains(false, false)));
        api.get("/api/lessons/coverage/detail?sectionId=" + s.s5B + "&subjectId=" + s.maths,
                principal.accessToken())
                .andExpect(jsonPath("$.units[0].topics[*].overdue", contains(true, true)));
        api.get("/api/lessons/coverage/detail?sectionId=" + s.s5A + "&subjectId=" + s.english,
                principal.accessToken()).andExpect(status().isNotFound());

        // The CSV download: UTF-8 with a byte order mark, one row per section and subject.
        byte[] csv = api.get("/api/lessons/coverage.csv", principal.accessToken()).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(Arrays.copyOf(csv, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertThat(text.lines().toList()).hasSize(3);
        assertThat(text).startsWith("Class,Section,Subject,Teacher,Topics,Taught,Percent covered")
                .contains("Class 5,Class 5 A,Mathematics,Ravi Kumar,4,2,50.0,2,0,No," + s.today)
                .contains("Class 5,Class 5 B,Mathematics,,4,0,0.0,2,2,Yes,");

        // Families and staff without lessons.read have no report.
        api.get("/api/lessons/coverage", s.login("mother").accessToken()).andExpect(status().isForbidden());
        api.get("/api/lessons/coverage.csv", s.login("accounts").accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void anotherSchoolsPlansAreInvisible() throws Exception {
        String plan = s.plan(s.login("ravi"), s.s5A, s.maths, LessonsSchool.topic(syllabus, "Place value"));
        LessonsSchool other = new LessonsSchool(api);
        Session otherRavi = other.login("ravi");
        Session otherPrincipal = other.login("principal");
        api.get("/api/lessons/plans/" + plan, otherRavi.accessToken()).andExpect(status().isNotFound());
        api.put("/api/lessons/plans/" + plan, otherRavi.accessToken(), planJson(other.s5A, other.maths, null))
                .andExpect(status().isNotFound());
        api.post("/api/lessons/plans/" + plan + "/submit", otherRavi.accessToken(), null)
                .andExpect(status().isNotFound());
        api.post("/api/lessons/plans/" + plan + "/approve", otherPrincipal.accessToken(), null)
                .andExpect(status().isNotFound());
        api.delete("/api/lessons/plans/" + plan, otherRavi.accessToken()).andExpect(status().isNotFound());
        api.get("/api/lessons/plans", otherPrincipal.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        api.get("/api/lessons/coverage", otherPrincipal.accessToken()).andExpect(jsonPath("$.rows", hasSize(0)));
        // This school's section, subject or topic in the other school's body is a field error.
        api.post("/api/lessons/plans", otherRavi.accessToken(), planJson(s.s5A, other.maths, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.sectionId").exists());
        api.post("/api/lessons/plans", otherRavi.accessToken(), planJson(other.s5A, other.maths,
                LessonsSchool.topic(syllabus, "Place value")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.topicId").exists());
        api.get("/api/lessons/coverage/detail?sectionId=" + s.s5A + "&subjectId=" + s.maths,
                otherPrincipal.accessToken()).andExpect(status().isNotFound());
    }

    /** Ravi plans the topic for this week, the principal approves, and Ravi marks it taught today. */
    private void teach(Session teacher, Session approver, String sectionId, String topicId) throws Exception {
        String plan = s.plan(teacher, sectionId, s.maths, topicId);
        api.post("/api/lessons/plans/" + plan + "/submit", teacher.accessToken(), null).andExpect(status().isOk());
        api.post("/api/lessons/plans/" + plan + "/approve", approver.accessToken(), null).andExpect(status().isOk());
        api.post("/api/lessons/plans/" + plan + "/taught", teacher.accessToken(), """
                {"taughtOn":"%s"}""".formatted(s.today)).andExpect(status().isOk());
    }

    private String planJson(String sectionId, String subjectId, String topicId) {
        return """
                {"sectionId":"%s","subjectId":"%s","topicId":%s,"title":"Lesson","weekStart":"%s","periods":2}"""
                .formatted(sectionId, subjectId, topicId == null ? "null" : "\"" + topicId + "\"", s.monday(0));
    }
}
