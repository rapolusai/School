package com.akshara.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    DemoPrivacyData demoPrivacy;

    @Test
    void seedsADemoSchoolWithClassesAndStudentsOnce() throws Exception {
        DemoDataSeeder seeder = new DemoDataSeeder(provisioning, tenants, users, passwordEncoder, DEMO_PASSWORD,
                academics, students, transactionManager, admissions, attendanceData, demoFees, demoPrivacy);
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

        // Data protection: notice version 1 with the principal as grievance officer, the demo parent's consent for
        // both children, and one open access request about Arjun.
        Session principal = api.login(code, "principal" + DemoDataSeeder.DEMO_DOMAIN, DEMO_PASSWORD);
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
    }
}
