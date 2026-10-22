package com.akshara.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import com.akshara.academics.AcademicsService;
import com.akshara.admissions.AdmissionsService;
import com.akshara.identity.UserService;
import com.akshara.platform.TenantDirectory;
import com.akshara.students.StudentService;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/** The demo school a developer gets locally: its people, classes and students, created once. */
class DemoDataSeederIT extends IntegrationTest {

    /** Test-only password for a throwaway database. */
    static final String DEMO_PASSWORD = "Demo-only-password-1";

    @Autowired
    SchoolProvisioning provisioning;

    @Autowired
    TenantDirectory tenants;

    @Autowired
    UserService users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AcademicsService academics;

    @Autowired
    StudentService students;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    AdmissionsService admissions;

    @Autowired
    DemoAttendanceData attendanceData;

    @Autowired
    DemoFeesData demoFees;

    @Autowired
    DemoStaffData staffData;

    @Autowired
    DemoCommunicationData communicationData;

    @Autowired
    DemoTimetableHomeworkData timetableHomeworkData;

    @Autowired
    DemoBillingData demoBilling;

    @Autowired
    DemoPrivacyData demoPrivacy;

    @Autowired
    DemoPortalData portalData;

    @Autowired
    DemoLibraryData libraryData;

    @Autowired
    DemoPayrollData payrollData;

    @Autowired
    DemoExamsData examsData;

    @Autowired
    DemoChatData chatData;

    @Autowired
    DemoLessonsData lessonsData;

    @Autowired
    DemoTransportData transportData;

    @Autowired
    DemoCertificatesData certificatesData;

    @Autowired
    DemoFrontOfficeData frontOfficeData;

    @Autowired
    DemoAccountsData accountsData;

