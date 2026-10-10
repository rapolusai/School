package com.akshara.reports;

import static com.akshara.reports.ReportsSchool.marks;
import static com.akshara.reports.ReportsSchool.schoolDayBefore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/** The reports the hub adds: their numbers, filters, Excel exports, permissions and other schools' ids. */
class ReportsIT extends IntegrationTest {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private ReportsSchool s;
    private LocalDate d1;
    private LocalDate d2;

    @BeforeEach
    void school() throws Exception {
        s = new ReportsSchool(api);
        d1 = schoolDayBefore(s.today);
        d2 = schoolDayBefore(d1);
    }

    @Test
    void dailyAbsenteesAcrossTheSchool() throws Exception {
        s.threeDaysOfAttendance();
        Session principal = s.login("principal");
        String base = "/api/reports/attendance/absentees";

        api.get(base, principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(s.today.toString()))
                .andExpect(jsonPath("$.sectionCount").value(3))
                .andExpect(jsonPath("$.sectionsMarked").value(2))
                .andExpect(jsonPath("$.absent").value(1))
                .andExpect(jsonPath("$.onLeave").value(0))
                .andExpect(jsonPath("$.rows[*].fullName", contains("Bala Rao")))
                .andExpect(jsonPath("$.rows[0].className").value("Class 5"))
                .andExpect(jsonPath("$.rows[0].sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.rows[0].rollNo").value(2))
                .andExpect(jsonPath("$.rows[0].admissionNo").value("R-2"))
                .andExpect(jsonPath("$.rows[0].status").value("ABSENT"))
                // Absent on the last school day too.
                .andExpect(jsonPath("$.rows[0].daysInARow").value(2))
                .andExpect(jsonPath("$.rows[0].markedByName").value("Asha Admin"));
        api.get(base + "?includeLeave=true", principal.accessToken())
                .andExpect(jsonPath("$.rows[*].fullName", contains("Bala Rao", "Esha Rao")))
                .andExpect(jsonPath("$.rows[1].status").value("LEAVE"))
                .andExpect(jsonPath("$.onLeave").value(1));
        api.get(base + "?date=" + d1, principal.accessToken())
                .andExpect(jsonPath("$.rows[*].fullName", contains("Bala Rao", "Dev Rao")))
                .andExpect(jsonPath("$.rows[*].daysInARow", contains(1, 1)))
                .andExpect(jsonPath("$.sectionsMarked").value(3));
        api.get(base + "?includeLeave=true&classId=" + s.class6, principal.accessToken())
                .andExpect(jsonPath("$.rows[*].fullName", contains("Esha Rao")))
                .andExpect(jsonPath("$.sectionCount").value(1));

        // A class teacher sees only their own section.
        api.get(base + "?includeLeave=true", s.login("ravi").accessToken())
                .andExpect(jsonPath("$.rows[*].fullName", contains("Bala Rao")))
                .andExpect(jsonPath("$.sectionCount").value(1));
        api.get(base, s.login("sita").accessToken())
                .andExpect(jsonPath("$.rows", empty()))
                .andExpect(jsonPath("$.sectionCount").value(1))
                .andExpect(jsonPath("$.sectionsMarked").value(0));

        // Filters: no future days, and only this school's classes.
        api.get(base + "?date=" + s.today.plusDays(1), principal.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.date").exists());
        api.get(base + "?classId=" + otherSchoolClass(), principal.accessToken()).andExpect(status().isNotFound());
        api.get(base + "?classId=" + UUID.randomUUID(), principal.accessToken()).andExpect(status().isNotFound());
        api.get(base + "?date=yesterday", principal.accessToken()).andExpect(status().isBadRequest());
        Session accounts = s.login("accounts");
        api.get(base, accounts.accessToken()).andExpect(status().isForbidden());
        api.get(base + ".xlsx", accounts.accessToken()).andExpect(status().isForbidden());

        // The export: the same rows, school name, title and filters on top.
        byte[] file = download(base + ".xlsx?includeLeave=true", principal)
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"absentees-" + s.today + ".xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = XlsxTest.read(file).get("Absentees");
        assertThat(rows.get(0)).containsExactly("Test School " + s.school.code());
        assertThat(rows.get(1)).containsExactly("Daily absentees");
        assertThat(rows.get(2).getFirst()).contains("Class: All classes").contains("On leave: included");
        assertThat(rows.get(3).getFirst()).startsWith("Generated ").endsWith(" by Lakshmi Iyer");
        int header = rows.indexOf(List.of("Class", "Section", "Roll no", "Admission no", "Student", "Status",
                "Days in a row", "Marked by"));
        assertThat(header).isPositive();
        assertThat(rows.get(header + 1)).containsExactly("Class 5", "A", "2", "R-2", "Bala Rao", "Absent", "2",
                "Asha Admin");
        assertThat(rows.get(header + 2)).containsExactly("Class 6", "A", "1", "R-5", "Esha Rao", "On leave", "1",
                "Asha Admin");
        assertThat(rows.get(header + 3)).containsExactly("Absent", "", "", "", "1");
        assertThat(rows.get(header + 4)).containsExactly("On leave", "", "", "", "1");

        // The export is audited with its filters, never its rows.
        String audit = api.get("/api/audit-events?limit=20", s.admin.accessToken()).andReturn().getResponse()
                .getContentAsString();
        List<Map<String, Object>> exported = JsonPath.read(audit, "$[?(@.action == 'report.exported')]");
        assertThat(exported).hasSize(1);
        assertThat(exported.getFirst()).containsEntry("entityType", "report")
                .containsEntry("entityId", "attendance-absentees").containsEntry("actorName", "Lakshmi Iyer");
        assertThat(exported.getFirst().get("details").toString()).contains("includeLeave=true").contains("rows=2")
                .doesNotContain("Bala");
    }

    @Test
    void attendanceByClassAndSectionForADateRange() throws Exception {
        s.threeDaysOfAttendance();
        Session principal = s.login("principal");
        String base = "/api/reports/attendance/sections";
        String range = "?from=" + d2 + "&to=" + s.today;
        int schoolDays = s.today.getDayOfWeek() == DayOfWeek.SUNDAY ? 2 : 3;

        api.get(base + range, principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(d2.toString()))
                .andExpect(jsonPath("$.to").value(s.today.toString()))
                .andExpect(jsonPath("$.schoolDays").value(schoolDays))
                .andExpect(jsonPath("$.holidays").value(0))
                .andExpect(jsonPath("$.daysMarked").value(3))
                .andExpect(jsonPath("$.classes[*].className", contains("Class 5", "Class 6")))
                .andExpect(jsonPath("$.classes[0].sections[*].label", contains("Class 5 A", "Class 5 B")))
                // 5 A: P6 A2 L1 over three days.
                .andExpect(jsonPath("$.classes[0].sections[0].students").value(3))
                .andExpect(jsonPath("$.classes[0].sections[0].daysMarked").value(3))
                .andExpect(jsonPath("$.classes[0].sections[0].counts.present").value(6))
                .andExpect(jsonPath("$.classes[0].sections[0].counts.absent").value(2))
                .andExpect(jsonPath("$.classes[0].sections[0].counts.late").value(1))
                .andExpect(jsonPath("$.classes[0].sections[0].presentPercent").value(77.8))
                // 5 B: present then absent, not marked today.
                .andExpect(jsonPath("$.classes[0].sections[1].daysMarked").value(2))
                .andExpect(jsonPath("$.classes[0].sections[1].presentPercent").value(50.0))
                .andExpect(jsonPath("$.classes[0].presentPercent").value(72.7))
                .andExpect(jsonPath("$.classes[0].students").value(4))
                // 6 A: present, half day, leave.
                .andExpect(jsonPath("$.classes[1].sections[0].counts.halfDay").value(1))
                .andExpect(jsonPath("$.classes[1].sections[0].counts.leave").value(1))
                .andExpect(jsonPath("$.classes[1].presentPercent").value(50.0))
                .andExpect(jsonPath("$.students").value(5))
                .andExpect(jsonPath("$.counts.present").value(8))
                .andExpect(jsonPath("$.presentPercent").value(67.9));

        // This month to date by default; one class; a class teacher's own section.
        api.get(base, principal.accessToken())
                .andExpect(jsonPath("$.from").value(s.today.withDayOfMonth(1).toString()))
                .andExpect(jsonPath("$.to").value(s.today.toString()));
        api.get(base + range + "&classId=" + s.class6, principal.accessToken())
                .andExpect(jsonPath("$.classes[*].className", contains("Class 6")))
                .andExpect(jsonPath("$.students").value(1));
        api.get(base + range, s.login("ravi").accessToken())
                .andExpect(jsonPath("$.classes[*].sections[*].label", contains("Class 5 A")))
                .andExpect(jsonPath("$.presentPercent").value(77.8));

        // Bad ranges, another school's class, and people without attendance.read.
        api.get(base + "?from=" + s.today + "&to=" + d1, principal.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.to").exists());
        api.get(base + "?from=" + s.today.minusDays(400) + "&to=" + s.today, principal.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.to").exists());
        api.get(base + "?classId=" + otherSchoolClass(), principal.accessToken()).andExpect(status().isNotFound());
        api.get(base, s.login("front").accessToken()).andExpect(status().isForbidden());
        api.get(base + ".xlsx", s.login("front").accessToken()).andExpect(status().isForbidden());

        byte[] file = download(base + ".xlsx" + range, principal)
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"attendance-by-section-" + d2 + "-to-" + s.today + ".xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, List<List<String>>> book = XlsxTest.read(file);
        assertThat(book.keySet()).containsExactly("By section", "By class");
        List<List<String>> rows = book.get("By section");
        assertThat(rows.get(1)).containsExactly("Attendance by class and section");
        int header = rows.indexOf(List.of("Class", "Section", "Students", "Days marked", "Present", "Late",
                "Half day", "Absent", "Leave", "Attendance %"));
        assertThat(header).isPositive();
        assertThat(rows.get(header + 1)).containsExactly("Class 5", "A", "3", "3", "6", "1", "0", "2", "0", "77.8");
        assertThat(rows.get(header + 2)).containsExactly("Class 5", "B", "1", "2", "1", "0", "0", "1", "0", "50");
        assertThat(rows.get(header + 3)).containsExactly("Class 5 total", "", "4", "", "7", "1", "0", "3", "0",
                "72.7");
        assertThat(rows.get(header + 4)).containsExactly("Class 6", "A", "1", "3", "1", "0", "1", "0", "1", "50");
        assertThat(rows.getLast()).containsExactly("School total", "", "5", "3", "8", "1", "1", "3", "1", "67.9");
        assertThat(book.get("By class").getLast()).containsExactly("School total", "5", "8", "1", "1", "3", "1",
                "67.9");
    }

    @Test
    void homeworkCompletionBySectionAndSubject() throws Exception {
        Session ravi = s.login("ravi");
        String fractions = s.homework(ravi, List.of(s.s5A), s.maths, "Fractions", s.today.plusDays(2), true);
        String letter = s.homework(s.admin, List.of(s.s5A, s.s5B), s.english, "Letter", s.today.plusDays(3), true);
        s.homework(s.admin, List.of(s.s5B), s.maths, "Tables", s.today.plusDays(1), false);
        for (String[] student : new String[][] {{s.asha, "asha"}, {s.bala, "bala"}, {s.dev, "dev"}}) {
            s.studentSignIn(student[0], student[1]);
        }
        submit(fractions, s.login("asha"));
        submit(fractions, s.login("bala"));
        submit(letter, s.login("dev"));
        String tracker = api.get("/api/homework/" + fractions + "/submissions", ravi.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ashaSubmission = JsonPath.read(tracker, "$.rows[?(@.fullName == 'Asha Rao')].submissionId");
        api.put("/api/homework/" + fractions + "/submissions/" + ashaSubmission.getFirst() + "/review",
                ravi.accessToken(), """
                        {"status":"REVIEWED","grade":"A","remark":null}""").andExpect(status().isOk());

        String base = "/api/reports/homework/completion";
        String range = "?from=" + s.today.minusDays(30) + "&to=" + s.today.plusDays(7);
        api.get(base + range, s.admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].sectionLabel", contains("Class 5 A", "Class 5 A", "Class 5 B",
                        "Class 5 B")))
                .andExpect(jsonPath("$.rows[*].subjectName", contains("English", "Mathematics", "English",
                        "Mathematics")))
                .andExpect(jsonPath("$.rows[*].expected", contains(3, 3, 1, 0)))
                .andExpect(jsonPath("$.rows[*].submitted", contains(0, 2, 1, 0)))
                .andExpect(jsonPath("$.rows[1].reviewed").value(1))
                .andExpect(jsonPath("$.rows[1].waiting").value(1))
                .andExpect(jsonPath("$.rows[1].completionPercent").value(66.7))
                .andExpect(jsonPath("$.rows[2].completionPercent").value(100.0))
                .andExpect(jsonPath("$.rows[3].online").value(0))
                .andExpect(jsonPath("$.rows[3].homework").value(1))
                .andExpect(jsonPath("$.rows[3].completionPercent").value(nullValue()))
                // Each piece of homework once in the total, though "Letter" went to two sections.
                .andExpect(jsonPath("$.total.homework").value(3))
                .andExpect(jsonPath("$.total.online").value(2))
                .andExpect(jsonPath("$.total.expected").value(7))
                .andExpect(jsonPath("$.total.submitted").value(3))
                .andExpect(jsonPath("$.total.waiting").value(2))
                .andExpect(jsonPath("$.total.completionPercent").value(42.9));

        // Teachers see their own sections; filters narrow; the default is the last 30 days.
        api.get(base + range, ravi.accessToken())
                .andExpect(jsonPath("$.rows[*].sectionLabel", contains("Class 5 A", "Class 5 A")));
        api.get(base + range, s.login("sita").accessToken())
                .andExpect(jsonPath("$.rows[*].sectionLabel", contains("Class 5 B", "Class 5 B")))
                .andExpect(jsonPath("$.total.waiting").value(1));
        api.get(base + range + "&subjectId=" + s.maths, s.admin.accessToken())
                .andExpect(jsonPath("$.rows[*].sectionLabel", contains("Class 5 A", "Class 5 B")));
        api.get(base + range + "&classId=" + s.class6, s.admin.accessToken())
                .andExpect(jsonPath("$.rows", empty())).andExpect(jsonPath("$.total.homework").value(0));
        api.get(base, s.admin.accessToken())
                .andExpect(jsonPath("$.from").value(s.today.minusDays(30).toString()))
                .andExpect(jsonPath("$.to").value(s.today.toString()))
                .andExpect(jsonPath("$.rows", empty()));

        api.get(base + "?subjectId=" + otherSchoolSubject(), s.admin.accessToken()).andExpect(status().isNotFound());
        api.get(base + "?classId=" + UUID.randomUUID(), s.admin.accessToken()).andExpect(status().isNotFound());
        api.get(base + "?from=" + s.today + "&to=" + s.today.minusDays(1), s.admin.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.to").exists());
        api.get(base, s.login("accounts").accessToken()).andExpect(status().isForbidden());

        byte[] file = download(base + ".xlsx" + range, s.admin).andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = XlsxTest.read(file).get("Homework completion");
        int header = rows.indexOf(List.of("Class", "Section", "Subject", "Homework", "Online submission", "Expected",
                "Submitted", "Late", "Reviewed", "Needs redo", "Waiting for review", "Completion %"));
        assertThat(header).isPositive();
        assertThat(rows.get(header + 2)).containsExactly("Class 5", "Class 5 A", "Mathematics", "1", "1", "3", "2",
                "0", "1", "0", "1", "66.7");
        assertThat(rows.getLast()).containsExactly("Total", "", "", "3", "2", "7", "3", "0", "1", "0", "2", "42.9");
    }

    @Test
    void admissionsFunnelWithConversionRates() throws Exception {
        s.application("Aarav", s.class5, null, "WALK_IN");
        s.application("Bhavya", s.class5, "APPLICATION", "REFERRAL");
        String charan = s.application("Charan", s.class6, "APPLICATION", "WEBSITE");
        s.move(charan, "ASSESSMENT");
        String diya = s.application("Diya", s.class6, "APPLICATION", "REFERRAL");
        s.move(diya, "OFFERED");
        api.post("/api/admissions/applications/" + diya + "/admit", s.admin.accessToken(), """
                {"sectionId":"%s","admissionNo":"R-9","gender":"FEMALE"}""".formatted(s.s6A))
                .andExpect(status().isOk());
        String eshan = s.application("Eshan", s.class5, null, "PHONE");
        s.move(eshan, "APPLICATION");
        s.move(eshan, "REJECTED");
        String farah = s.application("Farah", s.class5, null, "WALK_IN");
        s.move(farah, "WITHDRAWN");

        Session front = s.login("front");
        String base = "/api/reports/admissions/funnel";
        api.get(base, front.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.academicYearId").value(nullValue()))
                .andExpect(jsonPath("$.funnel.total").value(6))
                .andExpect(jsonPath("$.funnel.stages[*].reached", contains(6, 4, 2, 1, 1)))
                .andExpect(jsonPath("$.funnel.stages[*].current", contains(1, 1, 1, 0, 1)))
                .andExpect(jsonPath("$.funnel.stages[0].fromPrevious").value(nullValue()))
                .andExpect(jsonPath("$.funnel.stages[1].fromPrevious").value(66.7))
                .andExpect(jsonPath("$.funnel.stages[2].fromPrevious").value(50.0))
                .andExpect(jsonPath("$.funnel.stages[4].fromPrevious").value(100.0))
                .andExpect(jsonPath("$.funnel.stages[*].fromEnquiry", contains(100.0, 66.7, 33.3, 16.7, 16.7)))
                .andExpect(jsonPath("$.funnel.open").value(3))
                .andExpect(jsonPath("$.funnel.rejected").value(1))
                .andExpect(jsonPath("$.funnel.withdrawn").value(1))
                .andExpect(jsonPath("$.funnel.conversionPercent").value(16.7))
                .andExpect(jsonPath("$.funnel.byClass[*].className", contains("Class 5", "Class 6")))
                .andExpect(jsonPath("$.funnel.byClass[0].total").value(4))
                .andExpect(jsonPath("$.funnel.byClass[0].applied").value(2))
                .andExpect(jsonPath("$.funnel.byClass[0].conversionPercent").value(0.0))
                .andExpect(jsonPath("$.funnel.byClass[1].assessed").value(2))
                .andExpect(jsonPath("$.funnel.byClass[1].admitted").value(1))
                .andExpect(jsonPath("$.funnel.byClass[1].conversionPercent").value(50.0))
                .andExpect(jsonPath("$.funnel.bySource[*].source", contains("WALK_IN", "WEBSITE", "PHONE", "REFERRAL")))
                .andExpect(jsonPath("$.funnel.bySource[*].total", contains(2, 1, 1, 2)));

        api.get(base + "?classId=" + s.class6, front.accessToken())
                .andExpect(jsonPath("$.funnel.stages[*].reached", contains(2, 2, 2, 1, 1)));
        api.get(base + "?source=REFERRAL", front.accessToken()).andExpect(jsonPath("$.funnel.total").value(2));
        api.get(base + "?yearId=" + s.yearId, front.accessToken())
                .andExpect(jsonPath("$.academicYearName").value("This year"))
                .andExpect(jsonPath("$.funnel.total").value(6));
        api.get(base + "?from=" + s.today.plusDays(1), front.accessToken())
                .andExpect(jsonPath("$.funnel.total").value(0))
                .andExpect(jsonPath("$.funnel.stages[1].fromPrevious").value(nullValue()));
        api.get(base + "?from=" + s.today + "&to=" + s.today, front.accessToken())
                .andExpect(jsonPath("$.funnel.total").value(6));
        api.get(base + "?from=" + s.today + "&to=" + s.today.minusDays(1), front.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.to").exists());
        api.get(base + "?yearId=" + otherSchoolYear(), front.accessToken()).andExpect(status().isNotFound());
        api.get(base + "?classId=" + otherSchoolClass(), front.accessToken()).andExpect(status().isNotFound());
        api.get(base + "?source=NEWSPAPER", front.accessToken()).andExpect(status().isBadRequest());
        api.get(base, s.login("ravi").accessToken()).andExpect(status().isForbidden());

        byte[] file = download(base + ".xlsx", front)
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"admissions-funnel-all-years.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, List<List<String>>> book = XlsxTest.read(file);
        assertThat(book.keySet()).containsExactly("Funnel", "By class", "By source");
        List<List<String>> funnel = book.get("Funnel");
        assertThat(funnel).contains(List.of("Enquiry", "6", "1", "", "100"),
                List.of("Application", "4", "1", "66.7", "66.7"), List.of("Admitted", "1", "1", "100", "16.7"),
                List.of("Applications", "6"));
        assertThat(book.get("By class")).contains(List.of("Class 6", "2", "2", "2", "1", "1", "0", "0", "50"));
        assertThat(book.get("By source")).contains(List.of("Walk-in", "2", "0", "0", "0", "0", "0", "1", "0"));
    }

    @Test
    void leaveTakenByStaffAndType() throws Exception {
        String body = api.post("/api/leave/types/standard", s.admin.accessToken(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String casual = type(body, "CL");
        String sick = type(body, "SL");
        String unpaid = type(body, "LOP");
        Session ravi = s.login("ravi");
        Session sita = s.login("sita");
        approve(apply(ravi, casual, s.monday(0)));
        approve(apply(ravi, unpaid, s.monday(1)));
        apply(sita, sick, s.monday(2));

        Session principal = s.login("principal");
        String base = "/api/reports/staff/leave";
        String report = api.get(base, principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.academicYearName").value("This year"))
                .andExpect(jsonPath("$.types[*].code", hasItem("CL")))
                .andExpect(jsonPath("$.types[-1].code").value("LOP"))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].total", contains(2.0)))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].lossOfPay", contains(1.0)))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].departmentName", contains("Primary")))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].employeeCode", contains("T-01")))
                .andExpect(jsonPath("$.staff[?(@.name == 'Sita Devi')].total", contains(0)))
                .andExpect(jsonPath("$.staff[?(@.name == 'Sita Devi')].pending", contains(1.0)))
                .andExpect(jsonPath("$.total").value(2.0))
                .andExpect(jsonPath("$.lossOfPay").value(1.0))
                .andExpect(jsonPath("$.pending").value(1.0))
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(report, "$.types[*].code");
        int cl = codes.indexOf("CL");
        assertThat(JsonPath.<List<Object>>read(report, "$.staff[?(@.name == 'Ravi Kumar')].days[" + cl + "]"))
                .containsExactly(1.0);
        assertThat(JsonPath.<Object>read(report, "$.typeTotals[" + cl + "]")).isEqualTo(1.0);
        // Everyone on the staff is listed, with or without leave.
        assertThat(JsonPath.<List<String>>read(report, "$.staff[*].name")).contains("Asha Admin", "Lakshmi Iyer",
                "Meena Reddy", "Farah Khan", "Ravi Kumar", "Sita Devi");

        api.get(base + "?departmentId=" + s.primary, principal.accessToken())
                .andExpect(jsonPath("$.staff[*].name", contains("Ravi Kumar")));
        api.get(base + "?leaveTypeId=" + casual, principal.accessToken())
                .andExpect(jsonPath("$.types[*].code", contains("CL")))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].total", contains(1.0)))
                .andExpect(jsonPath("$.staff[?(@.name == 'Ravi Kumar')].lossOfPay", contains(0)))
                .andExpect(jsonPath("$.pending").value(0));
        api.get(base + "?yearId=" + s.yearId, principal.accessToken()).andExpect(jsonPath("$.total").value(2.0));
        api.get(base + "?yearId=" + otherSchoolYear(), principal.accessToken()).andExpect(status().isNotFound());
        api.get(base + "?departmentId=" + UUID.randomUUID(), principal.accessToken())
                .andExpect(status().isNotFound());
        api.get(base + "?leaveTypeId=" + UUID.randomUUID(), principal.accessToken())
                .andExpect(status().isNotFound());
        api.get(base, ravi.accessToken()).andExpect(status().isForbidden());
        api.get(base + ".xlsx", ravi.accessToken()).andExpect(status().isForbidden());

        byte[] file = download(base + ".xlsx", principal)
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"leave-taken-this-year.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = XlsxTest.read(file).get("Leave taken");
        List<String> header = rows.stream().filter(r -> !r.isEmpty() && r.getFirst().equals("Staff")).findFirst()
                .orElseThrow();
        assertThat(header.subList(0, 3)).containsExactly("Staff", "Employee code", "Department");
        assertThat(header.subList(header.size() - 3, header.size())).containsExactly("Total", "Loss of pay",
                "Pending");
        List<String> raviRow = rows.stream().filter(r -> !r.isEmpty() && r.getFirst().equals("Ravi Kumar"))
                .findFirst().orElseThrow();
        assertThat(raviRow.subList(raviRow.size() - 3, raviRow.size())).containsExactly("2", "1", "0");
        assertThat(raviRow.get(3 + cl)).isEqualTo("1");
        assertThat(rows.getLast().getFirst()).isEqualTo("Total");
        assertThat(rows.getLast().subList(rows.getLast().size() - 3, rows.getLast().size()))
                .containsExactly("2", "1", "1");
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions download(String path, Session who) throws Exception {
        return api.get(path, who.accessToken()).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private void submit(String homeworkId, Session student) throws Exception {
        mvc.perform(MockMvcRequestBuilders.multipart("/api/me/homework/" + homeworkId + "/submission")
                .param("text", "Done.")
                .header("Authorization", "Bearer " + student.accessToken()))
                .andExpect(status().is2xxSuccessful());
    }

    private String apply(Session who, String typeId, LocalDate day) throws Exception {
        return TestApi.read(api.post("/api/leave/requests", who.accessToken(), """
                {"leaveTypeId":"%s","fromDate":"%s","toDate":"%s","halfDay":false,"reason":"Family function"}"""
                .formatted(typeId, day, day)).andExpect(status().isCreated()), "$.id");
    }

    private void approve(String requestId) throws Exception {
        api.post("/api/leave/requests/" + requestId + "/approve", s.admin.accessToken(), null)
                .andExpect(status().isOk());
    }

    private static String type(String body, String code) {
        List<String> ids = JsonPath.read(body, "$[?(@.code == '" + code + "')].id");
        return ids.getFirst();
    }

    private Other other;

    private record Other(String yearId, String classId, String subjectId) {
    }

    /** A class, a year and a subject of a second school, created once per test when needed. */
    private Other other() throws Exception {
        if (other == null) {
            TestApi.School school = api.signup();
            Session admin = api.login(school);
            SchoolFixtures fixtures = new SchoolFixtures(api);
            String year = fixtures.year(admin, "Other year", s.today.minusDays(100).toString(),
                    s.today.plusDays(200).toString(), true);
            String classId = fixtures.schoolClass(admin, "Class 5");
            String subject = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                    {"name":"Mathematics"}""").andExpect(status().isCreated()), "$.id");
            String section = fixtures.section(admin, classId, "A", 30);
            String student = fixtures.student(admin, section, "O-1", "Xavier", "Paul", "Mary Paul", "9811100001");
            api.put("/api/attendance/registers/" + section + "/" + s.today, admin.accessToken(), """
                    {"entries":[{"studentId":"%s","status":"ABSENT"}]}""".formatted(student))
                    .andExpect(status().isOk());
            other = new Other(year, classId, subject);
        }
        return other;
    }

    private String otherSchoolClass() throws Exception {
        return other().classId();
    }

    private String otherSchoolYear() throws Exception {
        return other().yearId();
    }

    private String otherSchoolSubject() throws Exception {
        return other().subjectId();
    }

    @Test
    void anotherSchoolsAbsenteesNeverShow() throws Exception {
        s.mark(s.s5A, s.today, marks(s.asha, "ABSENT", s.bala, "PRESENT", s.chitra, "PRESENT"));
        other();
        api.get("/api/reports/attendance/absentees", s.admin.accessToken())
                .andExpect(jsonPath("$.rows[*].fullName", contains("Asha Rao")))
                .andExpect(jsonPath("$.absent").value(1));
        api.get("/api/reports/attendance/sections", s.admin.accessToken())
                .andExpect(jsonPath("$.counts.absent").value(1))
                .andExpect(jsonPath("$.students").value(5))
                .andExpect(jsonPath("$.classes[*].sections[*]", hasSize(3)));
    }
}
