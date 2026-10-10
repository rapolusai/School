package com.akshara.reports;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * A school for the dashboard and report tests, built through the API: a current year that started 150 days ago,
 * Class 5 (sections A and B) and Class 6 (section A), five students, two teachers with staff profiles (Ravi, class
 * teacher of 5 A, in Primary; Sita, class teacher of 5 B, in Middle), a principal, an accountant and front office.
 * Chitra was admitted today; everyone else the day after the year began.
 */
final class ReportsSchool {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    final TestApi api;
    final School school;
    final Session admin;
    final LocalDate today = LocalDate.now(INDIA);
    final LocalDate yearStart = today.minusDays(150);
    final String yearId;
    final String primary;
    final String middle;
    final String ravi;
    final String sita;
    final String class5;
    final String class6;
    final String s5A;
    final String s5B;
    final String s6A;
    final String asha;
    final String bala;
    final String chitra;
    final String dev;
    final String esha;
    final String maths;
    final String english;

    ReportsSchool(TestApi api) throws Exception {
        this.api = api;
        school = api.signup();
        admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        yearId = fixtures.year(admin, "This year", yearStart.toString(), today.plusDays(200).toString(), true);
        primary = department("Primary");
        middle = department("Middle");
        ravi = staff("Ravi Kumar", "ravi", primary, "T-01");
        sita = staff("Sita Devi", "sita", middle, "T-02");
        api.createUser(admin, "Lakshmi Iyer", email("principal"), List.of("PRINCIPAL"));
        api.createUser(admin, "Meena Reddy", email("accounts"), List.of("ACCOUNTANT"));
        api.createUser(admin, "Farah Khan", email("front"), List.of("FRONT_OFFICE"));
        maths = subject("Mathematics");
        english = subject("English");
        class5 = fixtures.schoolClass(admin, "Class 5");
        class6 = fixtures.schoolClass(admin, "Class 6");
        api.put("/api/academics/classes/" + class5 + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s","%s"]}""".formatted(maths, english)).andExpect(status().isOk());
        s5A = section(class5, "A", ravi);
        s5B = section(class5, "B", sita);
        s6A = section(class6, "A", null);
        LocalDate early = yearStart.plusDays(1);
        asha = student(s5A, "R-1", "Asha", 1, early);
        bala = student(s5A, "R-2", "Bala", 2, early);
        chitra = student(s5A, "R-3", "Chitra", 3, today);
        dev = student(s5B, "R-4", "Dev", 1, early);
        esha = student(s6A, "R-5", "Esha", 1, early);
    }

    Session login(String mailbox) throws Exception {
        return api.login(school.code(), email(mailbox), TestApi.PASSWORD);
    }

    String email(String mailbox) {
        return mailbox + "@" + school.code() + ".akshara.test";
    }

    /** The most recent day before {@code day} that is not a Sunday. */
    static LocalDate schoolDayBefore(LocalDate day) {
        LocalDate d = day.minusDays(1);
        return d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.minusDays(1) : d;
    }

    /** The Monday {@code weeks} weeks after the coming one. */
    LocalDate monday(int weeks) {
        return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(weeks);
    }

    /** Saves a register as the School Admin: student id to status. */
    void mark(String sectionId, LocalDate date, Map<String, String> marks) throws Exception {
        String entries = marks.entrySet().stream()
                .map(e -> "{\"studentId\":\"%s\",\"status\":\"%s\"}".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining(","));
        api.put("/api/attendance/registers/" + sectionId + "/" + date, admin.accessToken(),
                "{\"entries\":[" + entries + "]}").andExpect(status().isOk());
    }