    @Test
    void seedsADemoSchoolWithClassesAndStudentsOnce() throws Exception {
        DemoDataSeeder seeder = new DemoDataSeeder(provisioning, tenants, users, passwordEncoder, DEMO_PASSWORD,
                academics, students, transactionManager, admissions, attendanceData, demoFees, staffData,
                communicationData, timetableHomeworkData, demoBilling, demoPrivacy, portalData, libraryData,
                payrollData, examsData, chatData, lessonsData, transportData, certificatesData, frontOfficeData,
                accountsData);
        seeder.run(null);
        // A second start leaves the existing demo school alone.
        seeder.run(null);

        String code = DemoDataSeeder.DEMO_CODE;
        Session admin = api.login(code, "admin" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
        String lastYear = TestApi.read(api.get("/api/academics/years", admin.accessToken())
                .andExpect(jsonPath("$[*].name", contains("2026-27", "2025-26")))
                .andExpect(jsonPath("$[0].current").value(true)), "$[1].id");
        api.get("/api/academics/classes", admin.accessToken())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].name").value("LKG"))
                .andExpect(jsonPath("$[6].name").value("Class 5"))
                .andExpect(jsonPath("$[6].sections.length()").value(2))
                .andExpect(jsonPath("$[6].sections[0].classTeacher.name").value("Ravi Kumar"))
                .andExpect(jsonPath("$[11].sections.length()").value(1));
        api.get("/api/academics/subjects", admin.accessToken()).andExpect(jsonPath("$.length()").value(12));

        int all = DemoSchoolData.STUDENTS.size();
        int graduates = (int) DemoSchoolData.STUDENTS.stream().filter(s -> s.contains("|Class 10|")).count();
        int admittedLastYear = (int) DemoSchoolData.STUDENTS.stream().filter(s -> s.contains("|2025|")).count();
        api.get("/api/students?size=100", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(all - graduates + DemoAdmissionsData.ADMITTED_STUDENTS));
        // Alumni were last enrolled in 2025-26.
        api.get("/api/students?status=ALUMNI&yearId=" + lastYear, admin.accessToken())
                .andExpect(jsonPath("$.total").value(graduates));
        api.get("/api/students?yearId=" + lastYear, admin.accessToken())
                .andExpect(jsonPath("$.total").value(admittedLastYear));

        Session parent = api.login(code, "parent" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
        api.get("/api/me/children", parent.accessToken())
                .andExpect(jsonPath("$[*].fullName", contains("Arjun Sharma", "Diya Sharma")))
                .andExpect(jsonPath("$[0].className").value("Class 5"))
                .andExpect(jsonPath("$[0].sectionName").value("A"))
                .andExpect(jsonPath("$[0].classTeacherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$[1].className").value("Class 2"))
                .andExpect(jsonPath("$[1].sectionName").value("A"));

        Session student = api.login(code, "student" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
        String arjun = TestApi.read(api.get("/api/me/student", student.accessToken())
                .andExpect(jsonPath("$.fullName").value("Arjun Sharma")), "$.id");
        api.get("/api/students/" + arjun, admin.accessToken())
                .andExpect(jsonPath("$.guardians[0].relation").value("MOTHER"))
                .andExpect(jsonPath("$.guardians[0].phone").value("9876500001"))
                .andExpect(jsonPath("$.enrollments.length()").value(2))
                .andExpect(jsonPath("$.siblings[*].fullName", hasItem("Diya Sharma")));

        Session frontOffice = api.login(code, "frontoffice" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
        api.get("/api/students", frontOffice.accessToken()).andExpect(status().isOk());

        // The admissions pipeline: every stage has applications, with upcoming tests and one child admitted.
        api.get("/api/admissions/applications", frontOffice.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(DemoAdmissionsData.APPLICATIONS))
                .andExpect(jsonPath("$.stageCounts.ENQUIRY").value(5))
                .andExpect(jsonPath("$.stageCounts.APPLICATION").value(2))
                .andExpect(jsonPath("$.stageCounts.ASSESSMENT").value(3))
                .andExpect(jsonPath("$.stageCounts.OFFERED").value(2))
                .andExpect(jsonPath("$.stageCounts.ADMITTED").value(1))
                .andExpect(jsonPath("$.stageCounts.REJECTED").value(1))
                .andExpect(jsonPath("$.stageCounts.WITHDRAWN").value(1));
        api.get("/api/admissions/slots/upcoming", frontOffice.accessToken())
                .andExpect(jsonPath("$.length()").value(DemoAdmissionsData.UPCOMING_SLOTS));
        String admitted = TestApi.read(api.get("/api/admissions/applications?stage=ADMITTED", admin.accessToken()),
                "$.items[0].id");
        String newStudent = TestApi.read(api.get("/api/admissions/applications/" + admitted, admin.accessToken())
                .andExpect(jsonPath("$.timeline[0].actorName").value("Suresh Rao")), "$.studentId");
        api.get("/api/students/" + newStudent, admin.accessToken())
                .andExpect(jsonPath("$.admissionNo").value(DemoAdmissionsData.ADMISSION_NO))
                .andExpect(jsonPath("$.currentEnrollment.className").value("LKG"));
        // Attendance for the last school days, with Class 5 A left for its class teacher today.
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        if (!today.isAfter(LocalDate.of(2027, 3, 31))) {
            Session teacher = api.login(code, "teacher" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
            api.get("/api/attendance/sections", teacher.accessToken())
                    .andExpect(jsonPath("$.sections.length()").value(1))
                    .andExpect(jsonPath("$.sections[0].label").value("Class 5 A"))
                    .andExpect(jsonPath("$.sections[0].marked").value(false));
            List<LocalDate> days = DemoAttendanceData.schoolDays(today, LocalDate.of(2026, 6, 1));
            LocalDate lastMarked = days.get(days.size() - 2);
            api.get("/api/attendance/registers/" + TestApi.read(api.get("/api/attendance/sections",
                    teacher.accessToken()), "$.sections[0].sectionId") + "/" + lastMarked, teacher.accessToken())
                    .andExpect(jsonPath("$.marked").value(true))
                    .andExpect(jsonPath("$.markedByName").value("Ravi Kumar"));
            api.get("/api/attendance/today", admin.accessToken()).andExpect(status().isOk());
            api.get("/api/me/children/" + arjun + "/attendance?month=" + YearMonth.from(lastMarked),
                    parent.accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recentAbsences[*].date", hasItem(lastMarked.toString())));
            // Absence alerts went through the outbox and the simulated sender.
            String simulated = TestApi.read(api.get("/api/messages?status=SIMULATED", admin.accessToken()), "$.total");
            assertThat(Integer.parseInt(simulated)).isPositive();
            api.get("/api/messages?status=FAILED", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
        }
        // Fees: every class has a published 2026-27 structure in four quarters, most of Q1 and Q2 is paid, and Arjun
        // still owes Q2, so the demo parent has something to pay.
        Session accounts = api.login(code, "accounts" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
        api.get("/api/fees/structures", accounts.accessToken())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[*].status", everyItem(is("PUBLISHED"))))
                .andExpect(jsonPath("$[*].instalmentCount", everyItem(is(4))));
        api.get("/api/fees/concessions", accounts.accessToken())
                .andExpect(jsonPath("$[?(@.type == 'RTE')].studentName", containsInAnyOrder(
                        DemoFeesData.RTE_STUDENTS.toArray())))
                .andExpect(jsonPath("$[?(@.type == 'SIBLING')].studentName", contains(DemoFeesData.DIYA)));
        String arjunFees = api.get("/api/me/children/" + arjun + "/fees", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instalments.length()").value(4))
                .andExpect(jsonPath("$.instalments[0].label").value("Quarter 1"))
                .andExpect(jsonPath("$.instalments[0].dueDate").value("2026-06-10"))
                .andExpect(jsonPath("$.instalments[0].status").value("PAID"))
                .andExpect(jsonPath("$.instalments[1].status").value("OVERDUE"))
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(arjunFees, "$.totals.overduePaise")).isPositive();
        String diya = TestApi.read(api.get("/api/me/children", parent.accessToken()), "$[1].id");
        api.get("/api/me/children/" + diya + "/fees", parent.accessToken())
                .andExpect(jsonPath("$.concessions[0].type").value("SIBLING"))
                .andExpect(jsonPath("$.instalments[0].concessionPaise").value(825_00))
                .andExpect(jsonPath("$.instalments[1].status").value("PAID"));

        // Receipt numbers run from 000001 without gaps, in the order the payments were received.
        List<String> numbers = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = api.get("/api/fees/receipts?size=100&page=" + page, accounts.accessToken())
                    .andReturn().getResponse().getContentAsString();
            numbers.addAll(JsonPath.read(body, "$.items[*].receiptNo"));
        }
        assertThat(numbers).hasSizeGreaterThan(80);
        List<String> expected = new ArrayList<>();
        for (int i = numbers.size(); i >= 1; i--) {
            expected.add("RCPT/2026-27/" + String.format("%06d", i));
        }
        assertThat(numbers).isEqualTo(expected);
        api.get("/api/fees/receipts?status=CANCELLED", accounts.accessToken())
                .andExpect(jsonPath("$.total").value(1));
        String overview = api.get("/api/fees/reports/overview", accounts.accessToken())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(overview, "$.overdueStudents")).isBetween(5, 15);

        // Staff records: every demo staff member has a profile, plus the extra teachers, the timetable's subject
        // teachers, the librarian and the bus drivers and attendants, and the security guard.
        int staffCount = DemoDataSeeder.PEOPLE.size() - 2 + 1 + DemoStaffData.EXTRA_TEACHERS
                + DemoTimetableHomeworkData.TEACHERS.size() + 1 + DemoTransportData.CREW.size() + 1;
        api.get("/api/staff?size=100", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(staffCount))
                .andExpect(jsonPath("$.incompleteProfiles").value(0));
        api.get("/api/staff/departments", admin.accessToken())
                .andExpect(jsonPath("$.length()").value(DemoStaffData.DEPARTMENTS.size()))
                .andExpect(jsonPath("$[?(@.name == 'Science')].head.name", contains("Anjali Deshmukh")));
        api.get("/api/leave/types", admin.accessToken()).andExpect(jsonPath("$.length()").value(6));
        if (!today.isAfter(LocalDate.of(2027, 3, 20)) && !today.isBefore(LocalDate.of(2026, 7, 1))) {
            // Ravi Kumar's request waits for the principal; Rahul Verma's for his department head.
            Session principal = api.login(code, "principal" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
            api.get("/api/leave/inbox", principal.accessToken())
                    .andExpect(jsonPath("$[*].userName", hasItem("Ravi Kumar")));
            Session anjali = api.login(code, "anjali.deshmukh" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
            api.get("/api/leave/inbox", anjali.accessToken())
                    .andExpect(jsonPath("$[*].userName", contains("Rahul Verma")));
            Session ravi = api.login(code, "teacher" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
            api.get("/api/leave/me", ravi.accessToken())
                    .andExpect(jsonPath("$.requests[?(@.status == 'PENDING')].leaveTypeCode", contains("CL")))
                    .andExpect(jsonPath("$.approver.routing").value("SCHOOL"));
            if (today.getDayOfWeek() != DayOfWeek.SUNDAY) {
                api.get("/api/staff-attendance/me/today", ravi.accessToken())
                        .andExpect(jsonPath("$.canCheckIn").value(true));
                api.get("/api/staff-attendance/today", principal.accessToken())
                        .andExpect(jsonPath("$.activeStaff").value(staffCount))
                        .andExpect(jsonPath("$.checkedIn").value(greaterThan(5)));
            }
            api.get("/api/staff-attendance/month", principal.accessToken())
                    .andExpect(jsonPath("$.staff.length()").value(staffCount));
        }

        // Circulars in every state, and a calendar with holidays, events, an exam week and a PTM.
        Session principal = api.login(code, "principal" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
        api.get("/api/notices", principal.accessToken())
                .andExpect(jsonPath("$.items.length()").value(DemoCommunicationData.CIRCULARS))
                .andExpect(jsonPath("$.counts.SENT").value(5))
                .andExpect(jsonPath("$.counts.SCHEDULED").value(1))
                .andExpect(jsonPath("$.counts.DRAFT").value(1))
                .andExpect(jsonPath("$.counts.PENDING_APPROVAL").value(1));
        api.get("/api/notices/board", parent.accessToken())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.unread").value(2))
                .andExpect(jsonPath("$.items[0].category").value("URGENT"))
                .andExpect(jsonPath("$.items[0].pinned").value(true));
        api.get("/api/calendar/entries", parent.accessToken())
                .andExpect(jsonPath("$.entries[*].title", hasItem("Gandhi Jayanti")))
                .andExpect(jsonPath("$.entries[*].title", hasItem("Parent-teacher meeting")))
                .andExpect(jsonPath("$.entries[?(@.title == 'Staff meeting')]").isEmpty());
        api.get("/api/calendar/entries", principal.accessToken())
                .andExpect(jsonPath("$.entries[*].title", hasItem("Staff meeting")));
        // Timetables: Class 5 A and Class 2 A complete and clash-free, Class 5 B partly done.
        api.get("/api/timetable/clashes", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clashes.length()").value(0))
                .andExpect(jsonPath("$.warnings.length()").value(0))
                .andExpect(jsonPath("$.sectionsChecked").value(3));
        api.get("/api/timetable/bell-schedule", admin.accessToken())
                .andExpect(jsonPath("$.weekdayPeriods").value(8))
                .andExpect(jsonPath("$.saturdayPeriods").value(5));
        Session ravi = api.login(code, "teacher" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
        api.get("/api/timetable/me", ravi.accessToken())
                .andExpect(jsonPath("$.slots.length()")
                        .value(8 + 8 + DemoTimetableHomeworkData.CLASS_5B_MATHS.length));
        // Homework: ten items for Class 5 A, four answered by Arjun.
        api.get("/api/me/homework", student.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.items.length()").value(DemoTimetableHomeworkData.HOMEWORK.size()))
                .andExpect(jsonPath("$.items[?(@.status != 'PENDING')]", hasSize(4)));
        api.get("/api/me/children/" + arjun + "/homework", parent.accessToken())
                .andExpect(jsonPath("$.items.length()").value(DemoTimetableHomeworkData.HOMEWORK.size()));
        api.get("/api/homework?when=all", ravi.accessToken())
                .andExpect(jsonPath("$.total").value(DemoTimetableHomeworkData.HOMEWORK.size()));
        // Billing with Akshara: a yearly Growth subscription in Telangana, its one invoice paid by bank transfer.
        String invoice = TestApi.read(api.get("/api/billing", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan").value("GROWTH"))
                .andExpect(jsonPath("$.subscription.billingCycle").value("YEARLY"))
                .andExpect(jsonPath("$.details.legalName").value(DemoBillingData.LEGAL_NAME))
                .andExpect(jsonPath("$.details.stateCode").value("36"))
                .andExpect(jsonPath("$.invoices.length()").value(1))
                .andExpect(jsonPath("$.invoices[0].status").value("PAID"))
                .andExpect(jsonPath("$.notice.kind").doesNotExist()), "$.invoices[0].id");
        api.get("/api/billing/invoices/" + invoice, admin.accessToken())
                .andExpect(jsonPath("$.taxSplit").value("CGST_SGST"))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].reference").value(DemoBillingData.UTR));
        // Data protection: notice version 1 with the principal as grievance officer, the demo parent's consent for
        // both children, and one open access request about Arjun.
        api.get("/api/privacy/notice", principal.accessToken())
                .andExpect(jsonPath("$.current.version").value(1))
                .andExpect(jsonPath("$.officer.name").value("Lakshmi Iyer"));
        api.get("/api/me/privacy", parent.accessToken())
                .andExpect(jsonPath("$.needsConsent").value(false))
                .andExpect(jsonPath("$.children[0].purposes[?(@.purpose == 'PHOTOS')].status", contains("GIVEN")))
                .andExpect(jsonPath("$.children[1].purposes[?(@.purpose == 'PHOTOS')].status",
                        contains("DECLINED")));
        api.get("/api/privacy/requests?status=OPEN", principal.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].type").value("ACCESS"))
                .andExpect(jsonPath("$.items[0].studentName").value("Arjun Sharma"))
                .andExpect(jsonPath("$.items[0].requesterName").value("Anitha Sharma"));
        // Child leave: Diya's approved by the principal, Arjun's half day waiting for his class teacher.
        if (!today.isAfter(LocalDate.of(2027, 1, 31))) {
            api.get("/api/me/children/" + diya + "/leave-requests", parent.accessToken())
                    .andExpect(jsonPath("$.requests.length()").value(1))
                    .andExpect(jsonPath("$.requests[0].status").value("APPROVED"))
                    .andExpect(jsonPath("$.requests[0].decidedByName").value("Lakshmi Iyer"));
            api.get("/api/attendance/leave-requests", ravi.accessToken())
                    .andExpect(jsonPath("$.pending.length()").value(1))
                    .andExpect(jsonPath("$.pending[0].studentName").value("Arjun Sharma"))
                    .andExpect(jsonPath("$.pending[0].halfDay").value(true))
                    .andExpect(jsonPath("$.pending[0].canDecide").value(true));
            api.get("/api/attendance/leave-requests", principal.accessToken())
                    .andExpect(jsonPath("$.pending.length()").value(1))
                    .andExpect(jsonPath("$.recent[0].status").value("APPROVED"));
        }
        // Library: sixty titles, Arjun's overdue book (Sneha Iyengar waits for it), two classmates' and the demo
        // teacher's books on loan, and Kunal Mehta's late return with its fine paid.
        Session librarian = api.login(code, DemoLibraryData.LIBRARIAN_EMAIL, DEMO_PASSWORD);
        api.get("/api/library/titles?size=100", librarian.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(DemoLibraryData.TITLE_COUNT))
                .andExpect(jsonPath("$.stats.titles").value(DemoLibraryData.TITLE_COUNT))
                .andExpect(jsonPath("$.stats.issued").value(4))
                .andExpect(jsonPath("$.stats.overdue").value(1));
        api.get("/api/library/reports/overdue", librarian.accessToken())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].member.name").value("Arjun Sharma"))
                .andExpect(jsonPath("$.rows[0].member.detail").value("Class 5 A"))
                .andExpect(jsonPath("$.rows[0].title").value(DemoLibraryData.OVERDUE_TITLE));
        api.get("/api/library/reservations", librarian.accessToken())
                .andExpect(jsonPath("$[*].member.name", contains("Sneha Iyengar")))
                .andExpect(jsonPath("$[0].status").value("WAITING"));
        api.get("/api/library/fines?status=PAID", librarian.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].member.name").value("Kunal Mehta"))
                .andExpect(jsonPath("$.items[0].receiptNo", startsWith("LIBF/")));
        // Certificates: two bonafide certificates and Laila Ahmed's transfer certificate issued (she left), ID cards
        // for Class 5 A; Arjun's mother sees his certificate and his card.
        api.get("/api/certificates?status=ISSUED", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].type", hasItems("BONAFIDE", "TRANSFER")));
        api.get("/api/students?status=TRANSFERRED", admin.accessToken())
                .andExpect(jsonPath("$.items[*].fullName", hasItem(DemoCertificatesData.LEFT_STUDENT)));
        api.get("/api/certificates/id-cards?subjectType=STUDENT", admin.accessToken())
                .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.items[0].subject.detail").value(DemoCertificatesData.CARDS_SECTION));
        api.get("/api/me/certificates", parent.accessToken())
                .andExpect(jsonPath("$.children[?(@.name=='Arjun Sharma')].certificates[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.children[?(@.name=='Arjun Sharma')].idCard.cardNo").exists());
        api.get("/api/me/library", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[*].name", contains("Arjun Sharma", "Diya Sharma")))
                .andExpect(jsonPath("$.members[0].loans[0].title").value(DemoLibraryData.OVERDUE_TITLE))
                .andExpect(jsonPath("$.members[0].loans[0].overdue").value(true))
                .andExpect(jsonPath("$.members[1].loans.length()").value(0));
        api.get("/api/library/titles", principal.accessToken()).andExpect(status().isOk());
        api.get("/api/library/loans", principal.accessToken()).andExpect(status().isForbidden());
        // Payroll: every active member of staff has a structure and fake bank details; last month is approved by the
        // School Admin and paid, and the demo teacher sees his own payslip with the account masked.
        String lastMonth = YearMonth.now(ZoneId.of("Asia/Kolkata")).minusMonths(1).toString();
        api.get("/api/payroll/runs", accounts.accessToken())
                .andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].month").value(lastMonth))
                .andExpect(jsonPath("$.runs[0].status").value("PAID"))
                .andExpect(jsonPath("$.runs[0].approvedByName").value("Priya Nair"))
                .andExpect(jsonPath("$.runs[0].totals.staffCount").value(greaterThan(10)))
                .andExpect(jsonPath("$.needStructure").value(0));
        api.get("/api/payroll/staff", accounts.accessToken())
                .andExpect(jsonPath("$.staff[*].hasBank", everyItem(is(true))))
                .andExpect(jsonPath("$.needBankDetails").value(0));
        String payslip = TestApi.read(api.get("/api/me/payslips", ravi.accessToken())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].month").value(lastMonth))
                .andExpect(jsonPath("$[0].status").value("PAID")), "$[0].id");
        api.get("/api/me/payslips/" + payslip, ravi.accessToken())
                .andExpect(jsonPath("$.person.name").value("Ravi Kumar"))
                .andExpect(jsonPath("$.person.panMasked").value(startsWith("XXXXXX")))
                .andExpect(jsonPath("$.school.name").value("Akshara Demo School"));
        // Exams: Periodic Test 1 for Class 5 marked, locked and published, so the demo parent sees Arjun's results
        // and report card; the Half-Yearly date sheet for Classes 2 and 5 is published.
        api.get("/api/exams/structure", principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms[*].name", contains("Term 1", "Term 2")))
                .andExpect(jsonPath("$.termWeightTotal").value(100))
                .andExpect(jsonPath("$.terms[0].exams[*].name", contains(DemoExamsData.PERIODIC_TEST,
                        DemoExamsData.HALF_YEARLY)))
                .andExpect(jsonPath("$.terms[0].examWeightTotal").value(100));
        api.get("/api/me/children/" + arjun + "/results", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].examName", contains(DemoExamsData.PERIODIC_TEST)))
                .andExpect(jsonPath("$.results[0].percent").value(89.5))
                .andExpect(jsonPath("$.results[0].grade").value("A2"))
                .andExpect(jsonPath("$.results[0].result").value("PASS"));
        String termOne = TestApi.read(api.get("/api/me/children/" + arjun + "/results", parent.accessToken()),
                "$.terms[0].id");
        api.get("/api/me/children/" + arjun + "/report-card?termId=" + termOne, parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedOnly").value(true))
                .andExpect(jsonPath("$.columns[*].name", contains(DemoExamsData.PERIODIC_TEST)))
                .andExpect(jsonPath("$.cards.length()").value(1))
                .andExpect(jsonPath("$.cards[0].fullName").value("Arjun Sharma"))
                .andExpect(jsonPath("$.cards[0].coScholastic.workEducation").value("A"))
                .andExpect(jsonPath("$.cards[0].hpc.socioEmotional").value("ADVANCED"));
        if (today.isBefore(DemoExamsData.EXAM_STARTS)) {
            api.get("/api/me/children/" + diya + "/results", parent.accessToken())
                    .andExpect(jsonPath("$.results").isEmpty())
                    .andExpect(jsonPath("$.upcoming[0].examName").value(DemoExamsData.HALF_YEARLY))
                    .andExpect(jsonPath("$.upcoming[0].papers.length()").value(6));
        }
        api.get("/api/exams/marks", ravi.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exams[0].examName").value(DemoExamsData.PERIODIC_TEST))
                .andExpect(jsonPath("$.exams[0].sheets[*].status", everyItem(is("LOCKED"))));
        // Messages: Anitha Sharma and Ravi Kumar wrote about Arjun; her last message is unread for him. A Class 5 PTM
        // has her booking with him and two made by the office.
        api.get("/api/chat/unread", ravi.accessToken())
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.conversations").value(1));
        api.get("/api/me/chat/conversations", parent.accessToken())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].withName").value("Ravi Kumar"))
                .andExpect(jsonPath("$[0].student.name").value("Arjun Sharma"))
                .andExpect(jsonPath("$[0].unread").value(0));
        api.get("/api/ptm/events", principal.accessToken())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value(DemoChatData.PTM_TITLE))
                .andExpect(jsonPath("$[0].booked").value(3));
        api.get("/api/me/ptm", parent.accessToken())
                .andExpect(jsonPath("$.children[0].name").value("Arjun Sharma"))
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].name").value("Ravi Kumar"))
                .andExpect(jsonPath("$.children[0].events[0].teachers[0].booking.startsAt").value("09:30:00"));
        api.get("/api/ptm/my", ravi.accessToken())
                .andExpect(jsonPath("$[0].schedule.booked").value(3));
        // Lessons: the Class 5 syllabi, Ravi's plan waiting for the principal, the question bank, the two pieces
        // of material and Arjun's released score in the closed online test, which his mother sees too.
        api.get("/api/lessons/syllabi", principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].subjectName", containsInAnyOrder("Mathematics", "English",
                        "Environmental Studies")));
        api.get("/api/lessons/plans/queue", principal.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].teacherName", contains("Ravi Kumar")));
        api.get("/api/lessons/questions", ravi.accessToken()).andExpect(jsonPath("$.length()").value(30));
        api.get("/api/me/study", student.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.material.length()").value(2))
                .andExpect(jsonPath("$.tests[?(@.title == '" + DemoLessonsData.OPEN_TEST + "')].canStart",
                        contains(true)));
        api.get("/api/me/children/" + arjun + "/study", parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tests[?(@.title == '" + DemoLessonsData.CLOSED_TEST + "')].score",
                        contains(7.5)))
                .andExpect(jsonPath("$.tests[?(@.title == '" + DemoLessonsData.CLOSED_TEST + "')].maxScore",
                        contains(9.0)));
        // Transport: three routes with a driver and an attendant each, Arjun and other students on the buses, the
        // mini bus's fitness certificate due soon, and this morning's trip of Arjun's route run by its attendant.
        api.get("/api/transport/routes", admin.accessToken())
                .andExpect(jsonPath("$[*].name", contains(DemoTransportData.ROUTE_ONE, "Route 2 - Kukatpally",
                        "Route 3 - Secunderabad")))
                .andExpect(jsonPath("$[*].flags[*]", not(hasItem("FITNESS_INVALID"))));
        api.get("/api/transport/allocations", admin.accessToken())
                .andExpect(jsonPath("$.length()").value(DemoTransportData.OTHER_RIDERS + 1));
        api.get("/api/transport/reports/documents", principal.accessToken())
                .andExpect(jsonPath("$.rows[0].subject").value("TS09 UC 1029"))
                .andExpect(jsonPath("$.rows[0].document").value("FITNESS"))
                .andExpect(jsonPath("$.rows[0].daysLeft").value(DemoTransportData.FITNESS_DAYS_LEFT));
        Session busAttendant = api.login(code, DemoTransportData.ATTENDANT_EMAIL, DEMO_PASSWORD);
        api.get("/api/transport/my-trips", busAttendant.accessToken())
                .andExpect(jsonPath("$.routes[*].name", contains(DemoTransportData.ROUTE_ONE)))
                .andExpect(jsonPath("$.routes[0].role").value("ATTENDANT"))
                .andExpect(jsonPath("$.routes[0].morning.status").value("COMPLETED"));
        api.get("/api/me/transport", parent.accessToken())
                .andExpect(jsonPath("$.children[0].name").value("Arjun Sharma"))
                .andExpect(jsonPath("$.children[0].allocation.routeName").value(DemoTransportData.ROUTE_ONE))
                .andExpect(jsonPath("$.children[0].trips[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.children[0].trips[0].childStatus").value("BOARDED"));
        // Front office: the security guard signs in and works the gate, where two visitors are inside and Arjun's
        // approved early pickup waits for the OTP his mother got by (simulated) message; the office has a follow-up
        // due today and two courier entries.
        Session securityGuard = api.login(code, DemoFrontOfficeData.SECURITY_EMAIL, DEMO_PASSWORD);
        api.get("/api/frontoffice/gate", securityGuard.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inside.length()").value(2))
                .andExpect(jsonPath("$.passes.length()").value(1))
                .andExpect(jsonPath("$.passes[0].studentName").value(DemoFrontOfficeData.ARJUN))
                .andExpect(jsonPath("$.passes[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.passes[0].collectorName").value("Rajesh Sharma"))
                .andExpect(jsonPath("$.passes[0].canRelease").value(true));
        api.get("/api/frontoffice/gate-passes", securityGuard.accessToken()).andExpect(status().isForbidden());
        api.get("/api/frontoffice/visitors", frontOffice.accessToken())
                .andExpect(jsonPath("$.visitors.length()").value(3))
                .andExpect(jsonPath("$.inside").value(2))
                .andExpect(jsonPath("$.visitors[?(@.status == 'OUT')].name", contains("Prakash Nair")));
        api.get("/api/frontoffice/follow-ups", frontOffice.accessToken())
                .andExpect(jsonPath("$.dueToday").value(2))
                .andExpect(jsonPath("$.overdue").value(1));
        api.get("/api/frontoffice/courier", frontOffice.accessToken()).andExpect(jsonPath("$.length()").value(2));
        api.get("/api/frontoffice/blocklist", frontOffice.accessToken()).andExpect(jsonPath("$.length()").value(1));
        api.get("/api/me/children/" + arjun + "/pickup", parent.accessToken())
                .andExpect(jsonPath("$.passes[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.persons[*].name", contains("Kamla Devi", "Rajesh Sharma")));
        api.get("/api/frontoffice/visitors", principal.accessToken()).andExpect(status().isOk());
        api.post("/api/frontoffice/visitors", principal.accessToken(), """
                {"name":"X","purpose":"MEETING"}""").andExpect(status().isForbidden());
        // Accounts: the chart with the fee heads mapped, bills waiting for the principal, paid bills in the books,
        // last month's payroll cost, donations with 80G numbers, a budget, and the fee income summary matching
        // the fee receipts day by day.
        api.get("/api/accounts/heads", accounts.accessToken())
                .andExpect(jsonPath("$[?(@.systemCode == 'FEES')].name", contains("Fee income")))
                .andExpect(jsonPath("$[?(@.feeHeadId)]", hasSize(greaterThan(2))));
        api.get("/api/accounts/expenses/queue", principal.accessToken())
                .andExpect(jsonPath("$.expenses.length()").value(greaterThan(2)))
                .andExpect(jsonPath("$.limited").value(true));
        api.get("/api/accounts/expenses?status=PAID", accounts.accessToken())
                .andExpect(jsonPath("$.paidCount").value(greaterThan(20)))
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andExpect(jsonPath("$.draftCount").value(2));
        api.get("/api/accounts/payroll-costs", principal.accessToken())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].staffCount").value(greaterThan(5)));
        api.get("/api/accounts/income?from=" + today.minusDays(200) + "&to=" + today, accounts.accessToken())
                .andExpect(jsonPath("$[?(@.eightyGNumber)]", hasSize(greaterThan(1))));
        int fyStart = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        api.get("/api/accounts/budgets/" + fyStart + "-" + String.format("%02d", (fyStart + 1) % 100),
                principal.accessToken()).andExpect(jsonPath("$.budgetPaise").value(greaterThan(1000000)));
        api.get("/api/accounts/fee-reconciliation?from=" + today.minusDays(200) + "&to=" + today,
                accounts.accessToken())
                .andExpect(jsonPath("$.daysWithDifferences").value(0))
                .andExpect(jsonPath("$.accountsPaise").value(greaterThan(100000)));
        api.get("/api/accounts/cash-position", principal.accessToken())
                .andExpect(jsonPath("$.banks.length()").value(2));
        api.get("/api/dashboard", principal.accessToken())
                .andExpect(jsonPath("$.accounts.awaitingApproval").value(greaterThan(2)));
    }
}
