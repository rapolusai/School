package com.akshara.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Leave: working days and half days, balances with carry-forward, refusals (balance, overlap), routing to the
 * department head or the school, approving (ON_LEAVE days and the event), rejecting, cancelling, permissions and
 * isolation.
 */
@RecordApplicationEvents
class LeaveIT extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    private School school;
    private Session admin;
    private StaffFixtures staff;
    private String currentYear;
    private String previousYear;
    private Map<String, String> types;
    private String science;
    private String ravi;
    private String anjali;
    private String rahul;
    private Session principal;
    private Session raviSession;
    private Session anjaliSession;
    private Session rahulSession;

    @BeforeEach
    void schoolWithStaff() throws Exception {
        school = api.signup();
        admin = api.login(school);
        staff = new StaffFixtures(api, school, admin);
        String[] years = staff.years();
        currentYear = years[0];
        previousYear = years[1];
        types = staff.standardTypes();
        science = staff.department("Science");
        String primary = staff.department("Primary");
        staff.staff("Lakshmi Iyer", "principal", "PRINCIPAL", "PR-1", null);
        ravi = staff.staff("Ravi Kumar", "ravi", "TEACHER", "T-1", primary);
        anjali = staff.staff("Anjali Deshmukh", "anjali", "TEACHER", "T-2", science);
        rahul = staff.staff("Rahul Verma", "rahul", "TEACHER", "T-3", science);
        staff.head(science, "Science", anjali);
        principal = staff.login("principal");
        raviSession = staff.login("ravi");
        anjaliSession = staff.login("anjali");
        rahulSession = staff.login("rahul");
    }

    @Test
    void thePreviewCountsWorkingDaysAndHalfDays() throws Exception {
        LocalDate monday = staff.monday(1);
        api.post("/api/leave/preview", raviSession.accessToken(), preview("CL", monday, monday.plusDays(6), false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDays.length()").value(6))
                .andExpect(jsonPath("$.nonWorkingDays").value(1))
                .andExpect(jsonPath("$.days").value(6.0))
                .andExpect(jsonPath("$.balance.available").value(12.0))
                .andExpect(jsonPath("$.availableAfter").value(6.0))
                .andExpect(jsonPath("$.enough").value(true))
                .andExpect(jsonPath("$.overlaps").value(false));
        // Saturday to Monday is two days.
        api.post("/api/leave/preview", raviSession.accessToken(), preview("CL", monday.minusDays(2), monday, false))
                .andExpect(jsonPath("$.days").value(2.0));
        api.post("/api/leave/preview", raviSession.accessToken(), preview("CL", monday, monday, true))
                .andExpect(jsonPath("$.days").value(0.5))
                .andExpect(jsonPath("$.availableAfter").value(11.5));
        api.post("/api/leave/preview", raviSession.accessToken(), preview("CL", monday, monday.plusDays(14), false))
                .andExpect(jsonPath("$.days").value(13.0))
                .andExpect(jsonPath("$.enough").value(false));

        api.post("/api/leave/preview", raviSession.accessToken(), preview("CL", monday, monday.plusDays(1), true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.halfDay").exists());
        api.post("/api/leave/preview", raviSession.accessToken(), preview("EL", monday, monday, true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.halfDay").exists());
        api.post("/api/leave/preview", raviSession.accessToken(),
                preview("CL", monday.minusDays(1), monday.minusDays(1), false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.toDate").exists());
        api.post("/api/leave/preview", raviSession.accessToken(), preview("CL", monday, monday.minusDays(3), false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.toDate").exists());
        LocalDate yearEnd = staff.today.plusDays(200);
        api.post("/api/leave/preview", raviSession.accessToken(),
                preview("CL", yearEnd.plusDays(5), yearEnd.plusDays(6), false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.fromDate").exists());
        api.post("/api/leave/preview", raviSession.accessToken(),
                preview("CL", yearEnd.minusDays(2), yearEnd.plusDays(6), false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.toDate").exists());
    }

    @Test
    void balancesCarryForwardUpToTheCap() throws Exception {
        // Last year Ravi brought 25 days of earned leave from the old records: 25 + 15, capped at 30, carry over.
        api.put("/api/leave/balances", admin.accessToken(), """
                {"userId":"%s","leaveTypeId":"%s","academicYearId":"%s","opening":25,"accrued":15}"""
                .formatted(ravi, types.get("EL"), previousYear))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(40.0))
                .andExpect(jsonPath("$.setByHand").value(true));
        // Rahul took three days of sick leave last year: 10 - 3 = 7 carry over (cap 20).
        String sick = staff.applied(rahulSession, types.get("SL"), staff.today.minusDays(300),
                staff.today.minusDays(298), false);
        long sickDays = new SundayOffCalendar().workingDays(staff.today.minusDays(300), staff.today.minusDays(298))
                .size();
        staff.decide(anjaliSession, sick, "approve", null).andExpect(status().isOk());

        api.get("/api/leave/me", raviSession.accessToken())
                .andExpect(jsonPath("$.year.id").value(currentYear))
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].opening", contains(30.0)))
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].available", contains(45.0)))
                // Sick leave: last year's 10 carried (cap 20) + 10.
                .andExpect(jsonPath("$.balances[?(@.code == 'SL')].available", contains(20.0)))
                // Casual leave does not carry forward.
                .andExpect(jsonPath("$.balances[?(@.code == 'CL')].available", contains(12.0)))
                // Loss of pay has no balance.
                .andExpect(jsonPath("$.balances[?(@.code == 'LOP')].available", contains((Object) null)));
        api.get("/api/leave/me", rahulSession.accessToken())
                .andExpect(jsonPath("$.balances[?(@.code == 'SL')].opening",
                        contains(10.0 - sickDays)))
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].available", contains(30.0)));
        api.get("/api/leave/me?yearId=" + previousYear, rahulSession.accessToken())
                .andExpect(jsonPath("$.balances[?(@.code == 'SL')].taken", contains((double) sickDays)));

        // Someone who joined this year accrued nothing last year.
        String newcomer = staff.staff("Priyanka Menon", "priyanka", "TEACHER", "T-9", null,
                staff.today.minusDays(100));
        api.get("/api/staff/" + newcomer + "/leave", admin.accessToken())
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].opening", contains(0.0)))
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].available", contains(15.0)));

        api.put("/api/leave/balances", admin.accessToken(), """
                {"userId":"%s","leaveTypeId":"%s","opening":2.25,"accrued":15}""".formatted(ravi, types.get("EL")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.opening").exists());
        api.put("/api/leave/balances", admin.accessToken(), """
                {"userId":"%s","leaveTypeId":"%s","opening":2,"accrued":0}""".formatted(ravi, types.get("LOP")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.leaveTypeId").exists());
        api.get("/api/audit-events?limit=200", admin.accessToken()).andExpect(jsonPath("$[*].action", hasItem("leave_balance.set")));
    }

    @Test
    void requestsBeyondTheBalanceOrOverlappingAreRefused() throws Exception {
        LocalDate monday = staff.monday(1);
        // Six days of casual leave wait for approval; seven more would go past the 12 days.
        staff.applied(raviSession, types.get("CL"), monday, monday.plusDays(5), false);
        staff.apply(raviSession, types.get("CL"), monday.plusDays(7), monday.plusDays(14), false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.leaveTypeId").exists());
        // Loss of pay has no limit.
        staff.apply(raviSession, types.get("LOP"), monday.plusDays(7), monday.plusDays(14), false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(7.0));
        // Overlapping a pending request.
        staff.apply(raviSession, types.get("SL"), monday.plusDays(2), monday.plusDays(2), false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.fromDate").exists());
        api.post("/api/leave/preview", raviSession.accessToken(), preview("SL", monday.plusDays(2),
                monday.plusDays(2), false)).andExpect(jsonPath("$.overlaps").value(true));
        // A rejected request no longer blocks the dates.
        String sick = staff.applied(raviSession, types.get("SL"), monday.plusDays(21), monday.plusDays(21), true);
        staff.decide(principal, sick, "reject", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.comment").exists());
        staff.decide(principal, sick, "reject", "Inspection that day")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionComment").value("Inspection that day"));
        staff.decide(principal, sick, "approve", null).andExpect(status().isConflict());
        staff.apply(raviSession, types.get("SL"), monday.plusDays(21), monday.plusDays(21), false)
                .andExpect(status().isCreated());
    }

    @Test
    void requestsGoToTheDepartmentHeadOrTheSchool() throws Exception {
        LocalDate monday = staff.monday(2);
        // Rahul's department has a head: Anjali decides.
        String rahulsRequest = TestApi.read(staff.apply(rahulSession, types.get("CL"), monday, monday, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.routing").value("DEPARTMENT_HEAD"))
                .andExpect(jsonPath("$.approverName").value("Anjali Deshmukh")), "$.id");
        // Ravi's has none: the School Admin and the Principal decide.
        String ravisRequest = TestApi.read(staff.apply(raviSession, types.get("CL"), monday, monday, false)
                .andExpect(jsonPath("$.routing").value("SCHOOL")), "$.id");
        // A head's own leave goes to the school.
        String anjalisRequest = TestApi.read(staff.apply(anjaliSession, types.get("CL"), monday, monday, false)
                .andExpect(jsonPath("$.routing").value("SCHOOL")), "$.id");

        api.get("/api/leave/inbox", anjaliSession.accessToken())
                .andExpect(jsonPath("$[*].id", contains(rahulsRequest)))
                .andExpect(jsonPath("$[0].available").value(12.0))
                .andExpect(jsonPath("$[0].canDecide").value(true));
        api.get("/api/leave/inbox", principal.accessToken())
                .andExpect(jsonPath("$[*].id", contains(ravisRequest, anjalisRequest)));
        api.get("/api/leave/inbox?all=true", principal.accessToken())
                .andExpect(jsonPath("$[*].id", hasItems(rahulsRequest, ravisRequest, anjalisRequest)));
        api.get("/api/leave/inbox", raviSession.accessToken()).andExpect(jsonPath("$", empty()));
        api.get("/api/leave/me", anjaliSession.accessToken()).andExpect(jsonPath("$.waitingForMe").value(1));
        api.get("/api/leave/me", rahulSession.accessToken())
                .andExpect(jsonPath("$.approver.routing").value("DEPARTMENT_HEAD"))
                .andExpect(jsonPath("$.approver.departmentHead.name").value("Anjali Deshmukh"));

        // The head decides only what is routed to them; nobody decides their own leave.
        staff.decide(anjaliSession, ravisRequest, "approve", null).andExpect(status().isForbidden());
        staff.decide(anjaliSession, anjalisRequest, "approve", null).andExpect(status().isForbidden());
        staff.decide(raviSession, rahulsRequest, "approve", null).andExpect(status().isForbidden());
        staff.decide(anjaliSession, rahulsRequest, "approve", "Enjoy")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedByName").value("Anjali Deshmukh"));
        // The principal may decide any request.
        staff.decide(principal, anjalisRequest, "approve", null).andExpect(status().isOk());

        // When the head leaves, what waited for them goes to the school.
        String waiting = staff.applied(rahulSession, types.get("SL"), monday.plusDays(7), monday.plusDays(7), false);
        api.post("/api/staff/" + anjali + "/leaving", admin.accessToken(), """
                {"leftOn":"%s","reason":"Moved"}""".formatted(staff.today)).andExpect(status().isOk());
        api.get("/api/leave/inbox", principal.accessToken())
                .andExpect(jsonPath("$[*].id", hasItem(waiting)));
        staff.apply(rahulSession, types.get("SL"), monday.plusDays(8), monday.plusDays(8), false)
                .andExpect(jsonPath("$.routing").value("SCHOOL"));
    }

    @Test
    void approvedLeaveMarksTheDaysOnLeaveAndCancellingGivesThemBack() throws Exception {
        LocalDate monday = staff.monday(1);
        events.clear();
        String request = staff.applied(raviSession, types.get("EL"), monday, monday.plusDays(2), false);
        // Earned leave: last year's 15 carried over plus this year's 15.
        api.get("/api/leave/me", raviSession.accessToken())
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].pending", contains(3.0)))
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].available", contains(30.0)));
        staff.decide(principal, request, "approve", "Approved")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionComment").value("Approved"));
        api.get("/api/leave/me", raviSession.accessToken())
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].taken", contains(3.0)))
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].available", contains(27.0)))
                .andExpect(jsonPath("$.requests[0].canCancel").value(true));

        // The timetable hears about it.
        List<StaffLeaveApproved> approved = events.stream(StaffLeaveApproved.class).toList();
        assertThat(approved).hasSize(1);
        assertThat(approved.getFirst().userId()).isEqualTo(UUID.fromString(ravi));
        assertThat(approved.getFirst().fromDate()).isEqualTo(monday);
        assertThat(approved.getFirst().toDate()).isEqualTo(monday.plusDays(2));
        assertThat(approved.getFirst().workingDays()).containsExactly(monday, monday.plusDays(1), monday.plusDays(2));
        assertThat(approved.getFirst().days()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(approved.getFirst().tenantId()).isEqualTo(school.tenantId());

        // Its days are ON_LEAVE on the staff attendance sheet.
        api.get("/api/staff-attendance/days/" + monday.plusDays(1), admin.accessToken())
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].status", contains("ON_LEAVE")))
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].onLeaveRequest", contains(true)));
        api.get("/api/staff/" + ravi + "/attendance?month=" + monday.toString().substring(0, 7),
                admin.accessToken())
                .andExpect(jsonPath("$.days[" + (monday.getDayOfMonth() - 1) + "].status").value("ON_LEAVE"));

        // Ravi cancels before it starts: the days come back and the sheet is cleared.
        staff.decide(raviSession, request, "cancel", "Plans changed")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledByName").value("Ravi Kumar"));
        api.get("/api/leave/me", raviSession.accessToken())
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].taken", contains(0.0)))
                .andExpect(jsonPath("$.balances[?(@.code == 'EL')].available", contains(30.0)));
        api.get("/api/staff-attendance/days/" + monday.plusDays(1), admin.accessToken())
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].status", contains((Object) null)));
        assertThat(events.stream(StaffLeaveCancelled.class).toList()).hasSize(1);
        staff.decide(raviSession, request, "cancel", null).andExpect(status().isConflict());

        // A half day is a half day on the sheet.
        LocalDate past = staff.pastMonday();
        String half = staff.applied(raviSession, types.get("CL"), past, past, true);
        staff.decide(principal, half, "approve", null).andExpect(status().isOk());
        api.get("/api/staff-attendance/days/" + past, admin.accessToken())
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].status", contains("HALF_DAY")));
        // Leave that has started: only the approver can cancel it.
        staff.decide(raviSession, half, "cancel", null).andExpect(status().isConflict());
        staff.decide(principal, half, "cancel", "Came to school after all").andExpect(status().isOk());
        api.get("/api/staff-attendance/days/" + past, admin.accessToken())
                .andExpect(jsonPath("$.rows[?(@.name == 'Ravi Kumar')].status", contains((Object) null)));
        api.get("/api/audit-events?limit=200", admin.accessToken()).andExpect(jsonPath("$[*].action", hasItems(
                "leave_request.created", "leave_request.approved", "leave_request.cancelled")));
    }

    @Test
    void teachersRequestButOnlyApproversApprove() throws Exception {
        staff.staff("Meena Reddy", "meena", "ACCOUNTANT", "A-1", null);
        Session accountant = staff.login("meena");
        LocalDate monday = staff.monday(3);
        String request = staff.applied(accountant, types.get("CL"), monday, monday, false);
        // A teacher can apply but not approve someone else's request.
        staff.decide(raviSession, request, "approve", null).andExpect(status().isForbidden());
        staff.decide(raviSession, request, "reject", "No").andExpect(status().isForbidden());
        staff.decide(raviSession, request, "cancel", null).andExpect(status().isForbidden());
        api.get("/api/leave/inbox?all=true", raviSession.accessToken()).andExpect(jsonPath("$", empty()));
        // Leave types and balances belong to the School Admin.
        api.post("/api/leave/types", raviSession.accessToken(), """
                {"name":"Study leave","code":"STL","yearlyQuota":5,"carryForwardCap":0,"halfDayAllowed":false,
                 "lossOfPay":false}""").andExpect(status().isForbidden());
        api.post("/api/leave/types", principal.accessToken(), """
                {"name":"Study leave","code":"STL","yearlyQuota":5,"carryForwardCap":0,"halfDayAllowed":false,
                 "lossOfPay":false}""").andExpect(status().isForbidden());
        api.get("/api/staff/" + ravi + "/leave", raviSession.accessToken()).andExpect(status().isForbidden());
        api.get("/api/staff/" + ravi + "/leave", principal.accessToken()).andExpect(status().isOk());
        // Parents have no leave.
        api.createUser(admin, "Anitha Sharma", staff.email("anitha"), List.of("PARENT"));
        Session parent = staff.login("anitha");
        api.get("/api/leave/me", parent.accessToken()).andExpect(status().isForbidden());
        staff.apply(parent, types.get("CL"), monday, monday, false).andExpect(status().isForbidden());
        staff.decide(principal, request, "approve", null).andExpect(status().isOk());
    }

    @Test
    void leaveTypesAreSetUpByTheSchool() throws Exception {
        String study = TestApi.read(api.post("/api/leave/types", admin.accessToken(), """
                {"name":"Study leave","code":"stl","yearlyQuota":5.5,"carryForwardCap":0,"halfDayAllowed":true,
                 "lossOfPay":false}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("STL"))
                .andExpect(jsonPath("$.yearlyQuota").value(5.5)), "$.id");
        api.post("/api/leave/types", admin.accessToken(), """
                {"name":"study LEAVE","code":"X1","yearlyQuota":5,"carryForwardCap":0,"halfDayAllowed":true,
                 "lossOfPay":false}""").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/leave/types", admin.accessToken(), """
                {"name":"Other","code":"CL","yearlyQuota":5,"carryForwardCap":0,"halfDayAllowed":true,
                 "lossOfPay":false}""").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.code").exists());
        api.post("/api/leave/types", admin.accessToken(), """
                {"name":"Odd","code":"OD","yearlyQuota":2.3,"carryForwardCap":0,"halfDayAllowed":true,
                 "lossOfPay":false}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.yearlyQuota").exists());
        // Used types can be switched off but not deleted; unused ones can be deleted.
        LocalDate monday = staff.monday(1);
        staff.applied(raviSession, study, monday, monday, false);
        api.delete("/api/leave/types/" + study, admin.accessToken()).andExpect(status().isConflict());
        api.put("/api/leave/types/" + study, admin.accessToken(), """
                {"name":"Study leave","code":"STL","yearlyQuota":5.5,"carryForwardCap":0,"halfDayAllowed":true,
                 "lossOfPay":false,"active":false}""").andExpect(status().isOk());
        api.get("/api/leave/types", raviSession.accessToken())
                .andExpect(jsonPath("$[*].code", not(hasItem("STL"))));
        api.get("/api/leave/types?all=true", admin.accessToken())
                .andExpect(jsonPath("$[?(@.code == 'STL')].inUse", contains(true)));
        staff.apply(raviSession, study, monday.plusDays(1), monday.plusDays(1), false)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.leaveTypeId").exists());
        api.delete("/api/leave/types/" + types.get("PL"), admin.accessToken()).andExpect(status().isNoContent());
        // Adding the standard types again adds only the missing one.
        api.post("/api/leave/types/standard", admin.accessToken(), null)
                .andExpect(jsonPath("$.length()").value(7));
        api.get("/api/audit-events?limit=200", admin.accessToken()).andExpect(jsonPath("$[*].action",
                hasItems("leave_type.created", "leave_type.updated", "leave_type.deleted")));
    }

    @Test
    void anotherSchoolsLeaveIsInvisible() throws Exception {
        School otherSchool = api.signup();
        Session otherAdmin = api.login(otherSchool);
        StaffFixtures other = new StaffFixtures(api, otherSchool, otherAdmin);
        String otherYear = other.years()[0];
        Map<String, String> otherTypes = other.standardTypes();
        String kiran = other.staff("Kiran Joshi", "kiran", "TEACHER", "T-1", null);
        Session kiranSession = other.login("kiran");
        LocalDate monday = staff.monday(1);
        String otherRequest = other.applied(kiranSession, otherTypes.get("CL"), monday, monday, false);

        staff.decide(principal, otherRequest, "approve", null).andExpect(status().isNotFound());
        staff.decide(admin, otherRequest, "cancel", null).andExpect(status().isNotFound());
        api.get("/api/leave/inbox?all=true", principal.accessToken())
                .andExpect(jsonPath("$[*].id", not(hasItem(otherRequest))));
        staff.apply(raviSession, otherTypes.get("CL"), monday, monday, false)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.leaveTypeId").exists());
        api.put("/api/leave/types/" + otherTypes.get("CL"), admin.accessToken(), """
                {"name":"Casual leave","code":"CL","yearlyQuota":1,"carryForwardCap":0,"halfDayAllowed":true,
                 "lossOfPay":false}""").andExpect(status().isNotFound());
        api.put("/api/leave/balances", admin.accessToken(), """
                {"userId":"%s","leaveTypeId":"%s","opening":1,"accrued":12}""".formatted(kiran, types.get("CL")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.userId").exists());
        api.get("/api/leave/me?yearId=" + otherYear, raviSession.accessToken())
                .andExpect(status().isNotFound());
    }

    private String preview(String code, LocalDate from, LocalDate to, boolean halfDay) {
        return """
                {"leaveTypeId":"%s","fromDate":"%s","toDate":"%s","halfDay":%s}"""
                .formatted(types.get(code), from, to, halfDay);
    }
}
