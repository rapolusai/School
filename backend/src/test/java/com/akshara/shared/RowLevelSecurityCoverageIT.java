package com.akshara.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akshara.attendance.ChildLeaveService;
import com.akshara.attendance.ChildLeaveService.Application;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileUploads;
import com.akshara.homework.HomeworkScope;
import com.akshara.homework.HomeworkService;
import com.akshara.homework.HomeworkService.HomeworkInput;
import com.akshara.homework.HomeworkViews.HomeworkDetail;
import com.akshara.homework.StudentHomeworkService;
import com.akshara.support.BillingFixtures;
import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Guards the database itself: every table that holds a school's data must have row-level security with the
 * tenant_isolation policy, so a table added by a future migration without it fails the build.
 */
class RowLevelSecurityCoverageIT extends IntegrationTest {

    /** Tables that are not owned by one school. Everything else must be isolated. */
    static final Set<String> NOT_SCHOOL_OWNED = Set.of("platform.tenant", "platform.platform_admin",
            "public.flyway_schema_history", "billing.subscription", "billing.invoice_counter");

    /** Tables whose isolation comes from a parent row instead of their own tenant_id. */
    static final Set<String> ISOLATED_THROUGH_PARENT = Set.of("identity.user_role");

    static final List<String> PHASE_ONE_TABLES = List.of("academics.academic_year", "academics.school_class",
            "academics.section", "academics.subject", "academics.class_subject", "students.student",
            "students.guardian", "students.student_guardian", "students.enrollment", "admissions.application",
            "admissions.application_guardian", "admissions.timeline_entry", "admissions.assessment_slot",
            "attendance.register", "attendance.entry", "notifications.message", "notifications.settings",
            "fees.fee_head", "fees.fee_structure", "fees.fee_instalment", "fees.fee_instalment_share",
            "fees.late_fee_rule", "fees.concession", "fees.concession_head", "fees.student_due",
            "fees.receipt_counter", "fees.receipt", "fees.payment_allocation", "fees.late_fee_waiver",
            "fees.payment_order", "fees.gateway_event", "fees.fee_reminder",
            "staff.department", "staff.staff_profile", "staff.leave_type", "staff.leave_balance",
            "staff.leave_request", "staff.attendance",
            "communication.calendar_entry", "communication.calendar_entry_class", "communication.circular",
            "communication.circular_target", "communication.circular_recipient", "communication.settings",
            "files.stored_file", "timetable.settings", "timetable.period", "timetable.teacher_assignment",
            "timetable.slot", "timetable.teacher_absence", "timetable.substitution", "homework.homework",
            "homework.homework_section", "homework.submission", "homework.settings", "billing.invoice",
            "billing.invoice_payment", "privacy.grievance_officer", "privacy.privacy_notice",
            "privacy.consent_record", "privacy.data_request", "privacy.request_event", "privacy.data_export",
            "attendance.leave_request");

    @Autowired
    HomeworkService homework;

    @Autowired
    StudentHomeworkService learners;

    @Autowired
    ChildLeaveService childLeave;

    /** The last day of the fixtures' current year, 2026-27. */
    static final LocalDate LAST_DAY = LocalDate.of(2027, 3, 31);

    /** Phase 0 foreign keys created before composite tenant keys were required. V1 cannot change. */
    static final Set<String> SINGLE_COLUMN_FK_ALLOWED = Set.of("identity.refresh_token");

    record TableInfo(String name, boolean rowSecurity, boolean hasTenantId, boolean hasPolicy, boolean partition,
            boolean appCanSelect, boolean appOwns) {
    }

