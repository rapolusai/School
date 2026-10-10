package com.akshara.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * A child's leave (absence notes): parents apply for their own child only, the class teacher of the child's section
 * (or attendance.manage) decides, approved leave pre-fills the register and stops absence alerts, and another school
 * sees nothing.
 */
class ChildLeaveIT extends IntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    AttendanceUsage usage;

    private final LocalDate today = LocalDate.now(INDIA);
    /** The latest school day (Monday to Saturday) up to today, so registers can be marked for it. */
    private final LocalDate day = schoolDayOnOrBefore(today);
    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String class5A;
    private String class5B;
    private String asha;
    private String bala;

    @BeforeEach
    void schoolWithTwoSectionsAndAParent() throws Exception {
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
        student(class5B, "B-1", "Dev", 1, "9876500021");
        String mother = TestApi.read(api.get("/api/students/" + asha, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + asha + "/guardians/" + mother + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("mother"), TestApi.PASSWORD))
                .andExpect(status().isOk());
    }

    @Test
    void aParentAppliesForTheirOwnChildOnly() throws Exception {
        Session parent = login("mother");
        LocalDate next = schoolDayAfter(today);
        api.get(leave(asha), parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.classTeacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.today").value(today.toString()))
                .andExpect(jsonPath("$.earliest").value(today.minusDays(30).toString()))
                .andExpect(jsonPath("$.latest").value(today.plusDays(200).toString()))
                .andExpect(jsonPath("$.canApply").value(true))
                .andExpect(jsonPath("$.requests.length()").value(0));

        api.post(leave(asha), parent.accessToken(), apply(next, next, false, "  Fever  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.fromDate").value(next.toString()))
                .andExpect(jsonPath("$.halfDay").value(false))
                .andExpect(jsonPath("$.schoolDays").value(1))
                .andExpect(jsonPath("$.reason").value("Fever"))
                .andExpect(jsonPath("$.requestedByName").value("Asha's Mother"))
                .andExpect(jsonPath("$.canCancel").value(true))
                .andExpect(jsonPath("$.canDecide").value(false));
        api.get(leave(asha), parent.accessToken())
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].status").value("PENDING"));

        // Another child of the same school, and a child of another school, do not exist for this parent.
        api.get(leave(bala), parent.accessToken()).andExpect(status().isNotFound());
        api.post(leave(bala), parent.accessToken(), apply(next, next, false, "Fever"))
                .andExpect(status().isNotFound());
        School other = api.signup();
        Session adminB = api.login(other);
        fixtures.currentYear(adminB);
        String otherSection = fixtures.section(adminB, fixtures.schoolClass(adminB, "Class 1"), "A", null);
        String otherChild = fixtures.student(adminB, otherSection, "O-1", "Other", "Child", "Other Mother",
                "9876500031");
        api.get(leave(otherChild), parent.accessToken()).andExpect(status().isNotFound());
        api.post(leave(otherChild), parent.accessToken(), apply(next, next, false, "Fever"))
                .andExpect(status().isNotFound());

        // Parents never reach the teachers' endpoints, and staff do not use the parent ones.
        String id = TestApi.read(api.get(leave(asha), parent.accessToken()), "$.requests[0].id");
        api.get("/api/attendance/leave-requests", parent.accessToken()).andExpect(status().isForbidden());
        api.post(decide(id, "approve"), parent.accessToken(), "{}").andExpect(status().isForbidden());
        api.get(leave(asha), login("ravi").accessToken()).andExpect(status().isForbidden());
        // The school admin holds every permission, but Asha is not their child.
        api.get(leave(asha), admin.accessToken()).andExpect(status().isNotFound());
        // A parent is not a student: their own-requests endpoint has no student record behind it.
        api.get("/api/me/leave-requests", parent.accessToken()).andExpect(status().isNotFound());

        // Audited without the reason or the child's name.
        String audit = api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("child_leave.requested")))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> requested = JsonPath.read(audit, "$[?(@.action == 'child_leave.requested')]");
        assertThat(requested).hasSize(1);
        assertThat(requested.getFirst()).containsEntry("entityType", "child_leave").containsEntry("entityId", id);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) requested.getFirst().get("details");
        assertThat(details).containsEntry("studentId", asha).containsEntry("section", "Class 5 A")
                .containsEntry("fromDate", next.toString()).containsEntry("halfDay", false);
        assertThat(details.toString()).doesNotContain("Fever").doesNotContain("Asha");
    }

    @Test
    void applicationsAreValidated() throws Exception {
        Session parent = login("mother");
        LocalDate next = schoolDayAfter(today);
        String path = leave(asha);
        api.post(path, parent.accessToken(), apply(next, next, false, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        api.post(path, parent.accessToken(), apply(next, next, false, "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        api.post(path, parent.accessToken(), """
                {"toDate":"%s","reason":"Fever"}""".formatted(next))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fromDate").exists());
        api.post(path, parent.accessToken(), apply(next.plusDays(2), next, false, "Fever"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.toDate").exists());
        // A half day is one day.
        api.post(path, parent.accessToken(), apply(next, next.plusDays(1), true, "Dentist"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.halfDay").exists());
        // At most 31 days at a time.
        api.post(path, parent.accessToken(), apply(next, next.plusDays(31), false, "Travel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.toDate").exists());
        // Up to 30 days back, and only inside the current year.
        api.post(path, parent.accessToken(), apply(today.minusDays(31), today.minusDays(31), false, "Fever"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fromDate").exists());
        api.post(path, parent.accessToken(), apply(today.plusDays(199), today.plusDays(205), false, "Travel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.toDate").exists());
        // Only Sundays: nothing to excuse.
        LocalDate sunday = today.with(TemporalAdjusters.next(DayOfWeek.SUNDAY));
        api.post(path, parent.accessToken(), apply(sunday, sunday, false, "Wedding"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fromDate").exists());
        // Nothing was saved by any of those.
        api.get(path, parent.accessToken()).andExpect(jsonPath("$.requests.length()").value(0));

        // Overlapping a waiting (or approved) request is refused; after cancelling, the days are free again.
        String first = TestApi.read(api.post(path, parent.accessToken(), apply(next, next.plusDays(2), false,
                "Wedding")).andExpect(status().isCreated()), "$.id");
        api.post(path, parent.accessToken(), apply(next.plusDays(2), next.plusDays(3), false, "Travel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.fromDate").exists());
        api.post(path + "/" + first + "/cancel", parent.accessToken(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledByName").value("Asha's Mother"))
                .andExpect(jsonPath("$.canCancel").value(false));
        api.post(path + "/" + first + "/cancel", parent.accessToken(), "{}").andExpect(status().isConflict());
        api.post(path, parent.accessToken(), apply(next.plusDays(2), next.plusDays(3), false, "Travel"))
                .andExpect(status().isCreated());
        // Cancelling someone else's request, or an unknown one, is 404.
        api.post(leave(bala) + "/" + first + "/cancel", parent.accessToken(), "{}").andExpect(status().isNotFound());
        api.post(path + "/" + UUID.randomUUID() + "/cancel", parent.accessToken(), "{}")
                .andExpect(status().isNotFound());
    }

    @Test
    void theClassTeacherDecidesForTheirOwnSectionOnly() throws Exception {
        Session parent = login("mother");
        Session ravi = login("ravi");
        Session sita = login("sita");
        Session principal = login("principal");
        LocalDate next = schoolDayAfter(today);
        String first = applied(parent, next, next, false, "Fever");
        String second = applied(parent, next.plusDays(7), next.plusDays(8), false, "Wedding");

        api.get("/api/attendance/leave-requests", ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wholeSchool").value(false))
                .andExpect(jsonPath("$.pending[*].id", contains(first, second)))
                .andExpect(jsonPath("$.pending[0].studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.pending[0].rollNo").value(1))
                .andExpect(jsonPath("$.pending[0].reason").value("Fever"))
                .andExpect(jsonPath("$.pending[0].canDecide").value(true))
                .andExpect(jsonPath("$.pending[0].canCancel").value(false))
                .andExpect(jsonPath("$.recent.length()").value(0));
        // Another class teacher sees none of them and cannot decide.
        api.get("/api/attendance/leave-requests", sita.accessToken())
                .andExpect(jsonPath("$.pending.length()").value(0));
        api.post(decide(first, "approve"), sita.accessToken(), "{}").andExpect(status().isForbidden());
        api.post(decide(first, "reject"), sita.accessToken(), """
                {"comment":"No"}""").andExpect(status().isForbidden());
        // No attendance permission at all.
        api.get("/api/attendance/leave-requests", login("accounts").accessToken())
                .andExpect(status().isForbidden());
        api.get("/api/attendance/leave-requests", principal.accessToken())
                .andExpect(jsonPath("$.wholeSchool").value(true))
                .andExpect(jsonPath("$.pending.length()").value(2));

        api.post(decide(first, "approve"), ravi.accessToken(), """
                {"comment":"  Get well soon  "}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedByName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.decisionComment").value("Get well soon"))
                .andExpect(jsonPath("$.canDecide").value(false));
        api.post(decide(first, "reject"), ravi.accessToken(), """
                {"comment":"Changed my mind"}""").andExpect(status().isConflict());
        api.post(decide(first, "approve"), ravi.accessToken(), null).andExpect(status().isConflict());

        // A rejection needs a reason; attendance.manage decides for any section.
        api.post(decide(second, "reject"), principal.accessToken(), """
                {"comment":"  "}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.comment").exists());
        api.post(decide(second, "reject"), principal.accessToken(), """
                {"comment":"Exams that week"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decidedByName").value("Lakshmi Iyer"));
        api.post(decide(UUID.randomUUID().toString(), "approve"), principal.accessToken(), "{}")
                .andExpect(status().isNotFound());

        api.get("/api/attendance/leave-requests", ravi.accessToken())
                .andExpect(jsonPath("$.pending.length()").value(0))
                .andExpect(jsonPath("$.recent[*].id", hasItems(first, second)));

        // The parent sees how each stands, with the teacher's comment.
        api.get(leave(asha), parent.accessToken())
                .andExpect(jsonPath("$.requests[*].id", contains(second, first)))
                .andExpect(jsonPath("$.requests[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.requests[0].decisionComment").value("Exams that week"))
                .andExpect(jsonPath("$.requests[0].canCancel").value(false))
                .andExpect(jsonPath("$.requests[1].status").value("APPROVED"))
                .andExpect(jsonPath("$.requests[1].canCancel").value(true));
        // Approved leave can be withdrawn until it starts.
        api.post(leave(asha) + "/" + first + "/cancel", parent.accessToken(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("child_leave.requested", "child_leave.approved",
                        "child_leave.rejected", "child_leave.cancelled")));
    }

    @Test
    void approvedLeavePreFillsTheRegisterAndStopsAbsenceAlerts() throws Exception {
        Session parent = login("mother");
        Session ravi = login("ravi");
        // Bala's mother applies too, for a half day.
        String balaMother = TestApi.read(api.get("/api/students/" + bala, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + bala + "/guardians/" + balaMother + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("bala-mother"), TestApi.PASSWORD))
                .andExpect(status().isOk());
        String ashaLeave = applied(parent, day, day, false, "Fever");
        String balaLeave = applied(login("bala-mother"), day, day, true, "Dentist");

        // Waiting requests do not pre-fill anything.
        api.get(AttendanceIT.register(class5A, day), ravi.accessToken())
                .andExpect(jsonPath("$.entries[0].approvedLeave").value(nullValue()))
                .andExpect(jsonPath("$.entries[1].approvedLeave").value(nullValue()));
        api.post(decide(ashaLeave, "approve"), ravi.accessToken(), "{}").andExpect(status().isOk());
        api.post(decide(balaLeave, "approve"), ravi.accessToken(), "{}").andExpect(status().isOk());

        api.get(AttendanceIT.register(class5A, day), ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(false))
                .andExpect(jsonPath("$.entries[*].fullName", contains("Asha Rao", "Bala Rao")))
                .andExpect(jsonPath("$.entries[0].status").value(nullValue()))
                .andExpect(jsonPath("$.entries[0].approvedLeave.requestId").value(ashaLeave))
                .andExpect(jsonPath("$.entries[0].approvedLeave.halfDay").value(false))
                .andExpect(jsonPath("$.entries[0].approvedLeave.prefill").value("LEAVE"))
                .andExpect(jsonPath("$.entries[1].approvedLeave.prefill").value("HALF_DAY"));

        // The teacher can still mark someone on leave absent; no alert goes to the parent who wrote the note.
        api.put(AttendanceIT.register(class5A, day), ravi.accessToken(), AttendanceIT.body(Map.of(asha, "ABSENT",
                bala, "HALF_DAY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsQueued").value(0))
                .andExpect(jsonPath("$.register.entries[0].status").value("ABSENT"))
                .andExpect(jsonPath("$.register.entries[0].approvedLeave.prefill").value("LEAVE"));
        api.get("/api/messages?relatedId=" + asha, admin.accessToken()).andExpect(jsonPath("$.total").value(0));

        // The parent's month shows the leave day and today's state.
        api.get("/api/me/children/" + asha + "/attendance?month=" + YearMonth.from(day), parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(today.toString()))
                .andExpect(jsonPath("$.leaveDays[0].date").value(day.toString()))
                .andExpect(jsonPath("$.leaveDays[0].halfDay").value(false))
                .andExpect(jsonPath("$.holidays.length()").value(0));
        if (day.equals(today)) {
            api.get("/api/me/children/" + asha + "/attendance", parent.accessToken())
                    .andExpect(jsonPath("$.todayStatus").value("ABSENT"))
                    .andExpect(jsonPath("$.todayLeave.prefill").value("LEAVE"))
                    .andExpect(jsonPath("$.todayHoliday").value(nullValue()));
        }
    }

    @Test
    void approvingLeaveCancelsAnAlertAlreadyQueued() throws Exception {
        Session parent = login("mother");
        Session ravi = login("ravi");
        // Marked absent first: the alert is queued, then the parent explains and the teacher approves.
        api.put(AttendanceIT.register(class5A, day), ravi.accessToken(), AttendanceIT.body(Map.of(asha, "ABSENT",
                bala, "PRESENT")))
                .andExpect(jsonPath("$.alertsQueued").value(1));
        api.get("/api/messages?relatedId=" + asha, admin.accessToken())
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"));
        String id = applied(parent, day, day, false, "Fever");
        api.post(decide(id, "approve"), ravi.accessToken(), null).andExpect(status().isOk());
        api.get("/api/messages?relatedId=" + asha, admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("SKIPPED"));
        // The register itself is not changed by the approval: the teacher changes the mark if they want.
        api.get(AttendanceIT.register(class5A, day), ravi.accessToken())
                .andExpect(jsonPath("$.entries[0].status").value("ABSENT"))
                .andExpect(jsonPath("$.entries[0].approvedLeave.prefill").value("LEAVE"));
        String audit = api.get("/api/audit-events?limit=20", admin.accessToken()).andReturn().getResponse()
                .getContentAsString();
        List<Integer> cancelled = JsonPath.read(audit,
                "$[?(@.action == 'child_leave.approved')].details.alertsCancelled");
        assertThat(cancelled).containsExactly(1);
        // Approved leave that has started cannot be withdrawn by the parent any more.
        api.post(leave(asha) + "/" + id + "/cancel", parent.accessToken(), "{}").andExpect(status().isConflict());
    }

    @Test
    void aStudentSeesTheirOwnLeaveAndAttendanceReadOnly() throws Exception {
        api.post("/api/students/" + asha + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email("asha"), TestApi.PASSWORD))
                .andExpect(status().isOk());
        String id = applied(login("mother"), day, day, false, "Fever");
        api.post(decide(id, "approve"), login("ravi").accessToken(), "{}").andExpect(status().isOk());
        api.put(AttendanceIT.register(class5A, day), admin.accessToken(), AttendanceIT.body(Map.of(asha, "LEAVE",
                bala, "PRESENT"))).andExpect(status().isOk());

        Session student = login("asha");
        api.get("/api/me/leave-requests", student.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.canApply").value(false))
                .andExpect(jsonPath("$.requests[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.requests[0].canCancel").value(false));
        api.get("/api/me/attendance?month=" + YearMonth.from(day), student.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.daysMarked").value(1))
                .andExpect(jsonPath("$.counts.leave").value(1))
                .andExpect(jsonPath("$.leaveDays[0].date").value(day.toString()));
        api.get("/api/me/attendance?month=May", student.accessToken()).andExpect(status().isBadRequest());
        // A student never applies or reaches anyone else's records.
        api.post(leave(asha), student.accessToken(), apply(day, day, false, "Fever"))
                .andExpect(status().isForbidden());
        api.get("/api/attendance/leave-requests", student.accessToken()).andExpect(status().isForbidden());
        // Staff are not students.
        api.get("/api/me/attendance", login("ravi").accessToken()).andExpect(status().isNotFound());
        api.get("/api/me/leave-requests", login("ravi").accessToken()).andExpect(status().isNotFound());
    }

    @Test
    void anotherSchoolSeesNothing() throws Exception {
        String id = applied(login("mother"), schoolDayAfter(today), schoolDayAfter(today), false, "Fever");
        School other = api.signup();
        Session adminB = api.login(other);
        fixtures.year(adminB, "This year", today.minusDays(10).toString(), today.plusDays(100).toString(), true);

        api.get("/api/attendance/leave-requests", adminB.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending.length()").value(0))
                .andExpect(jsonPath("$.recent.length()").value(0));
        api.post(decide(id, "approve"), adminB.accessToken(), "{}").andExpect(status().isNotFound());
        api.post(decide(id, "reject"), adminB.accessToken(), """
                {"comment":"No"}""").andExpect(status().isNotFound());
        api.get("/api/audit-events?limit=50", adminB.accessToken())
                .andExpect(jsonPath("$[*].action", not(hasItem("child_leave.requested"))));
        // Still waiting, untouched, in its own school.
        api.get("/api/attendance/leave-requests", admin.accessToken())
                .andExpect(jsonPath("$.pending[0].id").value(id))
                .andExpect(jsonPath("$.pending[0].status").value("PENDING"));
    }

    @Test
    void schoolSetupSeesSectionsAndYearsThatHaveLeaveRequests() throws Exception {
        applied(login("mother"), schoolDayAfter(today), schoolDayAfter(today), false, "Fever");
        String yearId = TestApi.read(api.get("/api/academics/years", admin.accessToken()), "$[0].id");
        long sectionUse = TenantContext.runAs(school.tenantId(), () -> usage.sectionUseCount(UUID.fromString(class5A)));
        long otherSectionUse = TenantContext.runAs(school.tenantId(),
                () -> usage.sectionUseCount(UUID.fromString(class5B)));
        long yearUse = TenantContext.runAs(school.tenantId(), () -> usage.yearUseCount(UUID.fromString(yearId)));
        assertThat(sectionUse).isEqualTo(1);
        assertThat(otherSectionUse).isZero();
        assertThat(yearUse).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String applied(Session parent, LocalDate from, LocalDate to, boolean halfDay, String reason)
            throws Exception {
        String studentId = TestApi.read(api.get("/api/me/children", parent.accessToken()), "$[0].id");
        return TestApi.read(api.post(leave(studentId), parent.accessToken(), apply(from, to, halfDay, reason))
                .andExpect(status().isCreated()), "$.id");
    }

    private static String apply(LocalDate from, LocalDate to, boolean halfDay, String reason) {
        return """
                {"fromDate":"%s","toDate":"%s","halfDay":%s,"reason":"%s"}""".formatted(from, to, halfDay, reason);
    }

    private static String leave(String studentId) {
        return "/api/me/children/" + studentId + "/leave-requests";
    }

    private static String decide(String id, String action) {
        return "/api/attendance/leave-requests/" + id + "/" + action;
    }

    static LocalDate schoolDayOnOrBefore(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SUNDAY ? date.minusDays(1) : date;
    }

    static LocalDate schoolDayAfter(LocalDate date) {
        LocalDate next = date.plusDays(1);
        return next.getDayOfWeek() == DayOfWeek.SUNDAY ? next.plusDays(1) : next;
    }

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

    private Session login(String name) throws Exception {
        return api.login(school.code(), email(name), TestApi.PASSWORD);
    }

    private String email(String name) {
        return name + "@" + school.code() + ".akshara.test";
    }
}
