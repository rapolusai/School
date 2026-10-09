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
    public static final String EXAMS_MANAGE = "exams.manage";
    public static final String NOTICES_SEND = "notices.send";
    public static final String CHILD_VIEW = "child.view";
    public static final String ADMISSIONS_READ = "admissions.read";
    public static final String ADMISSIONS_MANAGE = "admissions.manage";
    /** The log of SMS, WhatsApp and email messages the school has sent. */
    public static final String MESSAGES_READ = "messages.read";

    public static final String PLATFORM_ADMIN = "platform.admin";

    public static final List<String> ALL_SCHOOL = List.of(
            DASHBOARD_VIEW, USERS_READ, USERS_MANAGE, ROLES_READ, AUDIT_READ, SETTINGS_MANAGE,
            STUDENTS_READ, STUDENTS_MANAGE, ATTENDANCE_MARK, ATTENDANCE_READ, FEES_READ, FEES_COLLECT,
            EXAMS_MANAGE, NOTICES_SEND, CHILD_VIEW, ACADEMICS_READ, ADMISSIONS_READ, ADMISSIONS_MANAGE,
            ATTENDANCE_MANAGE, MESSAGES_READ);

    private Permissions() {
    }
}
