package com.akshara.reports;

import static com.akshara.reports.ReportsSchool.marks;
import static com.akshara.reports.ReportsSchool.schoolDayBefore;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.FeeFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/** The staff dashboard: its numbers, the permission behind each card, and other schools. */
class DashboardIT extends IntegrationTest {

    private ReportsSchool s;

    @BeforeEach
    void school() throws Exception {
        s = new ReportsSchool(api);
    }

    @Test
    void thePrincipalSeesEveryCardWithTheSchoolsNumbers() throws Exception {
        s.threeDaysOfAttendance();
        FeeFixtures fees = new FeeFixtures(api, mvc);
        fees.publishedStructure(s.admin, s.yearId, s.class5, fees.defaultHeads(s.admin));
        fees.cash(s.admin, s.asha, 5_000_00);
        fees.cash(s.admin, s.bala, 2_000_00);

        api.post("/api/admissions/applications", s.admin.accessToken(), """
                {"firstName":"Aarav","dateOfBirth":"2015-01-15","classId":"%s","academicYearId":"%s",
                 "source":"WALK_IN","followUpOn":"%s",
                 "guardians":[{"name":"Priya Mehta","relation":"MOTHER","phone":"9700012345"}]}"""
                .formatted(s.class5, s.yearId, s.today)).andExpect(status().isCreated());
        String bhavya = s.application("Bhavya", s.class5, "APPLICATION", "REFERRAL");
        String charan = s.application("Charan", s.class6, "APPLICATION", "WEBSITE");
        s.move(charan, "ASSESSMENT");
        String diya = s.application("Diya", s.class6, "APPLICATION", "PHONE");
        s.move(diya, "OFFERED");
        String eshan = s.application("Eshan", s.class5, null, "WALK_IN");
        s.move(eshan, "APPLICATION");
        s.move(eshan, "REJECTED");
        followUpOn(bhavya, s.today.minusDays(2));

        String types = api.post("/api/leave/types/standard", s.admin.accessToken(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> sl = JsonPath.read(types, "$[?(@.code == 'SL')].id");
        Session sita = s.login("sita");
        api.post("/api/leave/requests", sita.accessToken(), """
                {"leaveTypeId":"%s","fromDate":"%s","toDate":"%s","halfDay":false,"reason":"Fever"}"""
                .formatted(sl.getFirst(), s.monday(0), s.monday(0))).andExpect(status().isCreated());

        Session ravi = s.login("ravi");
        String circular = TestApi.read(api.post("/api/notices", ravi.accessToken(), """
                {"title":"Class 5 A picnic","body":"Dear parents,\\nPlease note.","category":"GENERAL",
                 "audience":{"classIds":[],"sectionIds":["%s"],"roles":["PARENT"]},"channels":[],"scheduledAt":null}"""
                .formatted(s.s5A)).andExpect(status().isCreated()), "$.id");
        api.post("/api/notices/" + circular + "/submit", ravi.accessToken(), "").andExpect(status().isOk());

        String fractions = s.homework(ravi, List.of(s.s5A), s.maths, "Fractions", s.today.plusDays(2), true);
        s.studentSignIn(s.bala, "bala");
        submit(fractions, s.login("bala"));

        Session principal = s.login("principal");
        ResultActions dashboard = api.get("/api/dashboard", principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(s.today.toString()))
                .andExpect(jsonPath("$.academicYearName").value("This year"))
                // Students: five on roll; Chitra was admitted today.
                .andExpect(jsonPath("$.students.onRoll").value(5))
                .andExpect(jsonPath("$.students.admittedThisMonth").value(1))
                .andExpect(jsonPath("$.students.admittedThisYear").value(5))
                // Attendance today: 5 A and 6 A marked out of three sections.
                .andExpect(jsonPath("$.attendance.wholeSchool").value(true))
                .andExpect(jsonPath("$.attendance.sectionCount").value(3))
                .andExpect(jsonPath("$.attendance.sectionsMarked").value(2))
                .andExpect(jsonPath("$.attendance.students").value(5))
                .andExpect(jsonPath("$.attendance.counts.present").value(1))
                .andExpect(jsonPath("$.attendance.counts.absent").value(1))
                .andExpect(jsonPath("$.attendance.counts.late").value(1))
                .andExpect(jsonPath("$.attendance.counts.leave").value(1))
                .andExpect(jsonPath("$.attendance.presentPercent").value(50.0))
                .andExpect(jsonPath("$.attendance.ownSections", empty()))
                .andExpect(jsonPath("$.attendance.trend", hasSize(30)))
                // Fees: two cash receipts today.
                .andExpect(jsonPath("$.fees.today.amountPaise").value(7_000_00))
                .andExpect(jsonPath("$.fees.today.receiptCount").value(2))
                .andExpect(jsonPath("$.fees.thisMonth.amountPaise").value(7_000_00))
                .andExpect(jsonPath("$.fees.outstandingPaise").value(4 * 46_000_00 - 7_000_00))
                .andExpect(jsonPath("$.fees.overduePaise").value(4 * 23_000_00 - 7_000_00))
                .andExpect(jsonPath("$.fees.overdueStudents").value(4))
                .andExpect(jsonPath("$.fees.byDay", hasSize(s.today.getDayOfMonth())))
                .andExpect(jsonPath("$.fees.byDay[-1].date").value(s.today.toString()))
                .andExpect(jsonPath("$.fees.byDay[-1].amountPaise").value(7_000_00))
                .andExpect(jsonPath("$.fees.byDay[-1].receiptCount").value(2))
                // Admissions: one enquiry, two in progress, one offer; the funnel counts furthest stages.
                .andExpect(jsonPath("$.admissions.openEnquiries").value(1))
                .andExpect(jsonPath("$.admissions.inProgress").value(2))
                .andExpect(jsonPath("$.admissions.offersPending").value(1))
                .andExpect(jsonPath("$.admissions.funnel[*].stage",
                        contains("ENQUIRY", "APPLICATION", "ASSESSMENT", "OFFERED", "ADMITTED")))
                .andExpect(jsonPath("$.admissions.funnel[*].reached", contains(5, 4, 2, 1, 0)))
                .andExpect(jsonPath("$.admissions.funnel[*].current", contains(1, 1, 1, 1, 0)))
                .andExpect(jsonPath("$.admissions.funnel[1].fromPrevious").value(80.0))
                .andExpect(jsonPath("$.admissions.funnel[2].fromEnquiry").value(40.0))
                .andExpect(jsonPath("$.admissions.followUps.dueToday").value(1))
                .andExpect(jsonPath("$.admissions.followUps.overdue").value(1))
                .andExpect(jsonPath("$.admissions.followUps.items[*].childName", contains("Bhavya", "Aarav")))
                // Staff, leave, circulars, homework and timetable.
                .andExpect(jsonPath("$.staff.activeStaff").value(notNullValue()))
                .andExpect(jsonPath("$.leave.waitingForMe").value(1))
                .andExpect(jsonPath("$.circulars.pendingApproval").value(1))
                .andExpect(jsonPath("$.circulars.items[0].title").value("Class 5 A picnic"))
                .andExpect(jsonPath("$.circulars.items[0].createdByName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.homework.waitingForReview").value(1))
                .andExpect(jsonPath("$.timetable.clashes").value(0))
                .andExpect(jsonPath("$.timetable.periodsToCover").value(0))
                .andExpect(jsonPath("$.myDay.substitutions", empty()));

        // The figures are the modules' own: the same as their screens show.
        String staffToday = TestApi.read(api.get("/api/staff-attendance/today", principal.accessToken()),
                "$.activeStaff");
        dashboard.andExpect(jsonPath("$.staff.activeStaff").value(Integer.parseInt(staffToday)));
        String feeOverview = api.get("/api/fees/reports/overview", principal.accessToken())
                .andReturn().getResponse().getContentAsString();
        dashboard.andExpect(jsonPath("$.fees.outstandingPaise")
                .value(((Number) JsonPath.read(feeOverview, "$.outstandingPaise")).intValue()));

        // The trend: the last two school days before today, and today unless it is a Sunday.
        LocalDate d1 = schoolDayBefore(s.today);
        LocalDate d2 = schoolDayBefore(d1);
        dashboard.andExpect(jsonPath("$.attendance.trend[?(@.date == '" + d1 + "')].sectionsMarked", contains(3)))
                .andExpect(jsonPath("$.attendance.trend[?(@.date == '" + d1 + "')].presentPercent", contains(50.0)))
                .andExpect(jsonPath("$.attendance.trend[?(@.date == '" + d2 + "')].presentPercent", contains(100.0)));
        if (s.today.getDayOfWeek() != DayOfWeek.SUNDAY) {
            dashboard.andExpect(jsonPath("$.attendance.trend[-1].date").value(s.today.toString()))
                    .andExpect(jsonPath("$.attendance.trend[-1].presentPercent").value(50.0));
        }
        // This month: the three days that fall in it.
        int present = 1;
        int absent = 1;
        int halfDay = 0;
        if (d1.getMonth() == s.today.getMonth()) {
            present += 2;
            absent += 2;
            halfDay += 1;
        }
        if (d2.getMonth() == s.today.getMonth()) {
            present += 5;
        }
        dashboard.andExpect(jsonPath("$.attendance.month.from").value(s.today.withDayOfMonth(1).toString()))
                .andExpect(jsonPath("$.attendance.month.to").value(s.today.toString()))
                .andExpect(jsonPath("$.attendance.month.counts.present").value(present))
                .andExpect(jsonPath("$.attendance.month.counts.absent").value(absent))
                .andExpect(jsonPath("$.attendance.month.counts.halfDay").value(halfDay));

        // Ravi, class teacher of 5 A, sees his own section, his homework to review and no school-wide cards.
        api.get("/api/dashboard", ravi.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.attendance.wholeSchool").value(false))
                .andExpect(jsonPath("$.attendance.sectionCount").value(1))
                .andExpect(jsonPath("$.attendance.students").value(3))
                .andExpect(jsonPath("$.attendance.counts.absent").value(1))
                .andExpect(jsonPath("$.attendance.ownSections[*].label", contains("Class 5 A")))
                .andExpect(jsonPath("$.attendance.ownSections[0].marked").value(true))
                .andExpect(jsonPath("$.homework.waitingForReview").value(1))
                .andExpect(jsonPath("$.leave.waitingForMe").value(0))
                .andExpect(jsonPath("$.myDay.substitutions", empty()));
        // Sita's 5 B is not marked yet today, and Bala's work is not hers to review.
        api.get("/api/dashboard", sita.accessToken())
                .andExpect(jsonPath("$.attendance.ownSections[0].label").value("Class 5 B"))
                .andExpect(jsonPath("$.attendance.ownSections[0].marked").value(false))
                .andExpect(jsonPath("$.attendance.sectionsMarked").value(0))
                .andExpect(jsonPath("$.homework.waitingForReview").value(0));
    }

    @Test
    void eachCardIsLeftOutWithoutThePermissionBehindIt() throws Exception {
        Session ravi = s.login("ravi");
        api.get("/api/dashboard", ravi.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.students.onRoll").value(5))
                .andExpect(jsonPath("$.attendance").value(notNullValue()))
                .andExpect(jsonPath("$.homework").value(notNullValue()))
                .andExpect(jsonPath("$.leave").value(notNullValue()))
                .andExpect(jsonPath("$.myDay").value(notNullValue()))
                .andExpect(jsonPath("$.fees").value(nullValue()))
                .andExpect(jsonPath("$.admissions").value(nullValue()))
                .andExpect(jsonPath("$.staff").value(nullValue()))
                .andExpect(jsonPath("$.circulars").value(nullValue()))
                .andExpect(jsonPath("$.timetable").value(nullValue()));

        Session accounts = s.login("accounts");
        api.get("/api/dashboard", accounts.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.fees.today.amountPaise").value(0))
                .andExpect(jsonPath("$.students").value(notNullValue()))
                .andExpect(jsonPath("$.attendance").value(nullValue()))
                .andExpect(jsonPath("$.admissions").value(nullValue()))
                .andExpect(jsonPath("$.homework").value(nullValue()))
                .andExpect(jsonPath("$.staff").value(nullValue()))
                .andExpect(jsonPath("$.circulars").value(nullValue()))
                .andExpect(jsonPath("$.timetable").value(nullValue()));

        Session front = s.login("front");
        api.get("/api/dashboard", front.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.admissions.openEnquiries").value(0))
                .andExpect(jsonPath("$.admissions.followUps.items", empty()))
                .andExpect(jsonPath("$.students").value(notNullValue()))
                .andExpect(jsonPath("$.fees").value(nullValue()))
                .andExpect(jsonPath("$.attendance").value(nullValue()))
                .andExpect(jsonPath("$.homework").value(nullValue()))
                .andExpect(jsonPath("$.staff").value(nullValue()))
                .andExpect(jsonPath("$.circulars").value(nullValue()))
                .andExpect(jsonPath("$.timetable").value(nullValue()));

        // A parent has dashboard.view but none of the staff cards.
        String mother = TestApi.read(api.get("/api/students/" + s.asha, s.admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + s.asha + "/guardians/" + mother + "/sign-in", s.admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(s.email("mother"), TestApi.PASSWORD))
                .andExpect(status().isOk());
        api.get("/api/dashboard", s.login("mother").accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.students").value(nullValue()))
                .andExpect(jsonPath("$.attendance").value(nullValue()))
                .andExpect(jsonPath("$.fees").value(nullValue()))
                .andExpect(jsonPath("$.admissions").value(nullValue()))
                .andExpect(jsonPath("$.leave").value(nullValue()))
                .andExpect(jsonPath("$.homework").value(nullValue()))
                .andExpect(jsonPath("$.myDay").value(nullValue()));

        // Signed out: no dashboard at all.
        mvc.perform(MockMvcRequestBuilders.get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherSchoolsDataIsNeverCounted() throws Exception {
        s.threeDaysOfAttendance();
        // A second school with its own students, attendance, a fee payment and an enquiry.
        TestApi.School other = api.signup();
        Session otherAdmin = api.login(other);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        String otherYear = fixtures.year(otherAdmin, "Other year", s.today.minusDays(100).toString(),
                s.today.plusDays(200).toString(), true);
        String otherClass = fixtures.schoolClass(otherAdmin, "Class 5");
        String otherSection = fixtures.section(otherAdmin, otherClass, "A", 30);
        String x = fixtures.student(otherAdmin, otherSection, "O-1", "Xavier", "Paul", "Mary Paul", "9811100001");
        String y = fixtures.student(otherAdmin, otherSection, "O-2", "Yamini", "Paul", "Mary Paul", "9811100002");
        api.put("/api/attendance/registers/" + otherSection + "/" + s.today, otherAdmin.accessToken(), """
                {"entries":[{"studentId":"%s","status":"ABSENT"},{"studentId":"%s","status":"ABSENT"}]}"""
                .formatted(x, y)).andExpect(status().isOk());
        FeeFixtures fees = new FeeFixtures(api, mvc);
        fees.publishedStructure(otherAdmin, otherYear, otherClass, fees.defaultHeads(otherAdmin));
        fees.cash(otherAdmin, x, 1_000_00);
        api.post("/api/admissions/applications", otherAdmin.accessToken(), """
                {"firstName":"Zoya","dateOfBirth":"2015-01-15","classId":"%s","academicYearId":"%s",
                 "source":"WALK_IN","followUpOn":"%s",
                 "guardians":[{"name":"Zoya's mother","relation":"MOTHER","phone":"9700012399"}]}"""
                .formatted(otherClass, otherYear, s.today)).andExpect(status().isCreated());

        api.get("/api/dashboard", s.login("principal").accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.students.onRoll").value(5))
                .andExpect(jsonPath("$.attendance.sectionCount").value(3))
                .andExpect(jsonPath("$.attendance.students").value(5))
                .andExpect(jsonPath("$.attendance.counts.absent").value(1))
                .andExpect(jsonPath("$.fees.today.receiptCount").value(0))
                .andExpect(jsonPath("$.fees.outstandingPaise").value(0))
                .andExpect(jsonPath("$.admissions.openEnquiries").value(0))
                .andExpect(jsonPath("$.admissions.funnel[0].reached").value(0))
                .andExpect(jsonPath("$.admissions.followUps.dueToday").value(0));
        api.get("/api/dashboard", otherAdmin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.students.onRoll").value(2))
                .andExpect(jsonPath("$.attendance.sectionCount").value(1))
                .andExpect(jsonPath("$.attendance.counts.absent").value(2))
                .andExpect(jsonPath("$.attendance.counts.present").value(0))
                .andExpect(jsonPath("$.fees.today.amountPaise").value(1_000_00))
                .andExpect(jsonPath("$.admissions.openEnquiries").value(1))
                .andExpect(jsonPath("$.admissions.followUps.dueToday").value(1));
        // Marks in the other school never reach this one's trend.
        s.mark(s.s5B, s.today, marks(s.dev, "PRESENT"));
        api.get("/api/dashboard", s.admin.accessToken())
                .andExpect(jsonPath("$.attendance.counts.absent").value(1))
                .andExpect(jsonPath("$.attendance.counts.present").value(2));
    }

    private void submit(String homeworkId, Session student) throws Exception {
        mvc.perform(MockMvcRequestBuilders.multipart("/api/me/homework/" + homeworkId + "/submission")
                .param("text", "Done.")
                .header("Authorization", "Bearer " + student.accessToken()))
                .andExpect(status().is2xxSuccessful());
    }

    private static void followUpOn(String applicationId, LocalDate date) throws Exception {
        try (Connection owner = ownerConnection();
                PreparedStatement update = owner.prepareStatement(
                        "update admissions.application set follow_up_on = ? where id = ?")) {
            update.setDate(1, Date.valueOf(date));
            update.setObject(2, UUID.fromString(applicationId));
            update.executeUpdate();
        }
    }
}
