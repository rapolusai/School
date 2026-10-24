package com.akshara.staff;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.Role;
import com.akshara.identity.RoleCatalog;
import com.akshara.identity.RoleRepository;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.identity.UserService;
import com.akshara.shared.TenantContext;
import com.akshara.students.Phones;
import com.akshara.students.StudentImportService;

/**
 * Adds many staff members at once, from rows of a spreadsheet: each gets a staff sign-in and a complete staff profile.
 * The rules are those of "Add staff" (docs/api/phase-1-staff.md) with one difference: no password is set. Imported
 * staff cannot sign in until a School Admin sets their password (PUT /api/staff/{userId}/password); no password is
 * ever generated, stored or shown. Every row is checked first; {@link #commit} writes a clean batch only.
 */
@Service
public class StaffImportService {

    /** Columns every Staff sheet must have. {@code department} may be added. */
    public static final List<String> COLUMNS = List.of("name", "email", "mobile", "role", "designation",
            "employment_type", "joining_date", "employee_code");
    public static final List<String> OPTIONAL_COLUMNS = List.of("department");

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern EMPLOYEE_CODE = Pattern.compile(StaffForms.EMPLOYEE_CODE_PATTERN);

    /** One row to check. {@code line} must be unique within a batch; {@code place} names it in messages. */
    public record SourceRow(int line, String place, Map<String, String> values) {

        String cell(String column) {
            String value = values.get(column);
            return value == null ? "" : value.trim();
        }
    }

    public record RowError(int row, String column, String message) {
    }

    private record ParsedRow(int line, String name, String email, List<String> roles, String department,
            StaffProfile.Fields fields) {
    }

    /** Checked rows. {@code newDepartments} are the departments the commit will add (by name, as first written). */
    public static final class StaffBatch {

        private final List<ParsedRow> rows;
        private final List<RowError> errors;
        private final Set<Integer> invalidLines;
        private final List<String> newDepartments;

        private StaffBatch(List<ParsedRow> rows, List<RowError> errors, Set<Integer> invalidLines,
                List<String> newDepartments) {
            this.rows = rows;
            this.errors = List.copyOf(errors);
            this.invalidLines = Set.copyOf(invalidLines);
            this.newDepartments = List.copyOf(newDepartments);
        }

        public List<RowError> errors() {
            return errors;
        }

        public Set<Integer> invalidLines() {
            return invalidLines;
        }

        public int validRows() {
            return rows.size();
        }

        public boolean clean() {
            return invalidLines.isEmpty();
        }

        public List<String> newDepartments() {
            return newDepartments;
        }
    }

    private final StaffProfileRepository profiles;
    private final DepartmentRepository departments;
    private final DepartmentService departmentService;
    private final RoleRepository roles;
    private final UserRepository users;
    private final UserService userService;
    private final AuditService audit;

    public StaffImportService(StaffProfileRepository profiles, DepartmentRepository departments,
            DepartmentService departmentService, RoleRepository roles, UserRepository users, UserService userService,
            AuditService audit) {
        this.profiles = profiles;
        this.departments = departments;
        this.departmentService = departmentService;
        this.roles = roles;
        this.users = users;
        this.userService = userService;
        this.audit = audit;
    }

