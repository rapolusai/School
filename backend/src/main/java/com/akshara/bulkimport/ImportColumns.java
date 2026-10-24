package com.akshara.bulkimport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The columns of the school import workbook: the headings the template writes and the names the students and staff
 * checks use. Headings are matched loosely (case, spaces and punctuation do not matter) and a few common other names
 * are accepted, so "Admission No.", "admission_no" and "Admission number" are the same column.
 */
final class ImportColumns {

    /** A column: the heading the template writes and the field name the checks use. */
    record Column(String heading, String field, boolean required) {
    }

    static final List<Column> STUDENT = List.of(
            new Column("Admission no", "admission_no", true),
            new Column("Roll no", "roll_no", false),
            new Column("First name", "first_name", true),
            new Column("Last name", "last_name", false),
            new Column("Date of birth", "date_of_birth", true),
            new Column("Gender", "gender", true),
            new Column("Guardian name", "guardian_name", true),
            new Column("Relation", "guardian_relation", true),
            new Column("Guardian mobile", "guardian_phone", true),
            new Column("Guardian email", "guardian_email", false),
            new Column("Admission date", "admission_date", false));

    static final List<Column> STAFF = List.of(
            new Column("Name", "name", true),
            new Column("Email", "email", true),
            new Column("Mobile", "mobile", true),
            new Column("Role", "role", true),
            new Column("Designation", "designation", true),
            new Column("Department", "department", false),
            new Column("Employment type", "employment_type", true),
            new Column("Joining date", "joining_date", true),
            new Column("Employee code", "employee_code", true));

    private static final Map<String, String> STUDENT_ALIASES = aliases(STUDENT, Map.of(
            "admission_number", "admission_no",
            "roll_number", "roll_no",
            "dob", "date_of_birth",
            "parent_name", "guardian_name",
            "relation", "guardian_relation",
            "guardian_relation", "guardian_relation",
            "guardian_phone", "guardian_phone",
            "parent_mobile", "guardian_phone",
            "parent_email", "guardian_email"));

    private static final Map<String, String> STAFF_ALIASES = aliases(STAFF, Map.of(
            "full_name", "name",
            "mobile_number", "mobile",
            "phone", "mobile",
            "roles", "role",
            "date_of_joining", "joining_date",
            "employee_id", "employee_code",
            "employee_no", "employee_code"));

    private ImportColumns() {
    }

    /** "Admission No." becomes "admission_no". */
    static String normalize(String heading) {
        return heading.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** The field a student heading stands for, or null when it is not one. */
    static String studentField(String heading) {
        return STUDENT_ALIASES.get(normalize(heading));
    }

    static String staffField(String heading) {
        return STAFF_ALIASES.get(normalize(heading));
    }

    static String heading(List<Column> columns, String field) {
        return columns.stream().filter(c -> c.field().equals(field)).map(Column::heading).findFirst().orElse(field);
    }

    private static Map<String, String> aliases(List<Column> columns, Map<String, String> extra) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Column c : columns) {
            map.put(normalize(c.heading()), c.field());
            map.put(c.field(), c.field());
        }
        map.putAll(extra);
        return Map.copyOf(map);
    }
}
