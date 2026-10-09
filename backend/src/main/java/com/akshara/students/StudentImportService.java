package com.akshara.students;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService.CleanGuardian;

/**
 * Admits many students at once from CSV text. Every row is checked first; a dry run only reports, and a real run
 * writes every row in one transaction or, when any row has a problem, writes nothing.
 */
@Service
public class StudentImportService {

    public static final int MAX_ROWS = 2_000;
    static final int MAX_REPORTED_ERRORS = 500;

    /** Columns the header must contain, in the documented order. last_name and guardian_email may be left empty. */
    public static final List<String> COLUMNS = List.of("admission_no", "first_name", "last_name", "date_of_birth",
            "gender", "class", "section", "guardian_name", "guardian_relation", "guardian_phone", "guardian_email");
    /** Columns that may be added. admission_date defaults to the day of the import. */
    public static final List<String> OPTIONAL_COLUMNS = List.of("admission_date", "roll_no");

    static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern ADMISSION_NO = Pattern.compile(StudentForms.ADMISSION_NO_PATTERN);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT));

    public record RowError(int row, String column, String message) {
    }

    /**
     * {@code row} numbers are lines of the file (the header is line 1), so they match what a spreadsheet shows.
     * {@code errors} lists at most 500 problems; {@code invalidRows} counts every row with at least one.
     */
    public record ImportResult(boolean dryRun, boolean committed, int totalRows, int validRows, int invalidRows,
            int created, List<RowError> errors, List<String> ignoredColumns) {
    }

    private record ParsedRow(int line, Student.Profile profile, SectionInfo section, Integer rollNo,
            CleanGuardian guardian) {
    }

    private final StudentService studentService;
    private final StudentRepository students;
    private final GuardianRepository guardians;
    private final StudentGuardianRepository links;
    private final EnrollmentRepository enrollments;
    private final AcademicsDirectory academics;

    public StudentImportService(StudentService studentService, StudentRepository students,
            GuardianRepository guardians, StudentGuardianRepository links, EnrollmentRepository enrollments,
            AcademicsDirectory academics) {
        this.studentService = studentService;
        this.students = students;
        this.guardians = guardians;
        this.links = links;
        this.enrollments = enrollments;
        this.academics = academics;
    }

    @Transactional
    public ImportResult run(String csv, boolean dryRun, Actor actor) {
        TenantContext.require();
        List<CsvParser.Row> rows;
        try {
            rows = CsvParser.parse(csv).stream().filter(r -> !r.isBlank()).toList();
        } catch (CsvParser.CsvException e) {
            throw ApiException.badRequest(e.getMessage(), "csv");
        }
        if (rows.isEmpty()) {
            throw ApiException.badRequest("The file is empty. Start with the header row.", "csv");
        }
        Map<String, Integer> columns = header(rows.getFirst());
        List<String> ignored = columns.keySet().stream()
                .filter(c -> !COLUMNS.contains(c) && !OPTIONAL_COLUMNS.contains(c)).toList();
        List<CsvParser.Row> data = rows.subList(1, rows.size());
        if (data.isEmpty()) {
            throw ApiException.badRequest("The file has a header but no students.", "csv");
        }
        if (data.size() > MAX_ROWS) {
            throw ApiException.badRequest("A file can have at most " + String.format(Locale.ROOT, "%,d", MAX_ROWS)
                    + " students. This one has " + String.format(Locale.ROOT, "%,d", data.size()) + ".", "csv");
        }
        YearInfo year = academics.currentYear().orElseThrow(() -> ApiException.badRequest(
                "Set up the current academic year in School setup first.", "csv"));

        List<RowError> errors = new ArrayList<>();
        Set<Integer> invalidLines = new HashSet<>();
        List<ParsedRow> parsed = validate(data, columns, year, errors, invalidLines);

        int invalid = invalidLines.size();
        List<RowError> reported = errors.size() > MAX_REPORTED_ERRORS ? errors.subList(0, MAX_REPORTED_ERRORS) : errors;
        if (dryRun || invalid > 0) {
            return new ImportResult(dryRun, false, data.size(), data.size() - invalid, invalid, 0,
                    List.copyOf(reported), ignored);
        }

        Map<String, Guardian> cache = new HashMap<>();
        for (Guardian g : guardians.findByPhoneIn(parsed.stream().map(p -> p.guardian().phone()).distinct().toList())) {
            cache.putIfAbsent(g.getPhone() + "|" + g.getName().toLowerCase(Locale.ROOT), g);
        }
        for (ParsedRow row : parsed) {
            Student student = students.save(new Student(row.profile()));
            enrollments.save(new Enrollment(student.getId(), year.id(), row.section().id(), row.rollNo()));
            Guardian guardian = cache.get(row.guardian().key());
            if (guardian == null) {
                CleanGuardian g = row.guardian();
                guardian = guardians.save(new Guardian(g.name(), g.relation(), g.phone(), g.email(), g.occupation()));
                cache.put(g.key(), guardian);
            } else {
                guardian.fillGaps(row.guardian().email(), null);
            }
            links.save(new StudentGuardian(student.getId(), guardian.getId(), true));
        }
        students.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("count", parsed.size());
        details.put("academicYear", year.name());
        studentService.record(actor, "students.imported", "student", null, details);
        return new ImportResult(false, true, data.size(), parsed.size(), 0, parsed.size(), List.of(), ignored);
    }

    private static Map<String, Integer> header(CsvParser.Row row) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < row.values().size(); i++) {
            String name = row.values().get(i).trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
            if (name.isEmpty()) {
                continue;
            }
            if (columns.putIfAbsent(name, i) != null) {
                throw ApiException.badRequest("The column " + name + " appears twice in the header.", "csv");
            }
        }
        List<String> missing = COLUMNS.stream().filter(c -> !columns.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("The header is missing " + String.join(", ", missing)
                    + ". The first row must name the columns.", "csv");
        }
        return columns;
    }

    private List<ParsedRow> validate(List<CsvParser.Row> data, Map<String, Integer> columns, YearInfo year,
            List<RowError> errors, Set<Integer> invalidLines) {
        Map<String, SectionInfo> sections = new HashMap<>();
        for (SectionInfo s : academics.sections()) {
            sections.put(key(s.className(), s.name()), s);
        }
        Set<String> admissionNos = new HashSet<>();
        for (CsvParser.Row row : data) {
            String value = cell(row, columns, "admission_no");
            if (!value.isEmpty()) {
                admissionNos.add(value.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> taken = new HashSet<>(admissionNos.isEmpty() ? List.of()
                : students.findExistingAdmissionNos(admissionNos));
        Map<String, Integer> seenAdmission = new HashMap<>();
        Map<UUID, Long> seats = new HashMap<>();
        Map<String, Integer> seenRoll = new HashMap<>();
        Set<String> rollsInUse = new HashSet<>();
        for (Object[] r : enrollments.rollNosInYear(year.id())) {
            rollsInUse.add(r[0] + "|" + r[1]);
        }
        Map<UUID, Long> activeNow = new HashMap<>();
        for (Object[] r : enrollments.countActivePerSection(year.id())) {
            activeNow.put((UUID) r[0], ((Number) r[1]).longValue());
        }
        LocalDate today = LocalDate.now(SCHOOL_ZONE);

        List<ParsedRow> parsed = new ArrayList<>();
        for (CsvParser.Row row : data) {
            int line = row.line();
            List<RowError> rowErrors = new ArrayList<>();

            String admissionNo = cell(row, columns, "admission_no");
            if (admissionNo.isEmpty()) {
                rowErrors.add(new RowError(line, "admission_no", "Fill in the admission number."));
            } else if (admissionNo.length() > 30 || !ADMISSION_NO.matcher(admissionNo).matches()) {
                rowErrors.add(new RowError(line, "admission_no",
                        "Use up to 30 letters, digits and / . _ - for the admission number."));
            } else {
                String lower = admissionNo.toLowerCase(Locale.ROOT);
                Integer firstLine = seenAdmission.putIfAbsent(lower, line);
                if (firstLine != null) {
                    rowErrors.add(new RowError(line, "admission_no", "Also used on row " + firstLine + "."));
                } else if (taken.contains(lower)) {
                    rowErrors.add(new RowError(line, "admission_no",
                            "Admission number " + admissionNo + " is already used."));
                }
            }

            String firstName = cell(row, columns, "first_name");
            if (firstName.isEmpty()) {
                rowErrors.add(new RowError(line, "first_name", "Fill in the first name."));
            } else if (firstName.length() > 100) {
                rowErrors.add(new RowError(line, "first_name", "Use at most 100 characters."));
            }
            String lastName = cell(row, columns, "last_name");
            if (lastName.length() > 100) {
                rowErrors.add(new RowError(line, "last_name", "Use at most 100 characters."));
            }

            LocalDate dob = null;
            String dobText = cell(row, columns, "date_of_birth");
            if (dobText.isEmpty()) {
                rowErrors.add(new RowError(line, "date_of_birth", "Fill in the date of birth."));
            } else {
                dob = parseDate(dobText);
                if (dob == null) {
                    rowErrors.add(new RowError(line, "date_of_birth",
                            "Write the date as YYYY-MM-DD or DD-MM-YYYY."));
                } else if (!dob.isBefore(today)) {
                    rowErrors.add(new RowError(line, "date_of_birth", "The date of birth must be in the past."));
                    dob = null;
                }
            }

            LocalDate admissionDate = today;
            if (columns.containsKey("admission_date") && !cell(row, columns, "admission_date").isEmpty()) {
                admissionDate = parseDate(cell(row, columns, "admission_date"));
                if (admissionDate == null) {
                    rowErrors.add(new RowError(line, "admission_date",
                            "Write the date as YYYY-MM-DD or DD-MM-YYYY."));
                }
            }
            if (dob != null && admissionDate != null && !dob.isBefore(admissionDate)) {
                rowErrors.add(new RowError(line, "date_of_birth",
                        "The date of birth must be before the admission date."));
            }

            Gender gender = parseGender(cell(row, columns, "gender"));
            if (gender == null) {
                rowErrors.add(new RowError(line, "gender", "Use MALE, FEMALE or OTHER (or M, F, O)."));
            }

            String className = cell(row, columns, "class");
            String sectionName = cell(row, columns, "section");
            SectionInfo section = null;
            if (className.isEmpty() || sectionName.isEmpty()) {
                rowErrors.add(new RowError(line, className.isEmpty() ? "class" : "section",
                        "Fill in both the class and the section."));
            } else {
                section = sections.get(key(className, sectionName));
                if (section == null && className.matches("\\d{1,2}")) {
                    section = sections.get(key("Class " + className, sectionName));
                }
                if (section == null) {
                    rowErrors.add(new RowError(line, "section",
                            "There is no section " + sectionName + " in " + className + ". Add it in School setup first."));
                }
            }

            Integer rollNo = null;
            if (columns.containsKey("roll_no") && !cell(row, columns, "roll_no").isEmpty()) {
                String rollText = cell(row, columns, "roll_no");
                if (!rollText.matches("\\d{1,3}") || Integer.parseInt(rollText) < 1) {
                    rowErrors.add(new RowError(line, "roll_no", "Use a roll number from 1 to 999."));
                } else if (section != null) {
                    rollNo = Integer.parseInt(rollText);
                    String rollKey = section.id() + "|" + rollNo;
                    Integer firstLine = seenRoll.putIfAbsent(rollKey, line);
                    if (firstLine != null) {
                        rowErrors.add(new RowError(line, "roll_no", "Also used on row " + firstLine + "."));
                    } else if (rollsInUse.contains(rollKey)) {
                        rowErrors.add(new RowError(line, "roll_no",
                                "Roll number " + rollNo + " is already used in " + section.label() + "."));
                    }
                }
            }

            String guardianName = cell(row, columns, "guardian_name");
            if (guardianName.isEmpty()) {
                rowErrors.add(new RowError(line, "guardian_name", "Fill in the parent or guardian's name."));
            } else if (guardianName.length() > 200) {
                rowErrors.add(new RowError(line, "guardian_name", "Use at most 200 characters."));
            }
            GuardianRelation relation = parseRelation(cell(row, columns, "guardian_relation"));
            if (relation == null) {
                rowErrors.add(new RowError(line, "guardian_relation", "Use FATHER, MOTHER or GUARDIAN."));
            }
            String phone = Phones.normalize(cell(row, columns, "guardian_phone"));
            if (phone == null) {
                rowErrors.add(new RowError(line, "guardian_phone", Phones.MESSAGE));
            }
            String email = cell(row, columns, "guardian_email");
            if (!email.isEmpty() && (email.length() > 254 || !EMAIL.matcher(email).matches())) {
                rowErrors.add(new RowError(line, "guardian_email", "Enter a valid email address or leave it empty."));
            }

            if (section != null && section.capacity() != null) {
                long used = seats.merge(section.id(), 1L, Long::sum) + activeNow.getOrDefault(section.id(), 0L);
                if (used > section.capacity()) {
                    rowErrors.add(new RowError(line, "section", section.label() + " is full ("
                            + section.capacity() + " places)."));
                }
            }

            if (rowErrors.isEmpty()) {
                parsed.add(new ParsedRow(line, new Student.Profile(admissionNo, firstName,
                        lastName.isEmpty() ? null : lastName, dob, gender, admissionDate, null, null, null, null),
                        section, rollNo, new CleanGuardian(guardianName, relation, phone,
                                email.isEmpty() ? null : email, null, true)));
            } else {
                invalidLines.add(line);
                errors.addAll(rowErrors);
            }
        }
        return parsed;
    }

    private static String cell(CsvParser.Row row, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index >= row.values().size()) {
            return "";
        }
        return row.values().get(index).trim();
    }

    private static String key(String className, String sectionName) {
        return className.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + "|"
                + sectionName.trim().toLowerCase(Locale.ROOT);
    }

    static LocalDate parseDate(String text) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text.trim(), format);
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return null;
    }

    static Gender parseGender(String text) {
        return switch (text.trim().toUpperCase(Locale.ROOT)) {
            case "MALE", "M" -> Gender.MALE;
            case "FEMALE", "F" -> Gender.FEMALE;
            case "OTHER", "O" -> Gender.OTHER;
            default -> null;
        };
    }

    static GuardianRelation parseRelation(String text) {
        try {
            return text.isBlank() ? null : GuardianRelation.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