    /**
     * Checks every row against the school (emails and employee codes in use, roles, departments) and the other rows.
     * A department the school does not have is added by the commit when {@code createDepartments} is true, and is a
     * problem otherwise. Writes nothing.
     */
    @Transactional(readOnly = true)
    public StaffBatch check(List<SourceRow> rows, boolean createDepartments) {
        TenantContext.require();
        Map<String, String> roleByName = new HashMap<>();
        for (Role r : roles.findAllByOrderByNameAsc()) {
            if (RoleCatalog.isStaffRole(r.getCode())) {
                roleByName.put(roleKey(r.getCode()), r.getCode());
                roleByName.put(roleKey(r.getName()), r.getCode());
            }
        }
        Map<String, String> departmentByName = new HashMap<>();
        for (Department d : departments.findAll()) {
            departmentByName.put(d.getName().toLowerCase(Locale.ROOT), d.getName());
        }
        Set<String> emails = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (SourceRow row : rows) {
            if (!row.cell("email").isEmpty()) {
                emails.add(row.cell("email").toLowerCase(Locale.ROOT));
            }
            if (!row.cell("employee_code").isEmpty()) {
                codes.add(row.cell("employee_code").toLowerCase(Locale.ROOT));
            }
        }
        Set<String> emailsTaken = new HashSet<>(emails.isEmpty() ? List.of() : users.findExistingEmails(emails));
        Set<String> codesTaken = new HashSet<>(codes.isEmpty() ? List.of()
                : profiles.findExistingEmployeeCodes(codes));
        LocalDate latestJoining = StaffAttendanceService.today().plusYears(1);

        Map<String, String> seenEmail = new HashMap<>();
        Map<String, String> seenCode = new HashMap<>();
        Map<String, String> newDepartments = new LinkedHashMap<>();
        List<RowError> errors = new ArrayList<>();
        Set<Integer> invalidLines = new HashSet<>();
        List<ParsedRow> parsed = new ArrayList<>();
        for (SourceRow row : rows) {
            int line = row.line();
            List<RowError> rowErrors = new ArrayList<>();

            String name = row.cell("name");
            if (name.isEmpty()) {
                rowErrors.add(new RowError(line, "name", "Fill in the name."));
            } else if (name.length() > 200) {
                rowErrors.add(new RowError(line, "name", "Use at most 200 characters."));
            }

            String email = row.cell("email");
            if (email.isEmpty()) {
                rowErrors.add(new RowError(line, "email", "Fill in the email they will sign in with."));
            } else if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
                rowErrors.add(new RowError(line, "email", "Enter a valid email address."));
            } else {
                String lower = email.toLowerCase(Locale.ROOT);
                String firstPlace = seenEmail.putIfAbsent(lower, row.place());
                if (firstPlace != null) {
                    rowErrors.add(new RowError(line, "email", "Also used on " + firstPlace + "."));
                } else if (emailsTaken.contains(lower)) {
                    rowErrors.add(new RowError(line, "email", "Someone in this school already uses " + email + "."));
                }
            }

            String mobile = Phones.normalize(row.cell("mobile"));
            if (mobile == null) {
                rowErrors.add(new RowError(line, "mobile", Phones.MESSAGE));
            }

            List<String> roleCodes = new ArrayList<>();
            String roleText = row.cell("role");
            if (roleText.isEmpty()) {
                rowErrors.add(new RowError(line, "role", "Fill in the role, for example Teacher."));
            } else {
                Set<String> found = new LinkedHashSet<>();
                List<String> unknown = new ArrayList<>();
                for (String part : roleText.split("[,;/]")) {
                    if (part.isBlank()) {
                        continue;
                    }
                    String code = roleByName.get(roleKey(part));
                    if (code == null) {
                        unknown.add(part.trim());
                    } else {
                        found.add(code);
                    }
                }
                if (!unknown.isEmpty() || found.isEmpty()) {
                    rowErrors.add(new RowError(line, "role", (unknown.isEmpty() ? "" : "Unknown staff role: "
                            + String.join(", ", unknown) + ". ") + "Use a staff role such as Teacher, Accountant or "
                            + "Principal; separate two roles with a comma."));
                } else if (found.size() > 10) {
                    rowErrors.add(new RowError(line, "role", "Give at most 10 roles."));
                } else {
                    roleCodes.addAll(found);
                }
            }

            String designation = row.cell("designation");
            if (designation.isEmpty()) {
                rowErrors.add(new RowError(line, "designation", "Fill in the designation, for example PGT Maths."));
            } else if (designation.length() > 100) {
                rowErrors.add(new RowError(line, "designation", "Use at most 100 characters."));
            }

            String department = null;
            String departmentText = row.cell("department");
            if (!departmentText.isEmpty()) {
                String lower = departmentText.toLowerCase(Locale.ROOT);
                if (departmentText.length() > 100) {
                    rowErrors.add(new RowError(line, "department", "Use at most 100 characters."));
                } else if (departmentByName.containsKey(lower)) {
                    department = departmentByName.get(lower);
                } else if (createDepartments) {
                    department = newDepartments.computeIfAbsent(lower, k -> departmentText);
                } else {
                    rowErrors.add(new RowError(line, "department", "There is no department called " + departmentText
                            + ". Add it first, or let the import create missing ones."));
                }
            }

            EmploymentType employmentType = parseEmploymentType(row.cell("employment_type"));
            if (employmentType == null) {
                rowErrors.add(new RowError(line, "employment_type",
                        "Use PERMANENT, CONTRACT, PART_TIME or PROBATION."));
            }

            LocalDate joined = null;
            String joinedText = row.cell("joining_date");
            if (joinedText.isEmpty()) {
                rowErrors.add(new RowError(line, "joining_date", "Fill in the joining date."));
            } else {
                joined = StudentImportService.parseDate(joinedText);
                if (joined == null) {
                    rowErrors.add(new RowError(line, "joining_date", "Write the date as YYYY-MM-DD or DD-MM-YYYY."));
                } else if (joined.isAfter(latestJoining)) {
                    rowErrors.add(new RowError(line, "joining_date", "The joining date is too far in the future."));
                } else if (joined.getYear() < 1950) {
                    rowErrors.add(new RowError(line, "joining_date", "Check the joining date."));
                }
            }

            String code = row.cell("employee_code");
            if (code.isEmpty()) {
                rowErrors.add(new RowError(line, "employee_code", "Fill in the employee code."));
            } else if (code.length() > 30 || !EMPLOYEE_CODE.matcher(code).matches()) {
                rowErrors.add(new RowError(line, "employee_code",
                        "Use up to 30 letters, digits and / . _ -, starting with a letter or digit."));
            } else {
                String lower = code.toLowerCase(Locale.ROOT);
                String firstPlace = seenCode.putIfAbsent(lower, row.place());
                if (firstPlace != null) {
                    rowErrors.add(new RowError(line, "employee_code", "Also used on " + firstPlace + "."));
                } else if (codesTaken.contains(lower)) {
                    rowErrors.add(new RowError(line, "employee_code", "Employee code " + code + " is already used."));
                }
            }

            if (rowErrors.isEmpty()) {
                parsed.add(new ParsedRow(line, name, email, List.copyOf(roleCodes), department,
                        new StaffProfile.Fields(code, designation, null, employmentType, joined, mobile, null, null,
                                null)));
            } else {
                invalidLines.add(line);
                errors.addAll(rowErrors);
            }
        }
        // Only departments that a clean row needs are worth listing.
        Set<String> needed = new HashSet<>();
        parsed.forEach(p -> {
            if (p.department() != null) {
                needed.add(p.department().toLowerCase(Locale.ROOT));
            }
        });
        List<String> toAdd = newDepartments.entrySet().stream().filter(e -> needed.contains(e.getKey()))
                .map(Map.Entry::getValue).toList();
        return new StaffBatch(parsed, errors, invalidLines, toAdd);
    }

    /**
     * Writes a clean batch: the departments it needs, then for each row a sign-in without a password and the staff
     * profile. Audited as one {@code staff.imported} event with counts (the sign-ins are audited as usual).
     */
    @Transactional
    public int commit(StaffBatch batch, Actor actor, Map<String, ?> auditDetails) {
        TenantContext.require();
        if (!batch.clean()) {
            throw new IllegalStateException("A batch with problems cannot be imported");
        }
        for (String name : batch.newDepartments()) {
            departmentService.create(new StaffForms.DepartmentFields(name, null), actor);
        }
        Map<String, UUID> departmentIds = new HashMap<>();
        for (Department d : departments.findAll()) {
            departmentIds.put(d.getName().toLowerCase(Locale.ROOT), d.getId());
        }
        for (ParsedRow row : batch.rows) {
            UserAccount user = userService.createUserWithoutPassword(row.name(), row.email(), row.roles(), actor);
            StaffProfile.Fields f = row.fields();
            UUID departmentId = row.department() == null ? null
                    : departmentIds.get(row.department().toLowerCase(Locale.ROOT));
            profiles.save(new StaffProfile(user.getId(), new StaffProfile.Fields(f.employeeCode(), f.designation(),
                    departmentId, f.employmentType(), f.dateOfJoining(), f.mobile(), null, null, null)));
        }
        profiles.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("count", batch.rows.size());
        details.put("departmentsAdded", batch.newDepartments().size());
        details.put("signIn", "password not set");
        details.putAll(auditDetails);
        if (actor == null) {
            audit.record("staff.imported", "staff", null, details);
        } else {
            audit.record(actor, "staff.imported", "staff", null, details);
        }
        return batch.rows.size();
    }

    private static String roleKey(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ");
    }

    static EmploymentType parseEmploymentType(String text) {
        String key = text.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        try {
            return key.isEmpty() ? null : EmploymentType.valueOf(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
