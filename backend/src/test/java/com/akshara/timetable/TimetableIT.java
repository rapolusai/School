package com.akshara.timetable;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** The bell schedule, teacher assignments, clash checks, teacher views, free teachers and substitutions. */
class TimetableIT extends IntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final LocalDate today = LocalDate.now(INDIA);
    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String ravi;
    private String kavitha;
    private String sita;
    private String anil;
    private String accountant;
    private String maths;
    private String english;
    private String music;
    private String class5;
    private String class5A;
    private String class5B;

    @BeforeEach
    void schoolWithTwoSectionsAndFourTeachers() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fixtures.year(admin, "This year", today.minusDays(150).toString(), today.plusDays(200).toString(), true);
        ravi = api.createUser(admin, "Ravi Kumar", email("ravi"), List.of("TEACHER"));
        kavitha = api.createUser(admin, "Kavitha Menon", email("kavitha"), List.of("TEACHER"));
        sita = api.createUser(admin, "Sita Devi", email("sita"), List.of("TEACHER"));
        anil = api.createUser(admin, "Anil Rao", email("anil"), List.of("TEACHER"));
        accountant = api.createUser(admin, "Meena Reddy", email("accounts"), List.of("ACCOUNTANT"));
        api.createUser(admin, "Anitha Sharma", email("parent"), List.of("PARENT"));
        maths = subject("Mathematics");
        english = subject("English");
        music = subject("Music");
        class5 = fixtures.schoolClass(admin, "Class 5");
        api.put("/api/academics/classes/" + class5 + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s","%s"]}""".formatted(maths, english)).andExpect(status().isOk());
        class5A = fixtures.section(admin, class5, "A", 40);
        class5B = fixtures.section(admin, class5, "B", 40);
        api.put("/api/timetable/bell-schedule", admin.accessToken(), bells("""
                ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY"]""", false, """
                [{"label":"Period 1","startsAt":"08:30","endsAt":"09:10"},
                 {"label":"Period 2","startsAt":"09:10","endsAt":"09:50"},
                 {"label":"Break","startsAt":"09:50","endsAt":"10:05","breakTime":true},
                 {"label":"Period 3","startsAt":"10:05","endsAt":"10:45"},
                 {"label":"Period 4","startsAt":"10:45","endsAt":"11:25"}]""", null)).andExpect(status().isOk());
    }

    @Test
    void theBellScheduleIsCheckedAndReplacedAsAWhole() throws Exception {
        Session teacher = login("ravi");
        Session accounts = login("accounts");
        Session parent = login("parent");
        api.get("/api/timetable/bell-schedule", accounts.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekdayPeriods").value(4))
                .andExpect(jsonPath("$.weekday.length()").value(5))
                .andExpect(jsonPath("$.weekday[2].breakTime").value(true))
                .andExpect(jsonPath("$.weekday[2].number").doesNotExist())
                .andExpect(jsonPath("$.weekday[3].number").value(3))
                .andExpect(jsonPath("$.weekday[3].startsAt").value("10:05"))
                .andExpect(jsonPath("$.workingDays.length()").value(6));
        api.get("/api/timetable/bell-schedule", parent.accessToken()).andExpect(status().isForbidden());
        api.put("/api/timetable/bell-schedule", teacher.accessToken(), bells(WEEKDAYS, false, TWO_PERIODS, null))
                .andExpect(status().isForbidden());

        // Overlapping periods, a period ending before it starts, bad times, only breaks.
        api.put("/api/timetable/bell-schedule", admin.accessToken(), bells(WEEKDAYS, false, """
                [{"label":"Period 1","startsAt":"08:30","endsAt":"09:15"},
                 {"label":"Period 2","startsAt":"09:10","endsAt":"09:50"}]""", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['weekday[1].startsAt']", containsString("before Period 1 ends")));
        api.put("/api/timetable/bell-schedule", admin.accessToken(), bells(WEEKDAYS, false, """
                [{"label":"Period 1","startsAt":"09:30","endsAt":"09:10"}]""", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['weekday[0].endsAt']").exists());
        api.put("/api/timetable/bell-schedule", admin.accessToken(), bells(WEEKDAYS, false, """
                [{"label":"Period 1","startsAt":"8.30","endsAt":"09:10"}]""", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['weekday[0].startsAt']").exists());
        api.put("/api/timetable/bell-schedule", admin.accessToken(), bells(WEEKDAYS, false, """
                [{"label":"Lunch","startsAt":"12:00","endsAt":"12:40","breakTime":true}]""", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.weekday").exists());
        api.put("/api/timetable/bell-schedule", admin.accessToken(), bells("[]", false, TWO_PERIODS, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.workingDays").exists());
        // A Saturday schedule needs Saturday as a working day.
        api.put("/api/timetable/bell-schedule", admin.accessToken(), bells(WEEKDAYS, true, TWO_PERIODS, TWO_PERIODS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.saturdaySchedule").exists());

        // A separate, shorter Saturday.
        api.put("/api/timetable/bell-schedule", admin.accessToken(), bells("""
                ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY"]""", true, FOUR_PERIODS, """
                [{"label":"Period 1","startsAt":"08:30","endsAt":"09:10"},
                 {"label":"Period 2","startsAt":"09:10","endsAt":"09:50"}]"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saturdaySchedule").value(true))
                .andExpect(jsonPath("$.weekdayPeriods").value(4))
                .andExpect(jsonPath("$.saturdayPeriods").value(2));

        // A timetable that uses Saturday period 2 keeps the schedule from dropping it.
        assign(class5A, maths, ravi, 4);
        saveSection(class5A, cell("SATURDAY", 2, maths)).andExpect(status().isOk());
        saveSection(class5A, cell("SATURDAY", 3, maths))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['SATURDAY-3']", containsString("no period 3")));
        api.put("/api/timetable/bell-schedule", admin.accessToken(), bells("""
                ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY"]""", true, FOUR_PERIODS, """
                [{"label":"Period 1","startsAt":"08:30","endsAt":"09:10"}]"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.saturday").exists());
        api.put("/api/timetable/bell-schedule", admin.accessToken(), bells(WEEKDAYS, false, FOUR_PERIODS, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("Class 5 A has Mathematics in period 2 on Saturday")))
                .andExpect(jsonPath("$.errors.workingDays").exists());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("bell_schedule.updated", "teacher_assignment.created",
                        "timetable.section_saved")));
    }

    @Test
    void clashesAreRefusedOnSaveAndReportedWhenAssignmentsChange() throws Exception {
        Session teacher = login("ravi");
        String maths5A = assign(class5A, maths, ravi, 2);
        assign(class5A, english, kavitha, 3);
        String maths5B = assign(class5B, maths, sita, 2);
        String english5B = assign(class5B, english, kavitha, 3);

        // Only timetable.manage assigns; the subject must be one the class studies; the teacher must teach here.
        api.post("/api/timetable/assignments", teacher.accessToken(), assignment(class5A, music, ravi, 2))
                .andExpect(status().isForbidden());
        api.post("/api/timetable/assignments", admin.accessToken(), assignment(class5A, music, ravi, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.subjectId").exists());
        api.post("/api/timetable/assignments", admin.accessToken(), assignment(class5A, english, accountant, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.teacherId").exists());
        api.post("/api/timetable/assignments", admin.accessToken(), assignment(class5A, maths, sita, 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.subjectId").exists());
        api.get("/api/timetable/assignments?sectionId=" + class5A, teacher.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].subjectName", contains("English", "Mathematics")))
                .andExpect(jsonPath("$[1].teacherName").value("Ravi Kumar"));

        // Class 5 A: Mathematics three times against an allowance of two is only a warning.
        saveSection(class5A, cell("MONDAY", 1, maths), cell("MONDAY", 2, english), cell("TUESDAY", 1, maths),
                cell("TUESDAY", 2, maths))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(4))
                .andExpect(jsonPath("$.slots[0].teacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0].subjectName").value("Mathematics"))
                .andExpect(jsonPath("$.warnings[0].scheduled").value(3))
                .andExpect(jsonPath("$.warnings[0].periodsPerWeek").value(2))
                .andExpect(jsonPath("$.clashes.length()").value(0));

        // Class 5 B: Kavitha Menon is already teaching 5 A in Monday period 2 (teacher double-booked).
        saveSection(class5B, cell("MONDAY", 2, english))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors['MONDAY-2']", containsString("Kavitha Menon is teaching Class 5 A")));
        // Two subjects in one period of one section (section double-booked).
        saveSection(class5B, cell("MONDAY", 1, maths), cell("MONDAY", 1, english))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['MONDAY-1']", containsString("already has a subject")));
        saveSection(class5B, cell("MONDAY", 5, maths))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['MONDAY-5']").exists());
        saveSection(class5B, cell("SUNDAY", 1, maths))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['SUNDAY-1']", containsString("closed")));
        saveSection(class5B, cell("MONDAY", 3, music))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['MONDAY-3']").exists());
        saveSection(class5B, cell("MONDAY", 1, maths), cell("MONDAY", 3, english))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clashes.length()").value(0))
                .andExpect(jsonPath("$.canEdit").value(true));
        // Teachers can look but not save.
        api.get("/api/timetable/sections/" + class5B, teacher.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.busy.length()").value(0));
        api.put("/api/timetable/sections/" + class5B, teacher.accessToken(), "{\"slots\":[]}")
                .andExpect(status().isForbidden());

        // Giving 5 B's Mathematics to Ravi Kumar moves its period to him, which clashes with 5 A on Monday 1.
        api.put("/api/timetable/assignments/" + maths5B, admin.accessToken(), """
                {"teacherId":"%s","periodsPerWeek":2}""".formatted(ravi))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodsMoved").value(1))
                .andExpect(jsonPath("$.clashes.length()").value(1))
                .andExpect(jsonPath("$.clashes[0].teacherName").value("Ravi Kumar"));
        api.get("/api/timetable/clashes", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clashes.length()").value(1))
                .andExpect(jsonPath("$.clashes[0].kind").value("TEACHER"))
                .andExpect(jsonPath("$.clashes[0].day").value("MONDAY"))
                .andExpect(jsonPath("$.clashes[0].period").value(1))
                .andExpect(jsonPath("$.clashes[0].entries[*].sectionLabel", contains("Class 5 A", "Class 5 B")))
                .andExpect(jsonPath("$.warnings.length()").value(1));
        api.get("/api/timetable/clashes", teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/timetable/sections", admin.accessToken())
                .andExpect(jsonPath("$.clashes").value(1))
                .andExpect(jsonPath("$.sections[0].clashes").value(1))
                .andExpect(jsonPath("$.sections[0].cells").value(24));
        api.get("/api/timetable/sections/" + class5B, admin.accessToken())
                .andExpect(jsonPath("$.clashes.length()").value(1))
                .andExpect(jsonPath("$.busy[*].sectionLabel", hasItem("Class 5 A")));

        // Moving 5 B's Mathematics to period 4 fixes it.
        saveSection(class5B, cell("MONDAY", 4, maths), cell("MONDAY", 3, english)).andExpect(status().isOk());
        api.get("/api/timetable/clashes", admin.accessToken()).andExpect(jsonPath("$.clashes.length()").value(0));

        // An assignment with periods in the timetable cannot be deleted until they are removed.
        api.delete("/api/timetable/assignments/" + english5B, admin.accessToken())
                .andExpect(status().isConflict());
        saveSection(class5B, cell("MONDAY", 4, maths)).andExpect(status().isOk());
        api.delete("/api/timetable/assignments/" + english5B, admin.accessToken())
                .andExpect(status().isNoContent());
        api.put("/api/timetable/assignments/" + maths5A, admin.accessToken(), """
                {"teacherId":"%s","periodsPerWeek":61}""".formatted(ravi)).andExpect(status().isBadRequest());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("teacher_assignment.updated",
                        "teacher_assignment.deleted")));
    }

    @Test
    void teachersSeeTheirTimetableAndWhoIsFree() throws Exception {
        assign(class5A, maths, ravi, 5);
        assign(class5A, english, kavitha, 5);
        assign(class5B, maths, sita, 5);
        assign(class5B, english, kavitha, 5);
        saveSection(class5A, cell("MONDAY", 1, maths), cell("MONDAY", 2, english), cell("TUESDAY", 1, maths))
                .andExpect(status().isOk());
        saveSection(class5B, cell("MONDAY", 1, english), cell("MONDAY", 3, maths)).andExpect(status().isOk());

        Session teacher = login("ravi");
        api.get("/api/timetable/me", teacher.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.periodsPerWeek").value(5))
                .andExpect(jsonPath("$.slots.length()").value(2))
                .andExpect(jsonPath("$.slots[0].day").value("MONDAY"))
                .andExpect(jsonPath("$.slots[0].sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.slots[1].day").value("TUESDAY"));
        api.get("/api/timetable/teachers/" + kavitha, teacher.accessToken())
                .andExpect(jsonPath("$.slots[*].sectionLabel", contains("Class 5 B", "Class 5 A")));
        api.get("/api/timetable/teachers", admin.accessToken())
                .andExpect(jsonPath("$[?(@.name == 'Kavitha Menon')].scheduled", contains(2)))
                .andExpect(jsonPath("$[?(@.name == 'Kavitha Menon')].periodsPerWeek", contains(10)));

        // Monday period 1: Ravi (5 A) and Kavitha (5 B) are busy; Sita teaches Mathematics, so she comes first.
        api.get("/api/timetable/free-teachers?day=MONDAY&period=1&subjectId=" + maths, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teachers[*].name", contains("Sita Devi", "Anil Rao")))
                .andExpect(jsonPath("$.teachers[0].teachesSubject").value(true))
                .andExpect(jsonPath("$.teachers[0].periodsThatDay").value(1));
        // Without a subject, the least busy first.
        api.get("/api/timetable/free-teachers?day=MONDAY&period=1", teacher.accessToken())
                .andExpect(jsonPath("$.teachers[*].name", contains("Anil Rao", "Sita Devi")));
        api.get("/api/timetable/free-teachers?day=SUNDAY&period=1", admin.accessToken())
                .andExpect(status().isBadRequest());
        api.get("/api/timetable/free-teachers?day=MONDAY&period=9", admin.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.period").exists());

        // A teacher's day: a weekday with classes, and a Sunday with none.
        LocalDate monday = nextMonday();
        api.get("/api/timetable/me/today?date=" + monday, teacher.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDay").value(true))
                .andExpect(jsonPath("$.periods.length()").value(5))
                .andExpect(jsonPath("$.periods[0].entries[0].kind").value("CLASS"))
                .andExpect(jsonPath("$.periods[0].entries[0].sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.periods[1].entries.length()").value(0));
        api.get("/api/timetable/me/today?date=" + monday.minusDays(1), teacher.accessToken())
                .andExpect(jsonPath("$.workingDay").value(false))
                .andExpect(jsonPath("$.periods.length()").value(0));
    }

    @Test
    void absentTeachersArePairedWithFreeSubstitutes() throws Exception {
        assign(class5A, maths, ravi, 5);
        assign(class5A, english, kavitha, 5);
        assign(class5B, maths, sita, 5);
        assign(class5B, english, kavitha, 5);
        saveSection(class5A, cell("MONDAY", 1, maths), cell("MONDAY", 2, english), cell("MONDAY", 4, maths))
                .andExpect(status().isOk());
        saveSection(class5B, cell("MONDAY", 1, english), cell("MONDAY", 3, maths)).andExpect(status().isOk());
        LocalDate monday = nextMonday();
        Session sitaSession = login("sita");

        String absence = TestApi.read(api.post("/api/timetable/substitutions/absences", admin.accessToken(), """
                {"date":"%s","teacherId":"%s","reason":"Medical leave"}""".formatted(monday, ravi))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.absences.length()").value(1))
                .andExpect(jsonPath("$.absences[0].teacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.absences[0].periods[*].period", contains(1, 4)))
                .andExpect(jsonPath("$.absences[0].periods[0].sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.absences[0].periods[0].suggestions[*].name", contains("Sita Devi",
                        "Anil Rao")))
                .andExpect(jsonPath("$.periodsToCover").value(2))
                .andExpect(jsonPath("$.periodsCovered").value(0)), "$.absences[0].id");
        api.post("/api/timetable/substitutions/absences", admin.accessToken(), """
                {"date":"%s","teacherId":"%s"}""".formatted(monday, ravi)).andExpect(status().isConflict());
        api.post("/api/timetable/substitutions/absences", sitaSession.accessToken(), """
                {"date":"%s","teacherId":"%s"}""".formatted(monday, anil)).andExpect(status().isForbidden());

        // Kavitha is teaching 5 B in period 1; Ravi is the absent one; period 2 is not Ravi's.
        api.put("/api/timetable/substitutions", admin.accessToken(), substitution(monday, class5A, 1, kavitha))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.teacherId", containsString("teaching Class 5 B")));
        api.put("/api/timetable/substitutions", admin.accessToken(), substitution(monday, class5A, 1, ravi))
                .andExpect(status().isBadRequest());
        api.put("/api/timetable/substitutions", admin.accessToken(), substitution(monday, class5A, 2, anil))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.period").exists());
        api.put("/api/timetable/substitutions", sitaSession.accessToken(), substitution(monday, class5A, 1, sita))
                .andExpect(status().isForbidden());

        api.put("/api/timetable/substitutions", admin.accessToken(), substitution(monday, class5A, 1, sita))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodsCovered").value(1))
                .andExpect(jsonPath("$.absences[0].periods[0].substitute.teacherName").value("Sita Devi"))
                // Sita now covers period 1, so she is no longer suggested there.
                .andExpect(jsonPath("$.absences[0].periods[0].suggestions[*].name", not(hasItem("Sita Devi"))));

        // The substitute sees the class in her day; the absent teacher sees who covers his.
        api.get("/api/timetable/me/today?date=" + monday, sitaSession.accessToken())
                .andExpect(jsonPath("$.periods[0].entries[0].kind").value("SUBSTITUTION"))
                .andExpect(jsonPath("$.periods[0].entries[0].sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.periods[0].entries[0].absentTeacherName").value("Ravi Kumar"));
        api.get("/api/timetable/me/today?date=" + monday, login("ravi").accessToken())
                .andExpect(jsonPath("$.absent").value(true))
                .andExpect(jsonPath("$.periods[0].entries[0].kind").value("COVERED"))
                .andExpect(jsonPath("$.periods[0].entries[0].substituteName").value("Sita Devi"));
        api.get("/api/timetable/free-teachers?date=" + monday + "&period=1", admin.accessToken())
                .andExpect(jsonPath("$.teachers[*].name", contains("Anil Rao")));
        // Anyone on the staff can read the day's sheet.
        api.get("/api/timetable/substitutions?date=" + monday, sitaSession.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodsCovered").value(1));

        // Another substitute replaces the first; a substitute who is then marked away loses the cover.
        api.put("/api/timetable/substitutions", admin.accessToken(), substitution(monday, class5A, 1, anil))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absences[0].periods[0].substitute.teacherName").value("Anil Rao"));
        api.post("/api/timetable/substitutions/absences", admin.accessToken(), """
                {"date":"%s","teacherId":"%s"}""".formatted(monday, anil))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodsCovered").value(0));
        api.delete("/api/timetable/substitutions/absences/" + absence, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absences[*].teacherName", contains("Anil Rao")));
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("teacher_absence.recorded", "substitution.assigned",
                        "teacher_absence.removed")));
    }

    @Test
    void studentsAndParentsSeeTheirOwnSectionOnly() throws Exception {
        assign(class5A, maths, ravi, 5);
        saveSection(class5A, cell("MONDAY", 1, maths), cell("WEDNESDAY", 2, maths)).andExpect(status().isOk());
        String asha = fixtures.student(admin, class5A, "A-1", "Asha", "Rao", "Lata Rao", "9876500011");
        String dev = fixtures.student(admin, class5B, "B-1", "Dev", "Rao", "Uma Rao", "9876500021");
        String mother = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("mother"), TestApi.PASSWORD))
                .andExpect(status().isOk());
        api.post("/api/students/" + asha + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("asha"), TestApi.PASSWORD))
                .andExpect(status().isOk());

        api.get("/api/me/timetable", login("asha").accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.slots[*].day", contains("MONDAY", "WEDNESDAY")))
                .andExpect(jsonPath("$.bells.weekdayPeriods").value(4));
        Session parent = login("mother");
        api.get("/api/me/children/" + asha + "/timetable", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0].subjectName").value("Mathematics"));
        api.get("/api/me/children/" + dev + "/timetable", parent.accessToken()).andExpect(status().isNotFound());
        api.get("/api/timetable/sections/" + class5A, parent.accessToken()).andExpect(status().isForbidden());
        // Staff without a student record have no "my timetable" as a student.
        api.get("/api/me/timetable", admin.accessToken()).andExpect(status().isNotFound());
    }

    @Test
    void anotherSchoolsTimetableIsNotFound() throws Exception {
        String assignment = assign(class5A, maths, ravi, 5);
        saveSection(class5A, cell("MONDAY", 1, maths)).andExpect(status().isOk());
        String absence = TestApi.read(api.post("/api/timetable/substitutions/absences", admin.accessToken(), """
                {"date":"%s","teacherId":"%s"}""".formatted(nextMonday(), ravi)), "$.absences[0].id");

        School other = api.signup();
        Session otherAdmin = api.login(other);
        new SchoolFixtures(api).year(otherAdmin, "This year", today.minusDays(150).toString(),
                today.plusDays(200).toString(), true);
        String otherTeacher = api.createUser(otherAdmin, "Other Teacher", "t@" + other.code() + ".akshara.test",
                List.of("TEACHER"));
        api.get("/api/timetable/sections/" + class5A, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/timetable/sections/" + class5A, otherAdmin.accessToken(), "{\"slots\":[]}")
                .andExpect(status().isNotFound());
        api.get("/api/timetable/teachers/" + ravi, otherAdmin.accessToken()).andExpect(status().isNotFound());
        api.put("/api/timetable/assignments/" + assignment, otherAdmin.accessToken(), """
                {"teacherId":"%s","periodsPerWeek":2}""".formatted(otherTeacher)).andExpect(status().isNotFound());
        api.delete("/api/timetable/assignments/" + assignment, otherAdmin.accessToken())
                .andExpect(status().isNotFound());
        api.delete("/api/timetable/substitutions/absences/" + absence, otherAdmin.accessToken())
                .andExpect(status().isNotFound());
        // Another school's ids in a request body are refused as bad input.
        api.post("/api/timetable/assignments", otherAdmin.accessToken(), assignment(class5A, maths, otherTeacher, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sectionId").exists());
        api.post("/api/timetable/substitutions/absences", otherAdmin.accessToken(), """
                {"date":"%s","teacherId":"%s"}""".formatted(nextMonday(), ravi))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.teacherId").exists());
        api.get("/api/timetable/sections", otherAdmin.accessToken())
                .andExpect(jsonPath("$.sections.length()").value(0));
        api.get("/api/timetable/bell-schedule", otherAdmin.accessToken())
                .andExpect(jsonPath("$.weekday.length()").value(0))
                .andExpect(jsonPath("$.workingDays.length()").value(6));
    }

    // ------------------------------------------------------------------ helpers

    static final String WEEKDAYS = """
            ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"]""";
    static final String TWO_PERIODS = """
            [{"label":"Period 1","startsAt":"08:30","endsAt":"09:10"},
             {"label":"Period 2","startsAt":"09:10","endsAt":"09:50"}]""";
    static final String FOUR_PERIODS = """
            [{"label":"Period 1","startsAt":"08:30","endsAt":"09:10"},
             {"label":"Period 2","startsAt":"09:10","endsAt":"09:50"},
             {"label":"Break","startsAt":"09:50","endsAt":"10:05","breakTime":true},
             {"label":"Period 3","startsAt":"10:05","endsAt":"10:45"},
             {"label":"Period 4","startsAt":"10:45","endsAt":"11:25"}]""";

    static String bells(String days, boolean saturday, String weekday, String saturdayRows) {
        return """
                {"workingDays":%s,"saturdaySchedule":%s,"weekday":%s,"saturday":%s}"""
                .formatted(days, saturday, weekday, saturdayRows == null ? "null" : saturdayRows);
    }

    private String subject(String name) throws Exception {
        return TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"%s"}""".formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    static String assignment(String sectionId, String subjectId, String teacherId, int periods) {
        return """
                {"sectionId":"%s","subjectId":"%s","teacherId":"%s","periodsPerWeek":%d}"""
                .formatted(sectionId, subjectId, teacherId, periods);
    }

    private String assign(String sectionId, String subjectId, String teacherId, int periods) throws Exception {
        return TestApi.read(api.post("/api/timetable/assignments", admin.accessToken(),
                assignment(sectionId, subjectId, teacherId, periods)).andExpect(status().isCreated()), "$.id");
    }

    static String cell(String day, int period, String subjectId) {
        return """
                {"day":"%s","period":%d,"subjectId":"%s"}""".formatted(day, period, subjectId);
    }

    private org.springframework.test.web.servlet.ResultActions saveSection(String sectionId, String... cells)
            throws Exception {
        return api.put("/api/timetable/sections/" + sectionId, admin.accessToken(),
                "{\"slots\":[" + List.of(cells).stream().collect(Collectors.joining(",")) + "]}");
    }

    static String substitution(LocalDate date, String sectionId, int period, String teacherId) {
        return """
                {"date":"%s","sectionId":"%s","period":%d,"teacherId":"%s"}"""
                .formatted(date, sectionId, period, teacherId);
    }

    private LocalDate nextMonday() {
        return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    private Session login(String name) throws Exception {
        return api.login(school.code(), email(name), TestApi.PASSWORD);
    }

    private String email(String name) {
        return name + "@" + school.code() + ".akshara.test";
    }
}
