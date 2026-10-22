package com.akshara.shared;

import java.util.List;

/** Permission codes checked by the API. Roles are bundles of these, adjustable per school. */
public final class Permissions {

    public static final String DASHBOARD_VIEW = "dashboard.view";
    public static final String USERS_READ = "users.read";
    public static final String USERS_MANAGE = "users.manage";
    public static final String ROLES_READ = "roles.read";
    public static final String AUDIT_READ = "audit.read";
    public static final String SETTINGS_MANAGE = "settings.manage";
    public static final String ACADEMICS_READ = "academics.read";
    public static final String STUDENTS_READ = "students.read";
    public static final String STUDENTS_MANAGE = "students.manage";
    public static final String ATTENDANCE_MARK = "attendance.mark";
    public static final String ATTENDANCE_READ = "attendance.read";
    /** Mark and read every section's attendance; without it, teachers work only with the sections they lead. */
    public static final String ATTENDANCE_MANAGE = "attendance.manage";
    public static final String FEES_READ = "fees.read";
    public static final String FEES_COLLECT = "fees.collect";
    public static final String FEES_MANAGE = "fees.manage";
    public static final String EXAMS_MANAGE = "exams.manage";
    public static final String NOTICES_SEND = "notices.send";
    public static final String CHILD_VIEW = "child.view";
    public static final String ADMISSIONS_READ = "admissions.read";
    public static final String ADMISSIONS_MANAGE = "admissions.manage";
    /** The log of SMS, WhatsApp and email messages the school has sent. */
    public static final String MESSAGES_READ = "messages.read";
    /** The staff directory, staff profiles, departments and staff attendance reports. */
    public static final String STAFF_READ = "staff.read";
    /** Adding staff, editing profiles and departments, recording leaving, leave types and balances. */
    public static final String STAFF_MANAGE = "staff.manage";
    /** Applying for one's own leave and checking in and out. Every staff role has it. */
    public static final String LEAVE_REQUEST = "leave.request";
    /** Approving or rejecting anyone's leave (department heads approve their department's without it). */
    public static final String LEAVE_APPROVE = "leave.approve";
    /** Marking and correcting any staff member's attendance. */
    public static final String STAFF_ATTENDANCE_MANAGE = "staff_attendance.manage";
    /** Read one's own notice board and the school calendar. */
    public static final String NOTICES_READ = "notices.read";
    /** Approve or reject circulars sent for approval, and address any class, section or role. */
    public static final String NOTICES_APPROVE = "notices.approve";
    /** Add, change and remove school calendar entries. */
    public static final String CALENDAR_MANAGE = "calendar.manage";
    /** See the bell schedule, section and teacher timetables and who is free (every member of staff). */
    public static final String TIMETABLE_READ = "timetable.read";
    /** Change the bell schedule, teacher assignments and timetables, and arrange substitutions. */
    public static final String TIMETABLE_MANAGE = "timetable.manage";
    /**
     * Assign and review homework. Together with timetable.manage it covers every section; on its own it covers the
     * sections and subjects the person teaches (teacher assignments) and the sections they are class teacher of.
     */
    public static final String HOMEWORK_MANAGE = "homework.manage";
    /** The school's own Billing page: plan, trial, renewal date and Akshara's invoices to the school. */
    public static final String BILLING_READ = "billing.read";
    /** The privacy notice, the grievance officer, paper consent, and parents' data requests (exports, erasure). */
    public static final String PRIVACY_MANAGE = "privacy.manage";
    /** Search the library catalogue (every member of staff). */
    public static final String LIBRARY_READ = "library.read";
    /** The library: catalogue, circulation desk, fines, holds, labels, stock checks, reports and settings. */
    public static final String LIBRARY_MANAGE = "library.manage";
    /** Payroll screens, payslips of everyone and the payroll reports (bank account numbers and PAN masked). */
    public static final String PAYROLL_READ = "payroll.read";
    /**
     * Payroll settings, salary structures, staff pay details (full account numbers and PAN on request, audited),
     * monthly runs, adjustments, marking a month paid and the bank, PF, ESI, PT and TDS files.
     */
    public static final String PAYROLL_MANAGE = "payroll.manage";
    /** Approving a month's payroll, which locks it. */
    public static final String PAYROLL_APPROVE = "payroll.approve";
    /** Exam structure, date sheets, results, report cards and result analysis (read only). */
    public static final String EXAMS_READ = "exams.read";
    /** Grading schemes, terms and exams with their papers and weightage, and publishing date sheets. */
    public static final String EXAMS_SETUP = "exams.setup";
    /**
     * Publishing results to families, unlocking submitted marks, and entering marks for every section. Without it,
     * exams.manage covers the subjects a teacher teaches (timetable) and the sections they are class teacher of.
     */
    public static final String EXAMS_PUBLISH = "exams.publish";
    /**
     * Talk to parents in the app (conversations about a student). Teachers reach the parents of the students they teach;
     * with chat.oversee, any parent.
     */
    public static final String CHAT_USE = "chat.use";
    /**
     * Read any conversation for safeguarding (audited every time), close and reopen conversations, review reports and
     * set the staff's availability for messages.
     */
    public static final String CHAT_OVERSEE = "chat.oversee";
    /** Set up parent-teacher meetings, book for families and see every teacher's schedule and the attendance summary. */
    public static final String PTM_MANAGE = "ptm.manage";
    /** Viewing lesson plans, the syllabus and its coverage, and study material (staff). */
    public static final String LESSONS_READ = "lessons.read";
    /**
     * A teacher's own lesson plans, study material, question bank and online tests, for the sections and subjects
     * they teach (timetable).
     */
    public static final String LESSONS_MANAGE = "lessons.manage";
    /** Approving or returning lesson plans, and every section and subject (School Admin, Principal, coordinators). */
    public static final String LESSONS_APPROVE = "lessons.approve";
    /** Routes, vehicles and crew, the live trip board and the transport reports (read only). */
    public static final String TRANSPORT_READ = "transport.read";
    /** Vehicles, drivers and attendants, routes and stops, fee zones, student allocations and transport alerts. */
    public static final String TRANSPORT_MANAGE = "transport.manage";
    /**
     * Running a trip from a phone: start it, share the bus's position, mark students boarded, dropped or absent and
     * report a delay. Without transport.manage, only the trips of routes the user is the driver or attendant of.
     */
    public static final String TRANSPORT_TRIP = "transport.trip";

