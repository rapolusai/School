package com.akshara.identity;

import static com.akshara.shared.Permissions.*;

import java.util.List;

/** The roles every new school starts with. Schools can adjust them later. */
public final class RoleCatalog {

    public record RoleTemplate(String code, String name, List<String> permissions) {
    }

    public static final String SCHOOL_ADMIN = "SCHOOL_ADMIN";

    public static final List<RoleTemplate> DEFAULTS = List.of(
            new RoleTemplate(SCHOOL_ADMIN, "School Admin", ALL_SCHOOL),
            new RoleTemplate("PRINCIPAL", "Principal", List.of(DASHBOARD_VIEW, USERS_READ, ROLES_READ, AUDIT_READ,
                    STUDENTS_READ, ATTENDANCE_READ, FEES_READ, EXAMS_MANAGE, NOTICES_SEND)),
            new RoleTemplate("TEACHER", "Teacher", List.of(DASHBOARD_VIEW, STUDENTS_READ, ATTENDANCE_MARK,
                    ATTENDANCE_READ, EXAMS_MANAGE, NOTICES_SEND)),
            new RoleTemplate("ACCOUNTANT", "Accountant", List.of(DASHBOARD_VIEW, STUDENTS_READ, FEES_READ,
                    FEES_COLLECT)),
            new RoleTemplate("FRONT_OFFICE", "Front office", List.of(DASHBOARD_VIEW, STUDENTS_READ)),
            new RoleTemplate("PARENT", "Parent", List.of(DASHBOARD_VIEW, CHILD_VIEW)),
            new RoleTemplate("STUDENT", "Student", List.of(DASHBOARD_VIEW)));

    private RoleCatalog() {
    }
}
