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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.attendance.ChildLeaveService;
import com.akshara.attendance.ChildLeaveService.Application;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileUploads;
import com.akshara.homework.HomeworkScope;
import com.akshara.homework.HomeworkService;
import com.akshara.homework.HomeworkService.HomeworkInput;
import com.akshara.homework.HomeworkViews.HomeworkDetail;
import com.akshara.homework.StudentHomeworkService;
import com.akshara.support.AccountsFixtures;
import com.akshara.support.InventoryFixtures;
import com.akshara.support.BillingFixtures;
import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/**
 * Guards the database itself: every table that holds a school's data must have row-level security with the
 * tenant_isolation policy, so a table added by a future migration without it fails the build.
 */
class RowLevelSecurityCoverageIT extends IntegrationTest {

    /** Tables that are not owned by one school. Everything else must be isolated. */
    static final Set<String> NOT_SCHOOL_OWNED = Set.of("platform.tenant", "platform.platform_admin",
            "public.flyway_schema_history", "billing.subscription", "billing.invoice_counter",
            "payroll.statutory_rate", "payroll.tax_rule", "payroll.tax_slab", "payroll.pt_slab_default");

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
            "attendance.leave_request",
            "library.settings", "library.receipt_counter", "library.borrowing_rule", "library.title", "library.copy",
            "library.loan", "library.fine", "library.reservation", "library.stock_check", "library.stock_check_item",
            "payroll.settings", "payroll.pt_slab", "payroll.salary_component", "payroll.salary_structure",
            "payroll.salary_structure_line", "payroll.staff_pay_profile", "payroll.payroll_run", "payroll.payslip",
            "payroll.payslip_line", "payroll.adjustment",
            "exams.grading_scheme", "exams.grade_band", "exams.settings", "exams.class_scheme", "exams.term",
            "exams.exam", "exams.exam_class", "exams.paper", "exams.mark_sheet", "exams.mark", "exams.term_record",
            "exams.hpc_entry",
            "chat.settings", "chat.staff_away", "chat.conversation", "chat.message", "chat.report", "chat.ptm_event",
            "chat.ptm_section", "chat.ptm_teacher", "chat.ptm_slot", "chat.ptm_booking",
            "lessons.settings", "lessons.syllabus", "lessons.syllabus_unit", "lessons.syllabus_topic",
            "lessons.lesson_plan", "lessons.material", "lessons.question", "lessons.question_option",
            "lessons.online_test", "lessons.test_section", "lessons.test_question", "lessons.test_attempt",
            "lessons.test_answer",
            "transport.settings", "transport.fee_zone", "transport.vehicle", "transport.crew_member",
            "transport.route", "transport.stop", "transport.allocation", "transport.trip", "transport.trip_event",
            "transport.position",
            "certificates.settings", "certificates.template", "certificates.serial_counter",
            "certificates.certificate", "certificates.id_card",
            "frontoffice.visitor", "frontoffice.blocked_visitor", "frontoffice.gate_pass", "frontoffice.pickup_person",
            "frontoffice.call_log", "frontoffice.enquiry", "frontoffice.courier_item",
            "accounts.settings", "accounts.approver", "accounts.head", "accounts.cost_centre", "accounts.vendor",
            "accounts.bank_account", "accounts.opening_balance", "accounts.number_counter",
            "accounts.recurring_expense", "accounts.expense", "accounts.expense_approval",
            "accounts.petty_cash_entry", "accounts.income_receipt", "accounts.fee_income_day",
            "accounts.payroll_cost", "accounts.budget", "accounts.budget_month", "accounts.bank_statement_line",
            "inventory.purchase_approver", "inventory.category", "inventory.location", "inventory.item",
            "inventory.number_counter", "inventory.requisition", "inventory.requisition_line",
            "inventory.purchase_approval", "inventory.rfq", "inventory.quote", "inventory.purchase_order",
            "inventory.purchase_order_line", "inventory.goods_receipt", "inventory.goods_receipt_line",
            "inventory.stock_take", "inventory.stock_take_line", "inventory.stock_movement", "inventory.asset",
            "inventory.asset_transfer", "inventory.verification_round", "inventory.verification_item");

    @Autowired
    HomeworkService homework;

    @Autowired
    StudentHomeworkService learners;

    @Autowired
    ChildLeaveService childLeave;

    @Autowired
    ApplicationEventPublisher events;

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
        libraryRecords(admin, student, tara);
        payrollRecords(admin, tara);
        examRecords(admin, yearId, classId, section, subject, student);
        chatRecords(school, admin, lata, student, section);
        lessonRecords(school, admin, classId, section, subject);
        transportRecords(admin, student, tara);
        certificateRecords(admin, student, section);
        frontOfficeRecords(admin, lata, student);
        accountsRecords(school, admin);
        inventoryRecords(admin, tara);
        School other = api.signup();

        try (Connection app = appConnection(); Connection owner = ownerConnection()) {
            for (String table : PHASE_ONE_TABLES) {
                long owned = countAsOwner(owner, table, school.tenantId());
                assertThat(owned).as(table).isPositive();
                assertThat(count(app, table)).as(table + " without a school").isZero();
                select(app, school.tenantId());
                assertThat(count(app, table)).as(table + " in its school").isEqualTo(owned);
                select(app, other.tenantId());
                // Only the other school's own rows: none, except what every school starts with (grading schemes).
                assertThat(count(app, table)).as(table + " in another school")
                        .isEqualTo(countAsOwner(owner, table, other.tenantId()));
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

    /**
     * One row in every library table: settings and a borrowing rule, a title with its copy, a loan returned late (a
     * fine, paid, so a receipt number), the copy lent again with a member of staff waiting for it, and a stock check
     * that saw the copy.
     */
    /**
     * One row in every certificates table: the settings, a template, a bonafide certificate prepared and signed (so
     * a serial counter), and an ID card for the student.
     */
    private void certificateRecords(Session admin, String student, String section) throws Exception {
        api.put("/api/certificates/settings", admin.accessToken(), """
                {"headerName":"RLS School","signatoryName":"Head","signatoryDesignation":"Principal",
                 "tcDuesCheck":true,"cardValidMonths":12,"lostCardFeePaise":0}""").andExpect(status().isOk());
        api.put("/api/certificates/templates/BONAFIDE", admin.accessToken(), """
                {"title":"Bonafide Certificate","body":"{student} of {class} is a student here.","serialPrefix":"BC"}""")
                .andExpect(status().isOk());
        String certificate = TestApi.read(api.post("/api/certificates", admin.accessToken(), """
                {"type":"BONAFIDE","studentId":"%s","purpose":"Bank account"}""".formatted(student))
                .andExpect(status().isCreated()), "$.id");
        api.post("/api/certificates/" + certificate + "/sign", admin.accessToken(), "").andExpect(status().isOk());
        api.post("/api/certificates/id-cards", admin.accessToken(), """
                {"subjectType":"STUDENT","studentId":"%s","bloodGroup":"O+"}""".formatted(student))
                .andExpect(status().isCreated());
    }

    private void libraryRecords(Session admin, String student, String staffUserId) throws Exception {
        api.put("/api/library/settings", admin.accessToken(), """
                {"accessionPrefix":"RLS","holdDays":3,"remindersEnabled":false}""").andExpect(status().isOk());
        api.put("/api/library/rules/STUDENT", admin.accessToken(), """
                {"maxBooks":2,"loanDays":7,"maxRenewals":1,"finePerDayPaise":100,"maxFinePaise":5000}""")
                .andExpect(status().isOk());
        String title = TestApi.read(api.post("/api/library/titles", admin.accessToken(), """
                {"title":"The Jungle Book","authors":"Rudyard Kipling","copies":1}""")
                .andExpect(status().isCreated()), "$.id");
        String accession = TestApi.read(api.get("/api/library/titles/" + title, admin.accessToken()),
                "$.copies[0].accessionNo");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        api.post("/api/library/loans", admin.accessToken(), """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s","issuedOn":"%s"}"""
                .formatted(accession, student, today.minusDays(30))).andExpect(status().isCreated());
        String fine = TestApi.read(api.post("/api/library/returns", admin.accessToken(), """
                {"accessionNo":"%s"}""".formatted(accession)).andExpect(status().isOk()), "$.fines[0].id");
        api.post("/api/library/fines/collect", admin.accessToken(), """
                {"fineIds":["%s"],"mode":"CASH"}""".formatted(fine)).andExpect(status().isOk());
        api.post("/api/library/loans", admin.accessToken(), """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(accession, student))
                .andExpect(status().isCreated());
        api.post("/api/library/reservations", admin.accessToken(), """
                {"titleId":"%s","memberType":"STAFF","memberId":"%s"}""".formatted(title, staffUserId))
                .andExpect(status().isCreated());
        String check = TestApi.read(api.post("/api/library/stock-checks", admin.accessToken(), "{}")
                .andExpect(status().isCreated()), "$.id");
        api.post("/api/library/stock-checks/" + check + "/scans", admin.accessToken(), """
                {"accessionNo":"%s"}""".formatted(accession)).andExpect(status().isOk());
    }

    /**
     * One row in every chat table: the school's message hours, a teacher's away note, Lata's conversation with her
     * daughter's teacher with a message and its attachment, her report of it, and a PTM for the section (its class,
     * teachers and slots) with the slot she booked.
     */
    private void chatRecords(School school, Session admin, Session lata, String student, String section)
            throws Exception {
        api.put("/api/chat/settings", admin.accessToken(), """
                {"availableDays":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],"availableFrom":"08:30",
                 "availableTo":"17:00","notifyNewMessages":true}""").andExpect(status().isOk());
        Session tara = api.login(school.code(), "tara@" + school.code() + ".akshara.test", TestApi.PASSWORD);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        api.put("/api/chat/away", tara.accessToken(), """
                {"returnsOn":"%s","note":"On training"}""".formatted(today.plusDays(5))).andExpect(status().isOk());
        String teacher = TestApi.read(api.get("/api/me/chat/contacts", lata.accessToken()), "$.contacts[0].personId");
        String conversation = TestApi.read(api.post("/api/me/chat/conversations", lata.accessToken(), """
                {"studentId":"%s","teacherUserId":"%s"}""".formatted(student, teacher))
                .andExpect(status().isCreated()), "$.id");
        mvc.perform(MockMvcRequestBuilders.multipart("/api/me/chat/conversations/" + conversation + "/messages")
                .file(new MockMultipartFile("files", "note.txt", "text/plain",
                        "Asha's note".getBytes(StandardCharsets.UTF_8)))
                .param("body", "Hello")
                .header("Authorization", "Bearer " + lata.accessToken()))
                .andExpect(status().isCreated());
        api.post("/api/me/chat/conversations/" + conversation + "/reports", lata.accessToken(), """
                {"reason":"Please look at this"}""").andExpect(status().isCreated());
        api.put("/api/ptm/settings", admin.accessToken(), """
                {"remindersEnabled":true}""").andExpect(status().isOk());
        api.post("/api/ptm/events", admin.accessToken(), """
                {"title":"PTM","date":"%s","startsAt":"09:00","endsAt":"10:00","slotMinutes":15,"mode":"IN_PERSON",
                 "sectionIds":["%s"],"includeSubjectTeachers":true}""".formatted(today.plusDays(7), section))
                .andExpect(status().isCreated());
        String slot = TestApi.read(api.get("/api/me/ptm", lata.accessToken()),
                "$.children[0].events[0].teachers[0].slots[0].id");
        api.post("/api/me/ptm/bookings", lata.accessToken(), """
                {"slotId":"%s","studentId":"%s"}""".formatted(slot, student)).andExpect(status().isCreated());
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

    /**
     * Payroll: settings, the school's own professional tax slabs, the standard components, Tara's pay details and
     * salary structure, this month's run (payslip and lines) and an adjustment in it.
     */
    private void payrollRecords(Session admin, String tara) throws Exception {
        String month = YearMonth.now(ZoneId.of("Asia/Kolkata")).toString();
        api.put("/api/payroll/settings", admin.accessToken(), """
                {"payDay":1,"prorationBasis":"CALENDAR_DAYS","pfEnabled":true,"pfOnFullWages":false,
                 "esiEnabled":true,"ptEnabled":true,"ptState":"TG"}""").andExpect(status().isOk());
        api.put("/api/payroll/settings/pt-slabs/TG", admin.accessToken(), """
                {"effectiveFrom":"2025-04-01","slabs":[{"appliesTo":"ALL","fromPaise":0,"toPaise":1500000,
                 "amountPaise":0},{"appliesTo":"ALL","fromPaise":1500001,"amountPaise":20000}]}""")
                .andExpect(status().isOk());
        String basic = TestApi.read(api.post("/api/payroll/components/standard", admin.accessToken(), null)
                .andExpect(status().isOk()), "$[?(@.code == 'BASIC')].id").replaceAll("[\\[\\]\"]", "");
        api.put("/api/payroll/staff/" + tara + "/pay-profile", admin.accessToken(), """
                {"bankAccountNumber":"000000123456","bankIfsc":"AKSH0000001","pan":"ABCDE1234F",
                 "pfMember":true,"ptWomenSlab":false}""").andExpect(status().isOk());
        api.post("/api/payroll/staff/" + tara + "/structures", admin.accessToken(), """
                {"effectiveFrom":"%s","lines":[{"componentId":"%s","monthlyPaise":3000000}]}"""
                .formatted(month, basic)).andExpect(status().isCreated());
        String run = TestApi.read(api.post("/api/payroll/runs", admin.accessToken(), """
                {"month":"%s"}""".formatted(month)).andExpect(status().isCreated()), "$.id");
        api.post("/api/payroll/runs/" + run + "/adjustments", admin.accessToken(), """
                {"userId":"%s","kind":"BONUS","amountPaise":100000,"reason":"Festival bonus"}""".formatted(tara))
                .andExpect(status().isCreated());
    }

    /**
     * Exam records: the built-in grading schemes (added at sign-up) given to the class, the settings, a term with an
     * exam for the class and its English paper, the student's marks, and the term record with the holistic progress
     * card (Class 1 has one).
     */
    private void examRecords(Session admin, String yearId, String classId, String section, String subject,
            String student) throws Exception {
        List<String> schemes = JsonPath.read(api.get("/api/exams/grading-schemes", admin.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$[?(@.kind == 'SCHOLASTIC')].id");
        String scheme = schemes.getFirst();
        api.put("/api/exams/class-schemes?yearId=" + yearId, admin.accessToken(), """
                {"classIds":["%s"],"schemeId":"%s"}""".formatted(classId, scheme)).andExpect(status().isOk());
        api.put("/api/exams/settings", admin.accessToken(), """
                {"ranksEnabled":true,"hpcUpToLevel":5}""").andExpect(status().isOk());
        String term = TestApi.read(api.post("/api/exams/terms", admin.accessToken(), """
                {"academicYearId":"%s","name":"Term 1","startsOn":"2026-06-01","endsOn":"2026-12-31"}"""
                .formatted(yearId)).andExpect(status().isCreated()), "$.terms[0].id");
        String exam = TestApi.read(api.post("/api/exams", admin.accessToken(), """
                {"termId":"%s","name":"Unit Test 1","classIds":["%s"]}""".formatted(term, classId))
                .andExpect(status().isCreated()), "$.terms[0].exams[0].id");
        String paper = TestApi.read(api.put("/api/exams/" + exam + "/classes/" + classId + "/papers",
                admin.accessToken(), """
                {"papers":[{"subjectId":"%s","maxTheory":40}]}""".formatted(subject))
                .andExpect(status().isOk()), "$.papers[0].id");
        api.put("/api/exams/papers/" + paper + "/sections/" + section + "/marks", admin.accessToken(), """
                {"version":null,"entries":[{"studentId":"%s","theory":31}]}""".formatted(student))
                .andExpect(status().isOk());
        api.put("/api/exams/terms/" + term + "/sections/" + section + "/records", admin.accessToken(), """
                {"entries":[{"studentId":"%s","workEducation":"A","remark":"Reads well.","language":"PROFICIENT"}]}"""
                .formatted(student)).andExpect(status().isOk());
    }

    /**
     * Lessons: the settings, a syllabus with a unit and topic, a lesson plan, study material, a bank question with
     * its options, and a published online test for the section (its question) that the student has started.
     */
    private void lessonRecords(School school, Session admin, String classId, String section, String subject)
            throws Exception {
        api.put("/api/lessons/settings", admin.accessToken(), """
                {"testNotificationsEnabled":true}""").andExpect(status().isOk());
        String topic = TestApi.read(api.post("/api/lessons/syllabi", admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","units":[{"title":"Letters","topics":[{"title":"Vowels",
                 "plannedPeriods":2,"targetMonth":"2026-07"}]}]}""".formatted(classId, subject))
                .andExpect(status().isCreated()), "$.units[0].topics[0].id");
        api.post("/api/lessons/plans", admin.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","topicId":"%s","weekStart":"2026-07-06","periods":2}"""
                .formatted(section, subject, topic)).andExpect(status().isCreated());
        api.post("/api/lessons/material", admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Vowel chart","publishOn":"2026-07-01"}"""
                .formatted(classId, subject)).andExpect(status().isCreated());
        String question = TestApi.read(api.post("/api/lessons/questions", admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","type":"MCQ_SINGLE","difficulty":"EASY","text":"Which is a vowel?",
                 "marks":1,"options":[{"text":"A","correct":true},{"text":"B","correct":false}]}"""
                .formatted(classId, subject)).andExpect(status().isCreated()), "$.id");
        Instant now = Instant.now();
        String test = TestApi.read(api.post("/api/lessons/tests", admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","sectionIds":["%s"],"title":"Vowels quiz","opensAt":"%s",
                 "closesAt":"%s","durationMinutes":10}""".formatted(classId, subject, section,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)))).andExpect(status().isCreated()), "$.id");
        api.put("/api/lessons/tests/" + test + "/questions", admin.accessToken(), """
                {"questions":[{"questionId":"%s"}]}""".formatted(question)).andExpect(status().isOk());
        api.post("/api/lessons/tests/" + test + "/publish", admin.accessToken(), null).andExpect(status().isOk());
        Session asha = api.login(school.code(), "asha@" + school.code() + ".akshara.test", TestApi.PASSWORD);
        api.post("/api/me/tests/" + test + "/attempts", asha.accessToken(), null).andExpect(status().isOk());
    }

    /**
     * One row in every transport table: the alert settings, a fee zone, a vehicle, a member of staff as its driver, a
     * route with a stop in the zone, the student's place on it, and today's morning trip with a mark and a position.
     */
    private void transportRecords(Session admin, String student, String staffUserId) throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        api.put("/api/transport/settings", admin.accessToken(), """
                {"boardedAlerts":true,"droppedAlerts":true,"approachingAlerts":false,"delayAlerts":true}""")
                .andExpect(status().isOk());
        String zone = TestApi.read(api.post("/api/transport/fee-zones", admin.accessToken(), """
                {"name":"Zone A","minKm":0,"maxKm":5,"annualFeePaise":1200000}""")
                .andExpect(status().isCreated()), "$.id");
        String vehicle = TestApi.read(api.post("/api/transport/vehicles", admin.accessToken(), """
                {"registrationNo":"TS09 UB 4410","type":"BUS","seats":40,"fitnessExpiry":"%s"}"""
                .formatted(today.plusYears(1))).andExpect(status().isCreated()), "$.id");
        String driver = TestApi.read(api.post("/api/transport/crew", admin.accessToken(), """
                {"userId":"%s","kind":"DRIVER","licenceNo":"TS09 20150001234","licenceExpiry":"%s"}"""
                .formatted(staffUserId, today.plusYears(3))).andExpect(status().isCreated()), "$.id");
        String route = TestApi.read(api.post("/api/transport/routes", admin.accessToken(), """
                {"name":"Route 1","vehicleId":"%s","driverId":"%s"}""".formatted(vehicle, driver))
                .andExpect(status().isCreated()), "$.id");
        String stop = TestApi.read(api.put("/api/transport/routes/" + route + "/stops", admin.accessToken(), """
                {"stops":[{"name":"Gate Stop","latitude":17.41,"longitude":78.44,"feeZoneId":"%s"}]}"""
                .formatted(zone)).andExpect(status().isOk()), "$.stops[0].id");
        api.post("/api/transport/allocations", admin.accessToken(), """
                {"studentId":"%s","routeId":"%s","stopId":"%s","startDate":"%s"}"""
                .formatted(student, route, stop, today)).andExpect(status().isCreated());
        String trip = TestApi.read(api.post("/api/transport/routes/" + route + "/trips", admin.accessToken(), """
                {"kind":"MORNING"}""").andExpect(status().isCreated()), "$.id");
        api.put("/api/transport/trips/" + trip + "/students/" + student, admin.accessToken(), """
                {"status":"BOARDED"}""").andExpect(status().isOk());
        api.post("/api/transport/trips/" + trip + "/positions", admin.accessToken(), """
                {"latitude":17.41,"longitude":78.44}""").andExpect(status().isOk());
    }

    /** One row in each front office table: a visitor, a blocklist entry, a gate pass, a pickup person, a call, an
     * enquiry and a courier item. */
    private void frontOfficeRecords(Session admin, Session lata, String student) throws Exception {
        api.post("/api/frontoffice/visitors", admin.accessToken(), """
                {"name":"Prakash Nair","phone":"9876511001","purpose":"MEETING","peopleCount":1,"idType":"NONE"}""")
                .andExpect(status().isCreated());
        api.post("/api/frontoffice/blocklist", admin.accessToken(), """
                {"name":"Unknown Salesman","reason":"Kept selling insurance."}""").andExpect(status().isCreated());
        api.post("/api/me/children/" + student + "/gate-passes", lata.accessToken(), """
                {"pickupAt":"%s","reason":"Dentist","collectorKind":"SELF"}"""
                .formatted(Instant.now().plus(Duration.ofMinutes(5)))).andExpect(status().isCreated());
        api.post("/api/me/children/" + student + "/pickup-persons", lata.accessToken(), """
                {"name":"Ravi Rao","relation":"Father","phone":"9876501009"}""").andExpect(status().isCreated());
        api.post("/api/frontoffice/calls", admin.accessToken(), """
                {"direction":"INCOMING","callerName":"Vikram Singh","about":"Bonafide certificate."}""")
                .andExpect(status().isCreated());
        api.post("/api/frontoffice/enquiries", admin.accessToken(), """
                {"source":"WALK_IN","topic":"ADMISSION","name":"Kavita Menon","details":"Admission next year."}""")
                .andExpect(status().isCreated());
        api.post("/api/frontoffice/courier", admin.accessToken(), """
                {"direction":"INWARD","item":"CBSE circulars","sender":"CBSE","receiver":"Principal",
                 "itemDate":"%s"}""".formatted(LocalDate.now(ZoneId.of("Asia/Kolkata"))))
                .andExpect(status().isCreated());
    }

    /**
     * Accounts records: one row in every accounts table through the API, plus the payroll cost that the payroll
     * module's approval event brings (the fee income row came with the fee payment above).
     */
    private void accountsRecords(School school, Session admin) throws Exception {
        String adminId = TestApi.read(api.get("/api/me", admin.accessToken()), "$.id");
        new AccountsFixtures(api).everything(admin, adminId);
        AccountsFixtures.payrollApproved(events, school.tenantId(), YearMonth.now(ZoneId.of("Asia/Kolkata")),
                6_000_000, 720_000, UUID.fromString(adminId));
    }

    /** Inventory records: one row in every inventory table through the API, with the accounts vendor above. */
    private void inventoryRecords(Session admin, String tara) throws Exception {
        String adminId = TestApi.read(api.get("/api/me", admin.accessToken()), "$.id");
        new InventoryFixtures(api).everything(admin, adminId, tara);
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