    /** The certificate register, templates and the ID card register (names and certificate details). */
    public static final String CERTIFICATES_READ = "certificates.read";
    /** Preparing certificates and ID cards, templates and the header, corrections and cancellations. */
    public static final String CERTIFICATES_ISSUE = "certificates.issue";
    /** Signing a prepared certificate, which issues it (the Principal or School Admin). */
    public static final String CERTIFICATES_SIGN = "certificates.sign";

    public static final String PLATFORM_ADMIN = "platform.admin";

    public static final List<String> ALL_SCHOOL = List.of(
            DASHBOARD_VIEW, USERS_READ, USERS_MANAGE, ROLES_READ, AUDIT_READ, SETTINGS_MANAGE,
            STUDENTS_READ, STUDENTS_MANAGE, ATTENDANCE_MARK, ATTENDANCE_READ, FEES_READ, FEES_COLLECT,
            EXAMS_MANAGE, NOTICES_SEND, CHILD_VIEW, ACADEMICS_READ, ADMISSIONS_READ, ADMISSIONS_MANAGE,
            ATTENDANCE_MANAGE, MESSAGES_READ, FEES_MANAGE, STAFF_READ, STAFF_MANAGE, LEAVE_REQUEST, LEAVE_APPROVE,
            STAFF_ATTENDANCE_MANAGE, NOTICES_READ, NOTICES_APPROVE, CALENDAR_MANAGE, TIMETABLE_READ, TIMETABLE_MANAGE,
            HOMEWORK_MANAGE, BILLING_READ, PRIVACY_MANAGE,
            LIBRARY_READ, LIBRARY_MANAGE, PAYROLL_READ, PAYROLL_MANAGE, PAYROLL_APPROVE, EXAMS_READ, EXAMS_SETUP,
            EXAMS_PUBLISH,
            CHAT_USE, CHAT_OVERSEE, PTM_MANAGE, LESSONS_READ, LESSONS_MANAGE, LESSONS_APPROVE, TRANSPORT_READ,
            TRANSPORT_MANAGE, TRANSPORT_TRIP, CERTIFICATES_READ, CERTIFICATES_ISSUE, CERTIFICATES_SIGN);

    private Permissions() {
    }
}
