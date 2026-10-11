package com.akshara.lessons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * Syllabi: units and topics with planned periods and target months, who may change which, keeping topics that lesson
 * plans use, copying last year's, the CSV import with its preview and row errors, the settings, and another school's
 * ids.
 */
class SyllabusIT extends IntegrationTest {

    private LessonsSchool s;

    @BeforeEach
    void school() throws Exception {
        s = new LessonsSchool(api);
    }

    @Test
    void aTeacherWritesTheSyllabusOfASubjectTheyTeach() throws Exception {
        Session ravi = s.login("ravi");
        String json = s.mathsSyllabus(ravi);
        assertThat(LessonsSchool.read(json, "$.className")).isEqualTo("Class 5");
        assertThat(LessonsSchool.read(json, "$.subjectName")).isEqualTo("Mathematics");
        assertThat(LessonsSchool.read(json, "$.topics")).isEqualTo("4");
        assertThat(LessonsSchool.read(json, "$.plannedPeriods")).isEqualTo("14");
        assertThat(LessonsSchool.read(json, "$.updatedByName")).isEqualTo("Ravi Kumar");
        assertThat(LessonsSchool.read(json, "$.units[0].topics[0].targetMonth"))
                .isEqualTo(s.month.minusMonths(3).toString());
        assertThat(LessonsSchool.read(json, "$.canEdit")).isEqualTo("true");
        String id = LessonsSchool.read(json, "$.id");

        // Everyone with lessons.read sees it; the list has the counts.
        Session principal = s.login("principal");
        api.get("/api/lessons/syllabi", principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(id)))
                .andExpect(jsonPath("$[0].units").value(2))
                .andExpect(jsonPath("$[0].topics").value(4));
        api.get("/api/lessons/syllabi/" + id, s.login("kavitha").accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(false));
        api.get("/api/lessons/topics?classId=" + s.class5 + "&subjectId=" + s.maths, ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", contains("Place value", "Addition", "Halves", "Decimals")))
                .andExpect(jsonPath("$[0].unitTitle").value("Numbers"));

        // Kavitha teaches English, not Mathematics; Sita teaches Mathematics in 5 B, so she may change it too.
        api.post("/api/lessons/syllabi", s.login("kavitha").accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[]}""".formatted(s.class6, s.maths))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.subjectId").exists());
        api.put("/api/lessons/syllabi/" + id, s.login("kavitha").accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[]}""".formatted(s.class5, s.maths))
                .andExpect(status().isForbidden());
        api.get("/api/lessons/syllabi/" + id, s.login("sita").accessToken())
                .andExpect(jsonPath("$.canEdit").value(true));
        // A second syllabus for the same class and subject is a conflict.
        api.post("/api/lessons/syllabi", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[]}""".formatted(s.class5, s.maths))
                .andExpect(status().isConflict());
        // Families, the accountant: no access.
        api.get("/api/lessons/syllabi", s.login("asha").accessToken()).andExpect(status().isForbidden());
        api.get("/api/lessons/syllabi", s.login("mother").accessToken()).andExpect(status().isForbidden());
        api.get("/api/lessons/syllabi", s.login("accounts").accessToken()).andExpect(status().isForbidden());
        // The principal approves but also may write the syllabus of any class.
        api.post("/api/lessons/syllabi", principal.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[{"title":"Reading","topics":[
                 {"title":"Stories","plannedPeriods":6}]}]}""".formatted(s.class5, s.english))
                .andExpect(status().isCreated());

        api.get("/api/audit-events?limit=20", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("syllabus.created")));
    }

    @Test
    void editingKeepsTopicsByIdAndRefusesToDropOnesWithPlans() throws Exception {
        Session ravi = s.login("ravi");
        String json = s.mathsSyllabus(ravi);
        String id = LessonsSchool.read(json, "$.id");
        String numbers = LessonsSchool.read(json, "$.units[0].id");
        String placeValue = LessonsSchool.topic(json, "Place value");
        String addition = LessonsSchool.topic(json, "Addition");
        String version = LessonsSchool.read(json, "$.version");
        s.plan(ravi, s.s5A, s.maths, placeValue);

        // Dropping the topic a plan uses is refused.
        api.put("/api/lessons/syllabi/" + id, ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","version":%s,"units":[{"id":"%s","title":"Numbers","topics":[
                 {"id":"%s","title":"Addition","plannedPeriods":3}]}]}"""
                .formatted(s.class5, s.maths, version, numbers, addition))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("Place value")));
        // Renaming it, reordering and adding a topic keeps the plan's topic.
        String updated = LessonsSchool.body(api.put("/api/lessons/syllabi/" + id, ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","version":%s,"units":[{"id":"%s","title":"Whole numbers","topics":[
                 {"id":"%s","title":"Adding","plannedPeriods":3},
                 {"id":"%s","title":"Place value to lakhs","plannedPeriods":5},
                 {"title":"Subtraction","plannedPeriods":2,"targetMonth":"%s"}]}]}"""
                .formatted(s.class5, s.maths, version, numbers, addition, placeValue, s.month))
                .andExpect(status().isOk()));
        assertThat(LessonsSchool.read(updated, "$.units[0].title")).isEqualTo("Whole numbers");
        assertThat(LessonsSchool.topic(updated, "Place value to lakhs")).isEqualTo(placeValue);
        assertThat(LessonsSchool.read(updated, "$.units[0].topics[1].hasPlans")).isEqualTo("true");
        assertThat(LessonsSchool.read(updated, "$.topics")).isEqualTo("3");
        // A stale version is a conflict; target months outside the year are refused.
        api.put("/api/lessons/syllabi/" + id, ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","version":%s,"units":[]}""".formatted(s.class5, s.maths, version))
                .andExpect(status().isConflict());
        api.post("/api/lessons/syllabi", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[{"title":"Far","topics":[
                 {"title":"Later","plannedPeriods":1,"targetMonth":"%s"}]}]}"""
                .formatted(s.class5, s.english, s.month.plusYears(2)))
                .andExpect(status().isForbidden());
        api.post("/api/lessons/syllabi", s.admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[{"title":"Far","topics":[
                 {"title":"Later","plannedPeriods":1,"targetMonth":"%s"}]}]}"""
                .formatted(s.class5, s.english, s.month.plusYears(2)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.units").exists());
        // The class and subject cannot change; a subject the class does not have is refused.
        api.put("/api/lessons/syllabi/" + id, ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[]}""".formatted(s.class5, s.english))
                .andExpect(status().isBadRequest());
        api.post("/api/lessons/syllabi", s.admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[]}""".formatted(s.class6, s.english))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.subjectId").exists());
        // A syllabus with lesson plans cannot be deleted; one without can.
        api.delete("/api/lessons/syllabi/" + id, ravi.accessToken()).andExpect(status().isConflict());
        String english = TestApi.read(api.post("/api/lessons/syllabi", s.login("kavitha").accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[]}""".formatted(s.class5, s.english))
                .andExpect(status().isCreated()), "$.id");
        api.delete("/api/lessons/syllabi/" + english, ravi.accessToken()).andExpect(status().isForbidden());
        api.delete("/api/lessons/syllabi/" + english, s.login("kavitha").accessToken())
                .andExpect(status().isNoContent());
        api.get("/api/lessons/syllabi/" + english, ravi.accessToken()).andExpect(status().isNotFound());
    }

    @Test
    void lastYearsSyllabusCopiesWithItsMonthsMovedOn() throws Exception {
        String lastYear = s.month.minusMonths(14).toString();
        api.post("/api/lessons/syllabi", s.admin.accessToken(), """
                {"academicYearId":"%s","classId":"%s","subjectId":"%s","units":[{"title":"Numbers","topics":[
                 {"title":"Place value","plannedPeriods":4,"targetMonth":"%s"},
                 {"title":"Addition","plannedPeriods":3}]}]}"""
                .formatted(s.lastYearId, s.class5, s.maths, lastYear)).andExpect(status().isCreated());
        api.post("/api/lessons/syllabi", s.admin.accessToken(), """
                {"academicYearId":"%s","classId":"%s","subjectId":"%s","units":[]}"""
                .formatted(s.lastYearId, s.class5, s.english)).andExpect(status().isCreated());
        // English already has a syllabus this year, so only Mathematics is copied.
        api.post("/api/lessons/syllabi", s.admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[]}""".formatted(s.class5, s.english))
                .andExpect(status().isCreated());

        // Ravi may copy only what he teaches: English is skipped for him (and exists anyway).
        api.post("/api/lessons/syllabi/copy", s.login("ravi").accessToken(), """
                {"fromYearId":"%s","toYearId":"%s"}""".formatted(s.lastYearId, s.yearId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.copied").value(1))
                .andExpect(jsonPath("$.skipped").value(1));
        String copied = LessonsSchool.first(api.get("/api/lessons/syllabi?yearId=" + s.yearId,
                s.admin.accessToken()), "$[?(@.subjectName == 'Mathematics')].id");
        api.get("/api/lessons/syllabi/" + copied, s.admin.accessToken())
                .andExpect(jsonPath("$.yearId").value(s.yearId))
                .andExpect(jsonPath("$.units[0].topics[*].title", contains("Place value", "Addition")))
                // The start months of the two years are twelve months apart.
                .andExpect(jsonPath("$.units[0].topics[0].targetMonth")
                        .value(s.month.minusMonths(14).plusMonths(monthsBetweenYearStarts()).toString()))
                .andExpect(jsonPath("$.units[0].topics[1].targetMonth").doesNotExist());
        // Copying again copies nothing; the same year is refused.
        api.post("/api/lessons/syllabi/copy", s.admin.accessToken(), """
                {"fromYearId":"%s","toYearId":"%s"}""".formatted(s.lastYearId, s.yearId))
                .andExpect(jsonPath("$.copied").value(0));
        api.post("/api/lessons/syllabi/copy", s.admin.accessToken(), """
                {"fromYearId":"%s","toYearId":"%s"}""".formatted(s.yearId, s.yearId))
                .andExpect(status().isBadRequest());
    }

    private long monthsBetweenYearStarts() {
        return ChronoUnit.MONTHS.between(YearMonth.from(s.today.minusDays(515)),
                YearMonth.from(s.today.minusDays(150)));
    }

    @Test
    void aCsvImportIsPreviewedAndWritesNothingWhenARowIsWrong() throws Exception {
        Session ravi = s.login("ravi");
        String csv = """
                class,subject,unit,topic,periods,target_month,notes
                Class 5,Mathematics,Numbers,Place value,4,%s,first
                Class 5,Mathematics,Numbers,Addition,,
                Class 5,Mathematics,Shapes,Triangles,2,%s
                """.formatted(s.month.minusMonths(1),
                s.month.atDay(1).format(DateTimeFormatter.ofPattern("MMM uuuu", Locale.ENGLISH)));
        api.post("/api/lessons/syllabi/import", ravi.accessToken(), importJson(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.validRows").value(3))
                .andExpect(jsonPath("$.syllabi").value(1))
                .andExpect(jsonPath("$.ignoredColumns", contains("notes")))
                .andExpect(jsonPath("$.preview", hasSize(3)))
                .andExpect(jsonPath("$.preview[1].plannedPeriods").value(1));
        api.get("/api/lessons/syllabi", ravi.accessToken()).andExpect(jsonPath("$", hasSize(0)));

        // Bad rows: a class that does not exist, a subject Ravi does not teach, a bad month. Nothing is written.
        String bad = """
                class,subject,unit,topic,periods,target_month
                Class 9,Mathematics,Numbers,Place value,4,
                Class 5,English,Reading,Stories,2,
                Class 5,Mathematics,Numbers,Addition,500,someday
                """;
        api.post("/api/lessons/syllabi/import?dryRun=false", ravi.accessToken(), importJson(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.committed").value(false))
                .andExpect(jsonPath("$.result.invalidRows").value(3))
                .andExpect(jsonPath("$.result.errors[*].row", hasItem(2)))
                .andExpect(jsonPath("$.result.errors[*].column", hasItem("periods")))
                .andExpect(jsonPath("$.result.errors[*].column", hasItem("target_month")));
        api.get("/api/lessons/syllabi", ravi.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        // A header without the required columns is a plain 400.
        api.post("/api/lessons/syllabi/import", ravi.accessToken(), importJson("unit,topic\nA,B\n"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.csv").exists());

        api.post("/api/lessons/syllabi/import?dryRun=false", ravi.accessToken(), importJson(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true));
        api.get("/api/lessons/syllabi", ravi.accessToken())
                .andExpect(jsonPath("$[0].units").value(2))
                .andExpect(jsonPath("$[0].topics").value(3));
        // Importing the same class and subject again is refused row by row.
        api.post("/api/lessons/syllabi/import", ravi.accessToken(), importJson(csv))
                .andExpect(jsonPath("$.invalidRows").value(3));
        api.get("/api/audit-events?limit=20", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("syllabus.imported")));
    }

    @Test
    void theSchoolSwitchesTestMessagesOnInTheSettings() throws Exception {
        api.get("/api/lessons/settings", s.login("ravi").accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.testNotificationsEnabled").value(false));
        api.put("/api/lessons/settings", s.login("principal").accessToken(), """
                {"testNotificationsEnabled":true}""").andExpect(status().isForbidden());
        api.put("/api/lessons/settings", s.admin.accessToken(), """
                {"testNotificationsEnabled":true}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.testNotificationsEnabled").value(true));
        api.put("/api/lessons/settings", s.admin.accessToken(), "{}").andExpect(status().isBadRequest());
        api.get("/api/lessons/settings", s.login("asha").accessToken()).andExpect(status().isForbidden());

        // The pickers: a teacher's own teaching; an approver has the whole school.
        api.get("/api/lessons/options", s.login("ravi").accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.yearId").value(s.yearId))
                .andExpect(jsonPath("$.wholeSchool").value(false))
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.teaching[*].sectionId", contains(s.s5A)))
                .andExpect(jsonPath("$.classes[*].name", hasItem("Class 5")));
        api.get("/api/lessons/options", s.login("principal").accessToken())
                .andExpect(jsonPath("$.wholeSchool").value(true))
                .andExpect(jsonPath("$.canApprove").value(true))
                .andExpect(jsonPath("$.canManage").value(false));
    }

    @Test
    void anotherSchoolsSyllabusIsInvisible() throws Exception {
        String id = LessonsSchool.read(s.mathsSyllabus(s.admin), "$.id");
        LessonsSchool other = new LessonsSchool(api);
        api.get("/api/lessons/syllabi/" + id, other.admin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/lessons/syllabi/" + id, other.admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[]}""".formatted(other.class5, other.maths))
                .andExpect(status().isNotFound());
        api.delete("/api/lessons/syllabi/" + id, other.admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/lessons/syllabi", other.admin.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        // Ids of this school in the other school's request bodies are field errors.
        api.post("/api/lessons/syllabi", other.admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[]}""".formatted(s.class5, s.maths))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.classId").exists());
        api.post("/api/lessons/syllabi", other.admin.accessToken(), """
                {"academicYearId":"%s","classId":"%s","subjectId":"%s","units":[]}"""
                .formatted(s.yearId, other.class5, other.maths))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.academicYearId").exists());
        api.post("/api/lessons/syllabi/copy", other.admin.accessToken(), """
                {"fromYearId":"%s","toYearId":"%s"}""".formatted(s.lastYearId, other.yearId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.fromYearId").exists());
    }

    private static String importJson(String csv) {
        return "{\"csv\":\"" + csv.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
    }
}
