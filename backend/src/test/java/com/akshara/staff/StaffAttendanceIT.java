package com.akshara.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Self check-in and check-out, the admin's daily sheet (audited), approved leave on the sheet, and the reports. */
class StaffAttendanceIT extends IntegrationTest {

    private School school;
    private Session admin;
    private StaffFixtures staff;
    private String science;
    private String ravi;
    private String meena;
    private Session principal;
    private Session raviSession;
    private Session meenaSession;

    @BeforeEach
    void schoolWithStaff() throws Exception {
        school = api.signup();
        admin = api.login(school);
        staff = new StaffFixtures(api, school, admin);
        staff.years();
        science = staff.department("Science");
        staff.staff("Lakshmi Iyer", "principal", "PRINCIPAL", "PR-1", null);
        ravi = staff.staff("Ravi Kumar", "ravi", "TEACHER", "T-1", science);
        meena = staff.staff("Meena Reddy", "meena", "ACCOUNTANT", "A-1", null);
        principal = staff.login("principal");
        raviSession = staff.login("ravi");
        meenaSession = staff.login("meena");
    }

    @Test
    void staffCheckInOnceADayAndCheckOutAfterCheckingIn() throws Exception {
        api.get("/api/staff-attendance/me/today", raviSession.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(staff.today.toString()))
                .andExpect(jsonPath("$.status").value(nullValue()))
                .andExpect(jsonPath("$.canCheckIn").value(true))
                .andExpect(jsonPath("$.canCheckOut").value(false));
        api.post("/api/staff-attendance/me/check-out", raviSession.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/staff-attendance/me/check-in", raviSession.accessToken(), """
                {"note":"%s"}""".formatted("x".repeat(201))).andExpect(status().isBadRequest());
        api.post("/api/staff-attendance/me/check-in", raviSession.accessToken(), """
                {"note":"Bus was late"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRESENT"))
                .andExpect(jsonPath("$.checkInAt").value(notNullValue()))
                .andExpect(jsonPath("$.checkInNote").value("Bus was late"))
                .andExpect(jsonPath("$.canCheckIn").value(false))
                .andExpect(jsonPath("$.canCheckOut").value(true));
        api.post("/api/staff-attendance/me/check-in", raviSession.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/staff-attendance/me/check-out", raviSession.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkOutAt").value(notNullValue()))
                .andExpect(jsonPath("$.canCheckOut").value(false));
        api.post("/api/staff-attendance/me/check-out", raviSession.accessToken(), null)
                .andExpect(status().isConflict());
        // Every staff role checks in; the admin sees it on the sheet as the person's own.
        api.post("/api/staff-attendance/me/check-in", meenaSession.accessToken(), null).andExpect(status().isOk());
        api.get("/api/staff-attendance/days/" + staff.today, admin.accessToken())
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].source", contains("SELF")))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].checkOut", contains(notNullValue())));
        api.get("/api/staff-attendance/today", principal.accessToken())
                .andExpect(jsonPath("$.activeStaff").value(4))
                .andExpect(jsonPath("$.present").value(2))
                .andExpect(jsonPath("$.checkedIn").value(2))
                .andExpect(jsonPath("$.notMarked").value(2));
        api.get("/api/audit-events?limit=200", admin.accessToken()).andExpect(jsonPath("$[*].action",
                hasItems("staff_attendance.checked_in", "staff_attendance.checked_out")));
    }

    @Test
    void theAdminMarksAndCorrectsADayAndEveryChangeIsAudited() throws Exception {
        LocalDate day = staff.lastWorkingDay();
        api.put("/api/staff-attendance/days/" + day, admin.accessToken(), """
                {"entries":[{"userId":"%s","status":"PRESENT","checkIn":"08:30","checkOut":"16:00"},
                            {"userId":"%s","status":"ABSENT"}]}""".formatted(ravi, meena))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(2))
                .andExpect(jsonPath("$.corrected").value(0))
                .andExpect(jsonPath("$.sheet.counts.present").value(1))
                .andExpect(jsonPath("$.sheet.counts.absent").value(1))
                .andExpect(jsonPath("$.sheet.notMarked").value(2));
        api.get("/api/staff-attendance/days/" + day, admin.accessToken())
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].checkIn", contains("08:30")))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].checkOut", contains("16:00")))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].source", contains("ADMIN")));
        assertThat(latestAction()).isEqualTo("staff_attendance.marked");

        // Saving the same again changes nothing and records nothing.
        api.put("/api/staff-attendance/days/" + day, admin.accessToken(), """
                {"entries":[{"userId":"%s","status":"PRESENT","checkIn":"08:30","checkOut":"16:00"}]}"""
                .formatted(ravi))
                .andExpect(jsonPath("$.marked").value(0))
                .andExpect(jsonPath("$.corrected").value(0));
        // A correction keeps what it was and what it became.
        api.put("/api/staff-attendance/days/" + day, admin.accessToken(), """
                {"entries":[{"userId":"%s","status":"HALF_DAY","checkIn":"08:30","checkOut":"12:30"}]}"""
                .formatted(meena))
                .andExpect(jsonPath("$.corrected").value(1));
        api.get("/api/audit-events?limit=1", admin.accessToken())
                .andExpect(jsonPath("$[0].action").value("staff_attendance.corrected"))
                .andExpect(jsonPath("$[0].entityType").value("staff_attendance_day"))
                .andExpect(jsonPath("$[0].entityId").value(day.toString()))
                .andExpect(jsonPath("$[0].details.changes[0].from").value("ABSENT"))
                .andExpect(jsonPath("$[0].details.changes[0].to").value("HALF_DAY 08:30-12:30"));
        api.get("/api/staff-attendance/days/" + day, admin.accessToken())
                .andExpect(jsonPath("$.rows[?(@.name == 'Meena Reddy')].updatedByName", contains("Asha Admin")))
                .andExpect(jsonPath("$.rows[?(@.name == 'Meena Reddy')].editedAt", contains(notNullValue())));

        // What the sheet refuses.
        badEntry(day, """
                {"userId":"%s","status":"ON_LEAVE"}""".formatted(ravi));
        badEntry(day, """
                {"userId":"%s","status":"ABSENT","checkIn":"08:30"}""".formatted(ravi));
        badEntry(day, """
                {"userId":"%s","status":"PRESENT","checkOut":"16:00"}""".formatted(ravi));
        badEntry(day, """
                {"userId":"%s","status":"PRESENT","checkIn":"16:00","checkOut":"08:30"}""".formatted(ravi));
        api.put("/api/staff-attendance/days/" + day, admin.accessToken(), """
                {"entries":[{"userId":"%s","status":"PRESENT","checkIn":"8:3"}]}""".formatted(ravi))
                .andExpect(status().isBadRequest());
        api.put("/api/staff-attendance/days/" + staff.today.plusDays(1), admin.accessToken(), """
                {"entries":[{"userId":"%s","status":"PRESENT"}]}""".formatted(ravi))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.date").exists());
        String parent = api.createUser(admin, "Anitha Sharma", staff.email("anitha"), List.of("PARENT"));
        badEntry(day, """
                {"userId":"%s","status":"PRESENT"}""".formatted(parent));

        // A day the person checked in on: the admin's sheet keeps the exact time when the minute is unchanged.
        api.post("/api/staff-attendance/me/check-in", raviSession.accessToken(), null).andExpect(status().isOk());
        String in = TestApi.read(api.get("/api/staff-attendance/days/" + staff.today, admin.accessToken()),
                "$.rows[?(@.name == 'Ravi Kumar')].checkIn").replaceAll("[\\[\\]\"]", "");
        api.put("/api/staff-attendance/days/" + staff.today, admin.accessToken(), """
                {"entries":[{"userId":"%s","status":"PRESENT","checkIn":"%s"}]}""".formatted(ravi, in))
                .andExpect(jsonPath("$.corrected").value(0));
    }

    @Test
    void approvedLeaveDaysChangeOnlyByCancellingTheLeave() throws Exception {
        Map<String, String> types = staff.standardTypes();
        LocalDate day = staff.pastMonday();
        String request = staff.applied(raviSession, types.get("CL"), day, day, false);
        staff.decide(principal, request, "approve", null).andExpect(status().isOk());
        api.get("/api/staff-attendance/days/" + day, admin.accessToken())
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].status", contains("ON_LEAVE")))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].source", contains("LEAVE")))
                .andExpect(jsonPath("$.counts.onLeave").value(1));
        api.put("/api/staff-attendance/days/" + day, admin.accessToken(), """
                {"entries":[{"userId":"%s","status":"PRESENT","checkIn":"08:30"}]}""".formatted(ravi))
                .andExpect(status().isConflict());
        // ON_LEAVE itself only ever comes from approved leave.
        api.put("/api/staff-attendance/days/" + day, admin.accessToken(), """
                {"entries":[{"userId":"%s","status":"ON_LEAVE"}]}""".formatted(ravi))
                .andExpect(status().isBadRequest());

        // On approved leave today: no check-in.
        if (staff.today.getDayOfWeek() != DayOfWeek.SUNDAY) {
            String today = staff.applied(raviSession, types.get("CL"), staff.today, staff.today, false);
            staff.decide(principal, today, "approve", null).andExpect(status().isOk());
            api.get("/api/staff-attendance/me/today", raviSession.accessToken())
                    .andExpect(jsonPath("$.onLeave").value(true))
                    .andExpect(jsonPath("$.canCheckIn").value(false));
            api.post("/api/staff-attendance/me/check-in", raviSession.accessToken(), null)
                    .andExpect(status().isConflict());
        }
    }

    @Test
    void theMonthReportCountsEachPersonsDaysAndDownloadsAsCsv() throws Exception {
        LocalDate day = staff.lastWorkingDay();
        YearMonth month = YearMonth.from(day);
        api.put("/api/staff-attendance/days/" + day, admin.accessToken(), """
                {"entries":[{"userId":"%s","status":"PRESENT","checkIn":"08:30","checkOut":"16:00"},
                            {"userId":"%s","status":"HALF_DAY","checkIn":"08:30","checkOut":"12:30"}]}"""
                .formatted(ravi, meena)).andExpect(status().isOk());

        int index = day.getDayOfMonth() - 1;
        api.get("/api/staff-attendance/month?month=" + month, principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(month.toString()))
                .andExpect(jsonPath("$.days.length()").value(month.lengthOfMonth()))
                .andExpect(jsonPath("$.staff.length()").value(4))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].marks[" + index + "]", contains("P")))
                .andExpect(jsonPath("$.staff[?(@.name == 'Meena Reddy')].marks[" + index + "]", contains("H")))
                .andExpect(jsonPath("$.staff[?(@.name == 'Meena Reddy')].daysWorked", contains(0.5)))
                .andExpect(jsonPath("$.days[" + index + "].counts.present").value(1))
                .andExpect(jsonPath("$.totals.halfDay").value(1));
        api.get("/api/staff-attendance/month?month=" + month + "&departmentId=" + science, principal.accessToken())
                .andExpect(jsonPath("$.staff[*].name", contains("Ravi Kumar")));
        api.get("/api/staff-attendance/month.csv?month=" + month, principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("staff-attendance-" + month + ".csv")))
                .andExpect(result -> {
                    String csv = result.getResponse().getContentAsString();
                    assertThat(csv).startsWith("Employee code,Name,Department,01");
                    assertThat(csv).contains("T-1,Ravi Kumar,Science");
                    assertThat(csv.lines().count()).isEqualTo(5);
                });
        api.get("/api/staff-attendance/month?month=2026-13", principal.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.month").exists());
        api.get("/api/staff/" + ravi + "/attendance?month=" + month, admin.accessToken())
                .andExpect(jsonPath("$.days.length()").value(month.lengthOfMonth()))
                .andExpect(jsonPath("$.days[" + index + "].status").value("PRESENT"))
                .andExpect(jsonPath("$.counts.present").value(1));
    }

    @Test
    void onlyTheSchoolAdminMarksAndOnlyStaffReadersSeeTheSheet() throws Exception {
        LocalDate day = staff.lastWorkingDay();
        api.get("/api/staff-attendance/days/" + day, principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(false));
        api.put("/api/staff-attendance/days/" + day, principal.accessToken(), """
                {"entries":[{"userId":"%s","status":"PRESENT"}]}""".formatted(ravi))
                .andExpect(status().isForbidden());
        api.get("/api/staff-attendance/days/" + day, raviSession.accessToken()).andExpect(status().isForbidden());
        api.get("/api/staff-attendance/month", meenaSession.accessToken()).andExpect(status().isForbidden());
        api.get("/api/staff-attendance/today", raviSession.accessToken()).andExpect(status().isForbidden());

        // Another school's staff cannot be marked here, nor seen.
        School otherSchool = api.signup();
        Session otherAdmin = api.login(otherSchool);
        StaffFixtures other = new StaffFixtures(api, otherSchool, otherAdmin);
        String kiran = other.staff("Kiran Joshi", "kiran", "TEACHER", "T-1", null);
        badEntry(day, """
                {"userId":"%s","status":"PRESENT"}""".formatted(kiran));
        api.get("/api/staff/" + kiran + "/attendance", admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/staff-attendance/days/" + day, admin.accessToken())
                .andExpect(jsonPath("$.rows[*].name", hasItem("Ravi Kumar")))
                .andExpect(jsonPath("$.rows.length()").value(4));
        api.get("/api/staff-attendance/month?departmentId=" + other.department("Arts"), admin.accessToken())
                .andExpect(status().isBadRequest());
    }

    private void badEntry(LocalDate day, String entry) throws Exception {
        api.put("/api/staff-attendance/days/" + day, admin.accessToken(), """
                {"entries":[%s]}""".formatted(entry))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.entries").exists());
    }

    private String latestAction() throws Exception {
        return TestApi.read(api.get("/api/audit-events?limit=1", admin.accessToken()), "$[0].action");
    }
}
