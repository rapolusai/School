package com.akshara.identity;

import static com.akshara.shared.Permissions.*;

import java.util.List;

/** The roles every new school starts with. Schools can adjust them later. */
public final class RoleCatalog {

    public record RoleTemplate(String code, String name, List<String> permissions) {
    }

    public static final String SCHOOL_ADMIN = "SCHOOL_ADMIN";
    public static final String TEACHER = "TEACHER";
    public static final String PARENT = "PARENT";
    public static final String STUDENT = "STUDENT";
    public static final String LIBRARIAN = "LIBRARIAN";
    public static final String TRANSPORT_MANAGER = "TRANSPORT_MANAGER";
    public static final String BUS_ATTENDANT = "BUS_ATTENDANT";
    public static final String SECURITY = "SECURITY";
    public static final String NURSE = "NURSE";

    public static final List<RoleTemplate> DEFAULTS = List.of(
            new RoleTemplate(SCHOOL_ADMIN, "School Admin", ALL_SCHOOL),
            new RoleTemplate("PRINCIPAL", "Principal", List.of(DASHBOARD_VIEW, USERS_READ, ROLES_READ, AUDIT_READ,
                    STUDENTS_READ, ATTENDANCE_READ, FEES_READ, EXAMS_MANAGE, NOTICES_SEND, ACADEMICS_READ,
                    ADMISSIONS_READ, ADMISSIONS_MANAGE, ATTENDANCE_MANAGE, MESSAGES_READ, STAFF_READ, LEAVE_REQUEST,
                    LEAVE_APPROVE, NOTICES_READ, NOTICES_APPROVE, CALENDAR_MANAGE, TIMETABLE_READ, TIMETABLE_MANAGE,
                    HOMEWORK_MANAGE, PRIVACY_MANAGE, LIBRARY_READ, EXAMS_READ, EXAMS_SETUP, EXAMS_PUBLISH, CHAT_USE,
                    CHAT_OVERSEE, PTM_MANAGE, LESSONS_READ, LESSONS_APPROVE, TRANSPORT_READ, CERTIFICATES_READ,
                    CERTIFICATES_ISSUE, CERTIFICATES_SIGN, FRONTOFFICE_READ, ACCOUNTS_READ,
                    ACCOUNTS_APPROVE, HEALTH_READ, SAFETY_MANAGE, POCSO_COMMITTEE)),
            new RoleTemplate("TEACHER", "Teacher", List.of(DASHBOARD_VIEW, STUDENTS_READ, ATTENDANCE_MARK,
                    ATTENDANCE_READ, EXAMS_MANAGE, NOTICES_SEND, ACADEMICS_READ, LEAVE_REQUEST, NOTICES_READ,
                    TIMETABLE_READ, HOMEWORK_MANAGE, LIBRARY_READ, EXAMS_READ, CHAT_USE, LESSONS_READ,
                    LESSONS_MANAGE, HEALTH_READ)),
            new RoleTemplate("ACCOUNTANT", "Accountant", List.of(DASHBOARD_VIEW, STUDENTS_READ, FEES_READ,
                    FEES_COLLECT, ACADEMICS_READ, FEES_MANAGE, LEAVE_REQUEST, NOTICES_READ, TIMETABLE_READ,
                    LIBRARY_READ, PAYROLL_READ, PAYROLL_MANAGE, ACCOUNTS_READ, ACCOUNTS_MANAGE)),
            // Front office handles admissions paperwork, so it may add and edit student records.
            new RoleTemplate("FRONT_OFFICE", "Front office", List.of(DASHBOARD_VIEW, STUDENTS_READ, STUDENTS_MANAGE,
                    ACADEMICS_READ, ADMISSIONS_READ, ADMISSIONS_MANAGE, LEAVE_REQUEST, NOTICES_READ, TIMETABLE_READ,
                    LIBRARY_READ, CERTIFICATES_READ, CERTIFICATES_ISSUE, FRONTOFFICE_READ, FRONTOFFICE_MANAGE,
                    GATE_PASS)),
            // The school librarian runs the library; like all staff they can apply for leave and read notices.
            new RoleTemplate(LIBRARIAN, "Librarian", List.of(DASHBOARD_VIEW, ACADEMICS_READ, LEAVE_REQUEST,
                    NOTICES_READ, TIMETABLE_READ, LIBRARY_READ, LIBRARY_MANAGE)),
            // The transport manager runs the school buses: vehicles, crew, routes, allocations and alerts.
            new RoleTemplate(TRANSPORT_MANAGER, "Transport manager", List.of(DASHBOARD_VIEW, ACADEMICS_READ,
                    LEAVE_REQUEST, NOTICES_READ, TIMETABLE_READ, TRANSPORT_READ, TRANSPORT_MANAGE, TRANSPORT_TRIP)),
            // Drivers and attendants run their route's trips from a phone.
            new RoleTemplate(BUS_ATTENDANT, "Bus attendant", List.of(DASHBOARD_VIEW, LEAVE_REQUEST, NOTICES_READ,
                    TRANSPORT_TRIP)),
            // Gate security checks visitors in and out and releases children against a parent's OTP, on a phone.
            new RoleTemplate(SECURITY, "Security", List.of(DASHBOARD_VIEW, LEAVE_REQUEST, NOTICES_READ, GATE_PASS)),
            // The school nurse keeps medical records and the infirmary log.
            new RoleTemplate(NURSE, "Nurse", List.of(DASHBOARD_VIEW, STUDENTS_READ, ACADEMICS_READ, LEAVE_REQUEST,
                    NOTICES_READ, TIMETABLE_READ, HEALTH_READ, HEALTH_MANAGE)),
            new RoleTemplate("PARENT", "Parent", List.of(DASHBOARD_VIEW, CHILD_VIEW, NOTICES_READ)),
            new RoleTemplate("STUDENT", "Student", List.of(DASHBOARD_VIEW, NOTICES_READ)));

    /** Every role except the family roles (Parent, Student) belongs to the school's staff. */
    public static boolean isStaffRole(String code) {
        return !PARENT.equals(code) && !STUDENT.equals(code);
    }

    private RoleCatalog() {
    }
}