    static Map<String, String> marks(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    /**
     * Three days of attendance. Two school days ago (d2) everyone was present. The last school day (d1): 5 A
     * present, absent (Bala), present; 5 B absent (Dev); 6 A half day. Today: 5 A present, absent (Bala), late; 5 B
     * not marked; 6 A on leave.
     */
    void threeDaysOfAttendance() throws Exception {
        LocalDate d1 = schoolDayBefore(today);
        LocalDate d2 = schoolDayBefore(d1);
        mark(s5A, d2, marks(asha, "PRESENT", bala, "PRESENT", chitra, "PRESENT"));
        mark(s5B, d2, marks(dev, "PRESENT"));
        mark(s6A, d2, marks(esha, "PRESENT"));
        mark(s5A, d1, marks(asha, "PRESENT", bala, "ABSENT", chitra, "PRESENT"));
        mark(s5B, d1, marks(dev, "ABSENT"));
        mark(s6A, d1, marks(esha, "HALF_DAY"));
        mark(s5A, today, marks(asha, "PRESENT", bala, "ABSENT", chitra, "LATE"));
        mark(s6A, today, marks(esha, "LEAVE"));
    }

    /** Gives the student a sign-in (mailbox = first name in lower case). */
    void studentSignIn(String studentId, String mailbox) throws Exception {
        api.post("/api/students/" + studentId + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email(mailbox), TestApi.PASSWORD))
                .andExpect(status().isOk());
    }

    String homework(Session who, List<String> sections, String subjectId, String title, LocalDate dueOn,
            boolean online) throws Exception {
        return TestApi.read(api.post("/api/homework", who.accessToken(), """
                {"sectionIds":[%s],"subjectId":"%s","title":"%s","instructions":"Page 12.",
                 "dueOn":"%s","onlineSubmission":%s}""".formatted(sections.stream().map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(",")), subjectId, title, dueOn, online))
                .andExpect(status().isCreated()), "$.id");
    }

    String application(String firstName, String classId, String stage, String source) throws Exception {
        return TestApi.read(api.post("/api/admissions/applications", admin.accessToken(), """
                {"stage":%s,"firstName":"%s","dateOfBirth":"2015-01-15","classId":"%s","academicYearId":"%s",
                 "source":"%s","guardians":[{"name":"Parent of %s","relation":"MOTHER","phone":"9700012345"}]}"""
                .formatted(stage == null ? "null" : "\"" + stage + "\"", firstName, classId, yearId, source,
                        firstName)).andExpect(status().isCreated()), "$.id");
    }

    void move(String applicationId, String stage) throws Exception {
        api.post("/api/admissions/applications/" + applicationId + "/stage", admin.accessToken(), """
                {"stage":"%s"}""".formatted(stage)).andExpect(status().isOk());
    }

    private String department(String name) throws Exception {
        return TestApi.read(api.post("/api/staff/departments", admin.accessToken(), """
                {"name":"%s"}""".formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private String staff(String name, String mailbox, String departmentId, String code) throws Exception {
        return TestApi.read(api.post("/api/staff", admin.accessToken(), """
                {"name":"%s","email":"%s","password":"%s","roles":["TEACHER"],"employeeCode":"%s",
                 "designation":"Teacher","departmentId":"%s","employmentType":"PERMANENT",
                 "dateOfJoining":"2020-01-06","mobile":"98480%05d"}""".formatted(name, email(mailbox),
                TestApi.PASSWORD, code, departmentId, code.hashCode() & 0xFFFF))
                .andExpect(status().isCreated()), "$.userId");
    }

    private String subject(String name) throws Exception {
        return TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"%s"}""".formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private String section(String classId, String name, String teacherId) throws Exception {
        return TestApi.read(api.post("/api/academics/classes/" + classId + "/sections", admin.accessToken(), """
                {"name":"%s","classTeacherId":%s}""".formatted(name, teacherId == null ? "null" : "\"" + teacherId
                + "\"")).andExpect(status().isCreated()), "$.id");
    }

    private String student(String sectionId, String admissionNo, String firstName, int rollNo, LocalDate admitted)
            throws Exception {
        return TestApi.read(api.post("/api/students", admin.accessToken(), """
                {"admissionNo":"%s","firstName":"%s","lastName":"Rao","dateOfBirth":"2015-04-12","gender":"FEMALE",
                 "admissionDate":"%s","sectionId":"%s","rollNo":%d,
                 "guardians":[{"name":"Guardian of %s","relation":"MOTHER","phone":"98765%05d","primary":true}]}
                """.formatted(admissionNo, firstName, admitted, sectionId, rollNo, firstName,
                admissionNo.hashCode() & 0xFFFF)).andExpect(status().isCreated()), "$.id");
    }
}
