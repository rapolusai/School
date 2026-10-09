package com.akshara.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.shared.TenantContext;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/** Marking registers, who may mark which section, re-saving, the reports and a parent's view. */
class AttendanceIT extends IntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    AttendanceUsage usage;

    private final LocalDate today = LocalDate.now(INDIA);
    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String class5A;
    private String class5B;
    private String asha;
    private String bala;
    private String chitra;
    private String dev;

    @BeforeEach
    void schoolWithTwoSections() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fixtures.year(admin, "This year", today.minusDays(150).toString(), today.plusDays(200).toString(), true);
        String ravi = api.createUser(admin, "Ravi Kumar", email("ravi"), List.of("TEACHER"));
        String sita = api.createUser(admin, "Sita Devi", email("sita"), List.of("TEACHER"));
        api.createUser(admin, "Lakshmi Iyer", email("principal"), List.of("PRINCIPAL"));
        api.createUser(admin, "Meena Reddy", email("accounts"), List.of("ACCOUNTANT"));
        String class5 = fixtures.schoolClass(admin, "Class 5");
        class5A = sectionWithTeacher(class5, "A", ravi);
        class5B = sectionWithTeacher(class5, "B", sita);
        asha = student(class5A, "A-1", "Asha", 1, "9876500011");
        bala = student(class5A, "A-2", "Bala", 2, "9876500012");
        chitra = student(class5A, "A-3", "Chitra", 3, "9876500013");
        dev = student(class5B, "B-1", "Dev", 1, "9876500021");
    }

    @Test
    void adminsMarkAnySectionAndTeachersOnlyTheirOwn() throws Exception {
        Session ravi = login("ravi");
        Session sita = login("sita");
        Session principal = login("principal");
        Session accounts = login("accounts");

        // A teacher sees only the section they are class teacher of.
        api.get("/api/attendance/sections", ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.canMark").value(true))
                .andExpect(jsonPath("$.sections.length()").value(1))
                .andExpect(jsonPath("$.sections[0].label").value("Class 5 A"))
                .andExpect(jsonPath("$.sections[0].students").value(3))
                .andExpect(jsonPath("$.sections[0].marked").value(false));
        api.get("/api/attendance/sections", principal.accessToken())
                .andExpect(jsonPath("$.sections[*].label", contains("Class 5 A", "Class 5 B")));

        api.get(register(class5A, today), ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(false))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.unmarked").value(3))
                .andExpect(jsonPath("$.entries[*].fullName", contains("Asha Rao", "Bala Rao", "Chitra Rao")));
        api.put(register(class5A, today), ravi.accessToken(), body(Map.of(asha, "PRESENT", bala, "PRESENT",
                chitra, "LATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstSave").value(true))
                .andExpect(jsonPath("$.register.marked").value(true))
                .andExpect(jsonPath("$.register.markedByName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.register.counts.present").value(2))
                .andExpect(jsonPath("$.register.counts.late").value(1))
                .andExpect(jsonPath("$.register.presentPercent").value(100.0));

        // Another teacher's section is off limits, for reading and for marking.
        api.get(register(class5B, today), ravi.accessToken()).andExpect(status().isForbidden());
        api.put(register(class5B, today), ravi.accessToken(), body(Map.of(dev, "PRESENT")))
                .andExpect(status().isForbidden());
        api.put(register(class5A, today), sita.accessToken(), body(Map.of(asha, "ABSENT", bala, "PRESENT",
                chitra, "PRESENT"))).andExpect(status().isForbidden());

        // attendance.manage marks any section.
        api.put(register(class5B, today), principal.accessToken(), body(Map.of(dev, "ABSENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.register.markedByName").value("Lakshmi Iyer"));
        api.put(register(class5A, today), principal.accessToken(), body(Map.of(asha, "PRESENT", bala, "PRESENT",
                chitra, "PRESENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstSave").value(false))
                .andExpect(jsonPath("$.register.markedByName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.register.updatedByName").value("Lakshmi Iyer"));

        // No attendance permission at all.
        api.get("/api/attendance/sections", accounts.accessToken()).andExpect(status().isForbidden());
        api.get(register(class5A, today), accounts.accessToken()).andExpect(status().isForbidden());
        api.put(register(class5A, today), accounts.accessToken(), body(Map.of(asha, "PRESENT")))
                .andExpect(status().isForbidden());
        api.get("/api/attendance/today", accounts.accessToken()).andExpect(status().isForbidden());

        // A teacher reads only their own students' summaries and month registers.
        api.get("/api/attendance/students/" + asha + "/summary", ravi.accessToken()).andExpect(status().isOk());
        api.get("/api/attendance/students/" + dev + "/summary", ravi.accessToken()).andExpect(status().isForbidden());
        api.get("/api/attendance/sections/" + class5B + "/month", ravi.accessToken())
                .andExpect(status().isForbidden());
    }

    @Test
    void registersAreValidated() throws Exception {
        Map<String, String> all = Map.of(asha, "PRESENT", bala, "PRESENT", chitra, "PRESENT");
        api.put(register(class5A, today.plusDays(1)), admin.accessToken(), body(all))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.date").exists());
        api.put(register(class5A, today.minusDays(151)), admin.accessToken(), body(all))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.date").exists());
        api.get(register(class5A, today.plusDays(3)), admin.accessToken())
                .andExpect(status().isBadRequest());
        // Every student needs a mark, only once, and only students of this section.
        api.put(register(class5A, today), admin.accessToken(), body(Map.of(asha, "PRESENT", bala, "PRESENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.entries").exists());
        api.put(register(class5A, today), admin.accessToken(), body(Map.of(asha, "PRESENT", bala, "PRESENT",
                chitra, "PRESENT", dev, "PRESENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.entries").exists());
        api.put(register(class5A, today), admin.accessToken(), """
                {"entries":[{"studentId":"%s","status":"PRESENT"},{"studentId":"%s","status":"ABSENT"},
                 {"studentId":"%s","status":"PRESENT"},{"studentId":"%s","status":"PRESENT"}]}"""
                .formatted(asha, asha, bala, chitra))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.entries").exists());
        api.put(register(class5A, today), admin.accessToken(), """
                {"entries":[{"studentId":"%s","status":"MAYBE"}]}""".formatted(asha))
                .andExpect(status().isBadRequest());
        api.put(register(class5A, today), admin.accessToken(), "{}").andExpect(status().isBadRequest());
        api.put(register("00000000-0000-7000-8000-000000000000", today), admin.accessToken(), body(all))
                .andExpect(status().isNotFound());
        api.get("/api/attendance/sections/" + class5A + "/month?month=2026-13", admin.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.month").exists());
        api.get("/api/attendance/sections?date=" + today.plusDays(1), admin.accessToken())
                .andExpect(status().isBadRequest());
        // Nothing was saved by any of those.
        api.get(register(class5A, today), admin.accessToken()).andExpect(jsonPath("$.marked").value(false));
    }

    @Test
    void savingAgainUpdatesTheMarksAndIsAudited() throws Exception {
        api.put(register(class5A, today), admin.accessToken(), body(Map.of(asha, "ABSENT", bala, "PRESENT",
                chitra, "PRESENT")))
                .andExpect(jsonPath("$.firstSave").value(true))
                .andExpect(jsonPath("$.changed").value(3))
                .andExpect(jsonPath("$.alertsQueued").value(1));
        // The same marks again change nothing and queue nothing.
        api.put(register(class5A, today), admin.accessToken(), body(Map.of(asha, "ABSENT", bala, "PRESENT",
                chitra, "PRESENT")))
                .andExpect(jsonPath("$.firstSave").value(false))
                .andExpect(jsonPath("$.changed").value(0))
                .andExpect(jsonPath("$.alertsQueued").value(0));
        api.put(register(class5A, today), admin.accessToken(), body(Map.of(asha, "ABSENT", bala, "HALF_DAY",
                chitra, "LEAVE")))
                .andExpect(jsonPath("$.changed").value(2))
                .andExpect(jsonPath("$.alertsQueued").value(0))
                .andExpect(jsonPath("$.register.counts.absent").value(1))
                .andExpect(jsonPath("$.register.counts.halfDay").value(1))
                .andExpect(jsonPath("$.register.counts.leave").value(1))
                .andExpect(jsonPath("$.register.entries[?(@.fullName == 'Bala Rao')].status").value("HALF_DAY"));

        // Still one register with one mark per student, and one alert.
        api.get("/api/attendance/sections", admin.accessToken())
                .andExpect(jsonPath("$.sections[0].marked").value(true))
                .andExpect(jsonPath("$.sections[0].counts.absent").value(1))
                .andExpect(jsonPath("$.sections[0].counts.halfDay").value(1));
        api.get("/api/messages?relatedId=" + asha, admin.accessToken()).andExpect(jsonPath("$.total").value(1));

        ResultActions audit = api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("attendance.marked", "attendance.updated")));
        String json = audit.andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> updates = JsonPath.read(json,
                "$[?(@.action == 'attendance.updated')]");
        // Only the save that changed something is recorded, with the counts before and after.
        assertThat(updates).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) updates.getFirst().get("details");
        assertThat(details).containsEntry("section", "Class 5 A").containsEntry("changed", 2);
        assertThat(details.get("before")).isEqualTo(Map.of("present", 2, "absent", 1, "late", 0, "halfDay", 0,
                "leave", 0));
        assertThat(details.get("after")).isEqualTo(Map.of("present", 0, "absent", 1, "late", 0, "halfDay", 1,
                "leave", 1));
    }

    @Test
    void reportsCountHalfDaysAsHalfAndLeaveAsNotPresent() throws Exception {
        YearMonth month = YearMonth.from(today).minusMonths(1);
        String[][] marks = {
            // Asha, Bala, Chitra on days 1 to 4
            {"PRESENT", "PRESENT", "ABSENT"},
            {"ABSENT", "PRESENT", "ABSENT"},
            {"LATE", "PRESENT", "PRESENT"},
            {"HALF_DAY", "LEAVE", "PRESENT"},
        };
        for (int d = 0; d < marks.length; d++) {
            api.put(register(class5A, month.atDay(d + 1)), admin.accessToken(), body(Map.of(asha, marks[d][0],
                    bala, marks[d][1], chitra, marks[d][2]))).andExpect(status().isOk());
        }
        api.get("/api/attendance/sections/" + class5A + "/month?month=" + month, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Class 5 A"))
                .andExpect(jsonPath("$.month").value(month.toString()))
                .andExpect(jsonPath("$.days.length()").value(month.lengthOfMonth()))
                .andExpect(jsonPath("$.daysMarked").value(4))
                .andExpect(jsonPath("$.days[0].marked").value(true))
                .andExpect(jsonPath("$.days[4].marked").value(false))
                // Day 1: 2 of 3 present.
                .andExpect(jsonPath("$.days[0].presentPercent").value(66.7))
                .andExpect(jsonPath("$.days[3].counts.halfDay").value(1))
                .andExpect(jsonPath("$.students[*].fullName", contains("Asha Rao", "Bala Rao", "Chitra Rao")))
                .andExpect(jsonPath("$.students[0].marks[0]").value("P"))
                .andExpect(jsonPath("$.students[0].marks[1]").value("A"))
                .andExpect(jsonPath("$.students[0].marks[2]").value("L"))
                .andExpect(jsonPath("$.students[0].marks[3]").value("H"))
                .andExpect(jsonPath("$.students[0].marks[4]").value(nullValue()))
                .andExpect(jsonPath("$.students[1].marks[3]").value("E"))
                // Asha: present + late count as 1, half day as 0.5, absent 0 -> 2.5 of 4.
                .andExpect(jsonPath("$.students[0].daysMarked").value(4))
                .andExpect(jsonPath("$.students[0].presentPercent").value(62.5))
                // Bala: leave counts as not present -> 3 of 4.
                .andExpect(jsonPath("$.students[1].presentPercent").value(75.0))
                .andExpect(jsonPath("$.students[2].presentPercent").value(50.0))
                .andExpect(jsonPath("$.counts.present").value(6))
                .andExpect(jsonPath("$.counts.absent").value(3))
                // (6 + 1 late + 0.5 half day) of 12.
                .andExpect(jsonPath("$.presentPercent").value(62.5));

        String csv = api.get("/api/attendance/sections/" + class5A + "/month.csv?month=" + month, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("attendance-class-5-a-" + month)))
                .andReturn().getResponse().getContentAsString();
        List<String> lines = csv.lines().toList();
        assertThat(lines.getFirst()).startsWith("Roll no,Admission no,Student,01,02,03,04,05")
                .endsWith("Days marked,Present,Absent,Late,Half day,Leave,Attendance %");
        assertThat(lines.get(1)).startsWith("1,A-1,Asha Rao,P,A,L,H,,").endsWith(",4,1,1,1,1,0,62.5");
        assertThat(lines).hasSize(1 + 3 + 3);
        assertThat(lines.get(4)).startsWith(",,Present (P+L+H),2,1,3,2,,");

        api.get("/api/attendance/students/" + asha + "/summary?from=" + month.atDay(1) + "&to="
                + month.atEndOfMonth(), admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.className").value("Class 5"))
                .andExpect(jsonPath("$.daysMarked").value(4))
                .andExpect(jsonPath("$.presentPercent").value(62.5))
                .andExpect(jsonPath("$.absences", contains(month.atDay(2).toString())))
                .andExpect(jsonPath("$.days.length()").value(4));
        api.get("/api/attendance/students/" + asha + "/summary?from=" + month.atDay(5) + "&to="
                + month.atDay(1), admin.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.to").exists());

        // Today, for the dashboard: one of two sections marked.
        api.put(register(class5A, today), admin.accessToken(), body(Map.of(asha, "PRESENT", bala, "ABSENT",
                chitra, "PRESENT"))).andExpect(status().isOk());
        api.get("/api/attendance/today", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.sectionCount").value(2))
                .andExpect(jsonPath("$.sectionsMarked").value(1))
                .andExpect(jsonPath("$.students").value(4))
                .andExpect(jsonPath("$.counts.absent").value(1))
                .andExpect(jsonPath("$.presentPercent").value(66.7))
                .andExpect(jsonPath("$.classes[0].className").value("Class 5"))
                .andExpect(jsonPath("$.classes[0].sections[1].marked").value(false));
        api.get("/api/attendance/today", login("sita").accessToken())
                .andExpect(jsonPath("$.sectionCount").value(1))
                .andExpect(jsonPath("$.sectionsMarked").value(0));
    }

    @Test
    void parentsSeeOnlyTheirOwnChild() throws Exception {
        String mother = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("mother"), TestApi.PASSWORD))
                .andExpect(status().isOk());
        api.put(register(class5A, today), admin.accessToken(), body(Map.of(asha, "ABSENT", bala, "PRESENT",
                chitra, "PRESENT"))).andExpect(status().isOk());

        Session parent = login("mother");
        YearMonth month = YearMonth.from(today);
        api.get("/api/me/children/" + asha + "/attendance?month=" + month, parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.month").value(month.toString()))
                .andExpect(jsonPath("$.daysMarked").value(1))
                .andExpect(jsonPath("$.counts.absent").value(1))
                .andExpect(jsonPath("$.presentPercent").value(0.0))
                .andExpect(jsonPath("$.recentAbsences[0].date").value(today.toString()));
        api.get("/api/me/children/" + asha + "/attendance", parent.accessToken()).andExpect(status().isOk());
        // Another child of the same school, and a child of another school, do not exist for this parent.
        api.get("/api/me/children/" + bala + "/attendance", parent.accessToken()).andExpect(status().isNotFound());
        School other = api.signup();
        Session adminB = api.login(other);
        fixtures.currentYear(adminB);
        String otherSection = fixtures.section(adminB, fixtures.schoolClass(adminB, "Class 1"), "A", null);
        String otherChild = fixtures.student(adminB, otherSection, "O-1", "Other", "Child", "Other Mother",
                "9876500031");
        api.get("/api/me/children/" + otherChild + "/attendance", parent.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/me/children/" + asha + "/attendance?month=May", parent.accessToken())
                .andExpect(status().isBadRequest());
        // Parents never reach the school-wide endpoints, and staff do not use the parent one.
        api.get("/api/attendance/sections", parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/me/children/" + asha + "/attendance", login("ravi").accessToken())
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherSchoolSeesNothing() throws Exception {
        api.put(register(class5A, today), admin.accessToken(), body(Map.of(asha, "ABSENT", bala, "PRESENT",
                chitra, "PRESENT"))).andExpect(status().isOk());
        School other = api.signup();
        Session adminB = api.login(other);
        fixtures.year(adminB, "This year", today.minusDays(10).toString(), today.plusDays(100).toString(), true);

        api.get("/api/attendance/sections", adminB.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(0));
        api.get(register(class5A, today), adminB.accessToken()).andExpect(status().isNotFound());
        api.put(register(class5A, today), adminB.accessToken(), body(Map.of(asha, "PRESENT", bala, "PRESENT",
                chitra, "PRESENT"))).andExpect(status().isNotFound());
        api.get("/api/attendance/sections/" + class5A + "/month", adminB.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/attendance/students/" + asha + "/summary", adminB.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/attendance/today", adminB.accessToken())
                .andExpect(jsonPath("$.sectionCount").value(0))
                .andExpect(jsonPath("$.counts.absent").value(0));
        api.get("/api/messages", adminB.accessToken()).andExpect(jsonPath("$.total").value(0));
        // The register is still there, untouched, in its own school.
        api.get(register(class5A, today), admin.accessToken())
                .andExpect(jsonPath("$.counts.absent").value(1));
    }

    @Test
    void schoolSetupSeesSectionsAndYearsThatHaveRegisters() throws Exception {
        api.put(register(class5A, today), admin.accessToken(), body(Map.of(asha, "PRESENT", bala, "PRESENT",
                chitra, "PRESENT"))).andExpect(status().isOk());
        String yearId = TestApi.read(api.get("/api/academics/years", admin.accessToken()), "$[0].id");
        long sectionUse = TenantContext.runAs(school.tenantId(),
                () -> usage.sectionUseCount(UUID.fromString(class5A)));
        long otherSectionUse = TenantContext.runAs(school.tenantId(),
                () -> usage.sectionUseCount(UUID.fromString(class5B)));
        long yearUse = TenantContext.runAs(school.tenantId(), () -> usage.yearUseCount(UUID.fromString(yearId)));
        assertThat(sectionUse).isEqualTo(1);
        assertThat(otherSectionUse).isZero();
        assertThat(yearUse).isEqualTo(1);
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].entityType", hasItem("attendance_register")));
    }

    // ------------------------------------------------------------------ helpers

    private String sectionWithTeacher(String classId, String name, String teacherId) throws Exception {
        return TestApi.read(api.post("/api/academics/classes/" + classId + "/sections", admin.accessToken(), """
                {"name":"%s","classTeacherId":"%s"}""".formatted(name, teacherId)).andExpect(status().isCreated()),
                "$.id");
    }

    private String student(String sectionId, String admissionNo, String firstName, int rollNo, String phone)
            throws Exception {
        return TestApi.read(api.post("/api/students", admin.accessToken(), SchoolFixtures.studentJson(sectionId,
                admissionNo, firstName, "Rao", firstName + "'s Mother", phone, rollNo))
                .andExpect(status().isCreated()), "$.id");
    }

    static String register(String sectionId, LocalDate date) {
        return "/api/attendance/registers/" + sectionId + "/" + date;
    }

    static String body(Map<String, String> marks) {
        Map<String, String> ordered = new LinkedHashMap<>(marks);
        return "{\"entries\":[" + ordered.entrySet().stream()
                .map(e -> "{\"studentId\":\"" + e.getKey() + "\",\"status\":\"" + e.getValue() + "\"}")
                .collect(Collectors.joining(",")) + "]}";
    }

    private Session login(String name) throws Exception {
        return api.login(school.code(), email(name), TestApi.PASSWORD);
    }

    private String email(String name) {
        return name + "@" + school.code() + ".akshara.test";
    }
}