    @Test
    void everySchoolOwnedTableHasRowLevelSecurity() throws Exception {
        List<TableInfo> tables = tables();
        assertThat(tables).extracting(TableInfo::name).containsAll(PHASE_ONE_TABLES);

        List<String> problems = new ArrayList<>();
        for (TableInfo t : tables) {
            if (t.appOwns()) {
                problems.add(t.name() + " is owned by the runtime role");
            }
            if (NOT_SCHOOL_OWNED.contains(t.name())) {
                continue;
            }
            if (t.partition()) {
                // A partition is read through its parent's policy; the runtime role must not reach it directly.
                if (t.appCanSelect() && !t.rowSecurity()) {
                    problems.add(t.name() + " is a partition the runtime role can read without row-level security");
                }
                continue;
            }
            if (!t.rowSecurity()) {
                problems.add(t.name() + " does not enable row-level security");
            }
            if (!t.hasPolicy()) {
                problems.add(t.name() + " has no tenant_isolation policy with USING and WITH CHECK");
            }
            if (!t.hasTenantId() && !ISOLATED_THROUGH_PARENT.contains(t.name())) {
                problems.add(t.name() + " has no tenant_id column");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void schoolDataIsInvisibleWithoutTheSchoolSelected() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        String yearId = fixtures.currentYear(admin);
        String classId = fixtures.schoolClass(admin, "Class 1");
        String section = fixtures.section(admin, classId, "A", 30);
        String subject = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"English"}"""), "$.id");
        api.put("/api/academics/classes/" + classId + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s"]}""".formatted(subject));
        String application = TestApi.read(api.post("/api/admissions/applications", admin.accessToken(), """
                {"stage":"APPLICATION","firstName":"Ravi","dateOfBirth":"2019-05-01","classId":"%s",
                 "academicYearId":"%s","source":"WALK_IN",
                 "guardians":[{"name":"Meena Rao","relation":"MOTHER","phone":"9876501002"}]}"""
                .formatted(classId, yearId)), "$.id");
        api.post("/api/admissions/applications/" + application + "/slots", admin.accessToken(), """
                {"kind":"TEST","scheduledAt":"%s","mode":"IN_PERSON","location":"Room 4"}"""
                .formatted(Instant.now().plus(Duration.ofDays(3))));
        String student = fixtures.student(admin, section, "RLS-1", "Asha", null, "Lata Rao", "9876501001");
        // An absence: a register, its entry and the alert it queues; and the school's message settings.
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        api.put("/api/attendance/registers/" + section + "/" + (day.isAfter(LAST_DAY) ? LAST_DAY : day),
                admin.accessToken(), """
                {"entries":[{"studentId":"%s","status":"ABSENT"}]}""".formatted(student))
                .andExpect(status().isOk());
        api.put("/api/notifications/settings", admin.accessToken(), """
                {"absenceAlertsEnabled":true,"absenceAlertChannel":"SMS","alertLanguage":"en",
                 "quietHoursEnabled":true,"quietHoursStart":"21:00","quietHoursEnd":"07:00"}""")
                .andExpect(status().isOk());
        feeRecords(admin, yearId, classId, student);
        // Staff records: a department, a profile, leave types, a balance, a leave request and a check-in.
        String department = TestApi.read(api.post("/api/staff/departments", admin.accessToken(), """
                {"name":"Primary"}""").andExpect(status().isCreated()), "$.id");
        String tara = TestApi.read(api.post("/api/staff", admin.accessToken(), """
                {"name":"Tara Teacher","email":"tara@%s.akshara.test","password":"%s","roles":["TEACHER"],
                 "employeeCode":"T-1","designation":"Teacher","departmentId":"%s","employmentType":"PERMANENT",
                 "dateOfJoining":"2020-06-01","mobile":"9876501003"}"""
                .formatted(school.code(), TestApi.PASSWORD, department)).andExpect(status().isCreated()), "$.userId");
        String casual = TestApi.read(api.post("/api/leave/types/standard", admin.accessToken(), null)
                .andExpect(status().isOk()), "$[0].id");
        api.put("/api/leave/balances", admin.accessToken(), """
                {"userId":"%s","leaveTypeId":"%s","opening":2,"accrued":12}""".formatted(tara, casual))
                .andExpect(status().isOk());
        Session taraSession = api.login(school.code(), "tara@" + school.code() + ".akshara.test", TestApi.PASSWORD);
        api.post("/api/leave/requests", taraSession.accessToken(), """
                {"leaveTypeId":"%s","fromDate":"2027-03-15","toDate":"2027-03-15","halfDay":false,
                 "reason":"Family function"}""".formatted(casual)).andExpect(status().isCreated());
        api.post("/api/staff-attendance/me/check-in", taraSession.accessToken(), null).andExpect(status().isOk());
        // A calendar entry for the class; a circular to the section's parents and the admins, sent (so it has
        // recipients); and the school's communication settings.
        api.post("/api/calendar/entries", admin.accessToken(), """
                {"kind":"EVENT","title":"Sports day","startsOn":"%s","audience":"CLASSES","classIds":["%s"]}"""
                .formatted(LAST_DAY, classId)).andExpect(status().isCreated());
        String circular = TestApi.read(api.post("/api/notices", admin.accessToken(), """
                {"title":"Welcome","body":"Welcome back.","category":"GENERAL",
                 "audience":{"sectionIds":["%s"],"roles":["PARENT","SCHOOL_ADMIN"]}}""".formatted(section)), "$.id");
        api.post("/api/notices/" + circular + "/submit", admin.accessToken(), "").andExpect(status().isOk());
        api.put("/api/notices/settings", admin.accessToken(), """
                {"teacherCircularsNeedApproval":true,"enquiryAckEnabled":true,"enquiryAckChannel":"SMS"}""")
                .andExpect(status().isOk());
        timetableAndHomework(school, admin, section, subject, student, day.isAfter(LAST_DAY) ? LAST_DAY : day);
        // The school's subscription invoice from Akshara and a payment against it.
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        billing.pay(root, school.tenantId(), billing.paying(root, school.tenantId(), "36"));
        Session lata = parentSignIn(school, admin, student);
        privacyRecords(admin, lata, student);
        childLeave(school, lata, student, day.isAfter(LAST_DAY) ? LAST_DAY : day);
        School other = api.signup();

        try (Connection app = appConnection(); Connection owner = ownerConnection()) {
            for (String table : PHASE_ONE_TABLES) {
                long owned = countAsOwner(owner, table, school.tenantId());
                assertThat(owned).as(table).isPositive();
                assertThat(count(app, table)).as(table + " without a school").isZero();
                select(app, school.tenantId());
                assertThat(count(app, table)).as(table + " in its school").isEqualTo(owned);
                select(app, other.tenantId());
                assertThat(count(app, table)).as(table + " in another school").isZero();
                // Writes aimed at the other school match nothing.
                try (PreparedStatement update = app.prepareStatement(
                        "update " + table + " set tenant_id = tenant_id where tenant_id = ?")) {
                    update.setObject(1, school.tenantId());
                    if (canUpdateTenantId(app, table)) {
                        assertThat(update.executeUpdate()).as(table).isZero();
                    } else {
                        // Append-only tables (ledgers, receipts) cannot be updated by the runtime role at all.
                        assertThatThrownBy(update::executeUpdate).as(table).hasMessageContaining("permission denied");
                    }
                }
                reset(app);
            }

            // Inserting a row for another school is refused by the policy's WITH CHECK.
            select(app, other.tenantId());
            assertThatThrownBy(() -> {
                try (PreparedStatement insert = app.prepareStatement("""
                        insert into academics.subject (id, tenant_id, name, created_at, updated_at)
                        values (?, ?, 'Intruder', now(), now())""")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, school.tenantId());
                    insert.executeUpdate();
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
        }
    }

    /**
     * Foreign key checks are not filtered by row-level security, so a key between two school-owned tables must
     * include tenant_id; otherwise a row could point at another school's row.
     */
    @Test
    void foreignKeysBetweenSchoolTablesIncludeTheSchool() throws Exception {
        List<String> problems = new ArrayList<>();
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement("""
                        select cn.nspname || '.' || c.relname, con.conname,
                               exists (select 1 from unnest(con.conkey) k
                                       join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k
                                       where a.attname = 'tenant_id')
                        from pg_constraint con
                        join pg_class c on c.oid = con.conrelid
                        join pg_namespace cn on cn.oid = c.relnamespace
                        join pg_class r on r.oid = con.confrelid
                        join pg_namespace rn on rn.oid = r.relnamespace
                        where con.contype = 'f'
                          and not (rn.nspname = 'platform' and r.relname = 'tenant')
                          and exists (select 1 from pg_attribute a
                                      where a.attrelid = con.conrelid and a.attname = 'tenant_id' and not a.attisdropped)
                          and exists (select 1 from pg_attribute a
                                      where a.attrelid = con.confrelid and a.attname = 'tenant_id' and not a.attisdropped)
                        order by 1, 2""");
                ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                if (!rs.getBoolean(3) && !SINGLE_COLUMN_FK_ALLOWED.contains(rs.getString(1))) {
                    problems.add(rs.getString(1) + "." + rs.getString(2) + " does not include tenant_id");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void aRowCannotPointAtAnotherSchoolsRow() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        String classId = fixtures.schoolClass(admin, "Class 2");
        School other = api.signup();

        try (Connection app = appConnection()) {
            select(app, other.tenantId());
            assertThatThrownBy(() -> {
                try (PreparedStatement insert = app.prepareStatement("""
                        insert into academics.section (id, tenant_id, class_id, name, created_at, updated_at)
                        values (?, ?, ?, 'X', now(), now())""")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, other.tenantId());
                    insert.setObject(3, UUID.fromString(classId));
                    insert.executeUpdate();
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("foreign key");
        }
    }

    /** One row in every fee table: setup, dues, a payment, a waiver, an online order with its event, a reminder. */
    private void feeRecords(Session admin, String yearId, String classId, String student) throws Exception {
        FeeFixtures fees = new FeeFixtures(api, mvc);
        Heads heads = fees.defaultHeads(admin);
        fees.publishedStructure(admin, yearId, classId, heads);
        api.put("/api/fees/late-fee-rule", admin.accessToken(), """
                {"mode":"FLAT","graceDays":1,"flatPaise":5000}""").andExpect(status().isOk());
        api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"SIBLING","mode":"PERCENT","percent":5,"headIds":["%s"],"reason":"Sibling"}"""
                .formatted(student, heads.tuition())).andExpect(status().isCreated());
        fees.cash(admin, student, 1_000_00);
        List<String> instalments = fees.instalmentIds(admin, student);
        api.post("/api/fees/students/" + student + "/late-fee-waivers", admin.accessToken(), """
                {"instalmentId":"%s","reason":"Kind"}""".formatted(instalments.getFirst())).andExpect(status().isOk());
        String order = TestApi.read(api.post("/api/fees/students/" + student + "/orders", admin.accessToken(), """
                {"instalmentIds":["%s"]}""".formatted(instalments.get(1))).andExpect(status().isCreated()),
                "$.gatewayOrderId");
        api.post("/api/payments/sandbox/orders/" + order + "/complete", admin.accessToken(), """
                {"outcome":"FAILURE"}""").andExpect(status().isOk());
        api.post("/api/fees/reminders", admin.accessToken(), """
                {"studentIds":["%s"]}""".formatted(student)).andExpect(jsonPath("$.requested").value(1));
    }

    /** One row in every privacy table: officer, notice, paper consent, and a parent's access request with its export. */
    /** The student's mother, Lata Rao, gets a sign-in. */
    private Session parentSignIn(School school, Session admin, String student) throws Exception {
        String guardian = TestApi.read(api.get("/api/students/" + student, admin.accessToken()), "$.guardians[0].id");
        String email = "lata@" + school.code() + ".akshara.test";
        api.post("/api/students/" + student + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email, TestApi.PASSWORD))
                .andExpect(status().isOk());
        return api.login(school.code(), email, TestApi.PASSWORD);
    }

    private void privacyRecords(Session admin, Session parent, String student) throws Exception {
        api.put("/api/privacy/grievance-officer", admin.accessToken(), """
                {"name":"Lata Iyer","email":"dpo@example.test","phone":"9876501003"}""").andExpect(status().isOk());
        api.post("/api/privacy/notice", admin.accessToken(), """
                {"bodyEn":"Notice","bodyHi":"सूचना"}""").andExpect(status().isCreated());
        api.post("/api/privacy/students/" + student + "/consents", admin.accessToken(), """
                {"givenByName":"Lata Rao","signedOn":"%s","photos":true,"whatsapp":true}"""
                .formatted(LocalDate.now(ZoneId.of("Asia/Kolkata")))).andExpect(status().isCreated());
        String request = TestApi.read(api.post("/api/me/privacy/requests", parent.accessToken(), """
                {"type":"ACCESS","subject":"CHILD","studentId":"%s"}""".formatted(student))
                .andExpect(status().isCreated()), "$.id");
        api.post("/api/privacy/requests/" + request + "/export", admin.accessToken(), null).andExpect(status().isOk());
    }

    private static boolean canUpdateTenantId(Connection connection, String table) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(
                "select has_column_privilege(current_user, ?, 'tenant_id', 'UPDATE')")) {
            s.setString(1, table);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /**
     * A bell schedule (every day a working day, so any date works), a teacher's period with an absence and its
     * substitute, and homework with a file answered by a student with a file.
     */
    private void timetableAndHomework(School school, Session admin, String section, String subject, String student,
            LocalDate day) throws Exception {
        String domain = "@" + school.code() + ".akshara.test";
        String teacher = api.createUser(admin, "Ravi Kumar", "ravi" + domain, List.of("TEACHER"));
        String substitute = api.createUser(admin, "Sita Devi", "sita" + domain, List.of("TEACHER"));
        api.put("/api/timetable/bell-schedule", admin.accessToken(), """
                {"workingDays":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"],
                 "weekday":[{"label":"Period 1","startsAt":"08:30","endsAt":"09:10"}]}""")
                .andExpect(status().isOk());
        api.post("/api/timetable/assignments", admin.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","teacherId":"%s","periodsPerWeek":5}"""
                .formatted(section, subject, teacher)).andExpect(status().isCreated());
        api.put("/api/timetable/sections/" + section, admin.accessToken(), """
                {"slots":[{"day":"%s","period":1,"subjectId":"%s"}]}""".formatted(day.getDayOfWeek(), subject))
                .andExpect(status().isOk());
        api.post("/api/timetable/substitutions/absences", admin.accessToken(), """
                {"date":"%s","teacherId":"%s"}""".formatted(day, teacher)).andExpect(status().isCreated());
        api.put("/api/timetable/substitutions", admin.accessToken(), """
                {"date":"%s","sectionId":"%s","period":1,"teacherId":"%s"}""".formatted(day, section, substitute))
                .andExpect(status().isOk());
        api.put("/api/homework/settings", admin.accessToken(), """
                {"remindersEnabled":true}""").andExpect(status().isOk());
        api.post("/api/students/" + student + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted("asha" + domain, TestApi.PASSWORD))
                .andExpect(status().isOk());
        UUID studentUser = UUID.fromString(TestApi.read(api.get("/api/me",
                api.login(school.code(), "asha" + domain, TestApi.PASSWORD).accessToken()), "$.id"));
        Instant at = day.atTime(10, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        Actor by = new Actor(null, "Test");
        TenantContext.runAs(school.tenantId(), () -> {
            HomeworkDetail hw = homework.create(HomeworkScope.WHOLE_SCHOOL, new HomeworkInput(
                    List.of(UUID.fromString(section)), UUID.fromString(subject), "Reading", "Page 4", day, day, true),
                    by, at, day);
            homework.addAttachment(HomeworkScope.WHOLE_SCHOOL, hw.id(), FileUploads.check("sheet.txt",
                    "Read page 4.".getBytes(StandardCharsets.UTF_8), "file"), by, day);
            learners.submit(studentUser, hw.id(), "Done", List.of(), List.of(FileUploads.check("answer.txt",
                    "I read it.".getBytes(StandardCharsets.UTF_8), "files")), new Actor(studentUser, "Asha"),
                    at.plusSeconds(3600));
            return null;
        });
    }

    /** The student's mother asks for a day of leave for her child. */
    private void childLeave(School school, Session lata, String student, LocalDate day) throws Exception {
        UUID parent = UUID.fromString(TestApi.read(api.get("/api/me", lata.accessToken()), "$.id"));
        LocalDate leaveDay = day.getDayOfWeek() == DayOfWeek.SUNDAY ? day.minusDays(1) : day;
        TenantContext.runAs(school.tenantId(), () -> childLeave.apply(parent, UUID.fromString(student),
                new Application(leaveDay, leaveDay, false, "Fever"), new Actor(parent, "Lata Rao"), day,
                Instant.now()));
    }

    private static List<TableInfo> tables() throws SQLException {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement("""
                        select n.nspname || '.' || c.relname,
                               c.relrowsecurity,
                               exists (select 1 from pg_attribute a
                                       where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped),
                               exists (select 1 from pg_policy p
                                       where p.polrelid = c.oid and p.polname = 'tenant_isolation'
                                         and p.polcmd = '*' and p.polqual is not null and p.polwithcheck is not null),
                               c.relispartition,
                               has_table_privilege(?, c.oid, 'select'),
                               pg_get_userbyid(c.relowner) = ?
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where c.relkind in ('r', 'p')
                          and n.nspname not like 'pg\\_%' and n.nspname <> 'information_schema'
                        order by 1""")) {
            s.setString(1, APP_USER);
            s.setString(2, APP_USER);
            List<TableInfo> tables = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    tables.add(new TableInfo(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4),
                            rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7)));
                }
            }
            return tables;
        }
    }

    private static void select(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            s.setString(1, tenantId.toString());
            s.execute();
        }
    }

    private static void reset(Connection connection) throws SQLException {
        connection.createStatement().execute("reset app.tenant_id");
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long countAsOwner(Connection owner, String table, UUID tenantId) throws SQLException {
        try (PreparedStatement s = owner.prepareStatement("select count(*) from " + table + " where tenant_id = ?")) {
            s.setObject(1, tenantId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
