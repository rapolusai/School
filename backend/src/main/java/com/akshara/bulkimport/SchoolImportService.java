package com.akshara.bulkimport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.bulkimport.XlsxReader.Row;
import com.akshara.bulkimport.XlsxReader.Sheet;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffImportService;
import com.akshara.staff.StaffImportService.StaffBatch;
import com.akshara.students.StudentImportService;
import com.akshara.students.StudentImportService.StudentBatch;

/**
 * Brings an existing school's students and staff in from one Excel workbook: a "Staff" sheet and one sheet per class
 * and section (named like "Class 5 - A"). The whole file is checked first and every problem is reported by sheet and
 * row; nothing is written unless the whole file is clean, and then everything (any classes, sections and departments
 * it needs, the staff and the students) is written in one transaction. Students are checked with exactly the rules of
 * the CSV import, staff with the rules of "Add staff" (but without a password: see {@link StaffImportService}).
 * Parents get no sign-in. Aadhaar numbers are never read: unknown columns are ignored and listed.
 */
@Service
public class SchoolImportService {

    public static final int MAX_STUDENTS = 5_000;
    public static final int MAX_STAFF = 500;
    /** Problems listed per sheet and in all; the counts are always complete. */
    static final int MAX_PROBLEMS_PER_SHEET = 200;
    static final int MAX_PROBLEMS = 1_000;
    /** The longest class name School setup keeps (academics.school_class.name). */
    static final int MAX_CLASS_NAME = 40;

    static final String INSTRUCTIONS = "Instructions";
    static final String STAFF = "Staff";
    /** "Class 5 - A", "5 - A", "LKG - Rose": the class, a hyphen, the section. */
    private static final Pattern SECTION_SHEET = Pattern.compile("^(.+?)\\s*-\\s*([^-]+)$");
    private static final Pattern CLASS_NUMBER = Pattern.compile("^\\d{1,2}$");
    /** Line numbers in a batch: sheet index * this + row number, so they are unique across sheets. */
    private static final int LINE_STRIDE = 2_000_000;

    public enum SheetKind {
        STUDENTS, STAFF, IGNORED
    }

    /** Whether the class and section of a student sheet exist, will be created, or are missing. */
    public enum SectionStatus {
        EXISTS, NEW_SECTION, NEW_CLASS, MISSING
    }

    /** A problem. {@code row} is the spreadsheet row (null for a problem with the sheet itself). */
    public record Problem(Integer row, String column, String message) {
    }

    public record SheetResult(String name, SheetKind kind, String className, String sectionName,
            SectionStatus section, int rows, int validRows, int invalidRows, List<Problem> problems,
            int problemsNotShown, List<String> ignoredColumns) {
    }

    public record ToCreate(List<String> classes, List<String> sections, List<String> departments) {
    }

    /**
     * The answer to a check or an import. {@code ready} is true when the file has rows and no problem at all.
     * {@code studentsCreated} and {@code staffCreated} stay 0 until a real import commits.
     */
    public record ImportResult(boolean dryRun, boolean committed, boolean createMissing, boolean ready,
            int studentRows, int staffRows, int validRows, int invalidRows, int sheetProblems, int studentsCreated,
            int staffCreated, ToCreate toCreate, List<SheetResult> sheets) {
    }

    /** A student sheet, resolved to a section of the school or to a section to create. */
    private record SectionPlan(String className, String sectionName, SectionStatus status, SectionInfo info) {
    }

    private final XlsxReader reader = new XlsxReader();
    private final StudentImportService students;
    private final StaffImportService staff;
    private final AcademicsDirectory directory;
    private final AcademicsService academics;
    private final AuditService audit;

    public SchoolImportService(StudentImportService students, StaffImportService staff, AcademicsDirectory directory,
            AcademicsService academics, AuditService audit) {
        this.students = students;
        this.staff = staff;
        this.directory = directory;
        this.academics = academics;
        this.audit = audit;
    }

    /**
     * Checks the workbook and, unless {@code dryRun} or anything needs fixing, imports it. With {@code createMissing}
     * a student sheet for a class or section the school has not set up adds it, and a department the staff sheet
     * names is added; without it those are problems.
     */
    @Transactional
    public ImportResult run(byte[] file, boolean dryRun, boolean createMissing, Actor actor) {
        TenantContext.require();
        List<Sheet> sheets;
        try {
            sheets = reader.read(file);
        } catch (XlsxReader.XlsxException e) {
            throw ApiException.badRequest(e.getMessage(), "file");
        }
        if (directory.currentYear().isEmpty()) {
            throw ApiException.badRequest("Set up the current academic year in School setup first.", "file");
        }

        Map<String, SectionInfo> existingSections = new HashMap<>();
        for (SectionInfo s : directory.sections()) {
            existingSections.put(key(s.className(), s.name()), s);
        }
        Map<String, ClassView> existingClasses = new HashMap<>();
        for (ClassView c : academics.classes()) {
            existingClasses.put(c.name().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "), c);
        }

        List<Draft> drafts = new ArrayList<>();
        Map<String, String> sectionSheets = new HashMap<>(); // section key -> first sheet name
        Map<String, UUID> plannedClassIds = new LinkedHashMap<>(); // class key -> temporary id
        Map<String, String> plannedClassNames = new LinkedHashMap<>();
        List<StudentImportService.SourceRow> studentRows = new ArrayList<>();
        List<StaffImportService.SourceRow> staffRows = new ArrayList<>();
        boolean staffSheetSeen = false;

        for (int index = 0; index < sheets.size(); index++) {
            Sheet sheet = sheets.get(index);
            String name = sheet.name().trim();
            Draft draft = new Draft(name, index);
            drafts.add(draft);
            if (name.equalsIgnoreCase(INSTRUCTIONS)) {
                draft.kind = SheetKind.IGNORED;
                continue;
            }
            List<Row> rows = sheet.rows().stream().filter(r -> !r.isBlank()).toList();
            if (name.equalsIgnoreCase(STAFF)) {
                draft.kind = SheetKind.STAFF;
                if (staffSheetSeen) {
                    draft.sheetProblem("There is more than one Staff sheet. Put all staff on one sheet.");
                    continue;
                }
                staffSheetSeen = true;
                readStaffSheet(draft, rows, staffRows);
                continue;
            }
            draft.kind = SheetKind.STUDENTS;
            Matcher m = SECTION_SHEET.matcher(name);
            if (!m.matches() || m.group(1).isBlank() || m.group(2).isBlank()) {
                if (rows.size() > 1 || (rows.size() == 1 && !looksLikeStudentHeader(rows.getFirst()))) {
                    draft.sheetProblem("Name the sheet after its class and section, like \"Class 5 - A\".");
                } else {
                    draft.kind = SheetKind.IGNORED;
                }
                continue;
            }
            String className = m.group(1).trim().replaceAll("\\s+", " ");
            String sectionName = m.group(2).trim();
            SectionPlan plan = plan(className, sectionName, existingSections, existingClasses, plannedClassIds,
                    plannedClassNames, createMissing);
            draft.className = plan.className();
            draft.sectionName = plan.sectionName();
            draft.section = plan.status();
            String sectionKey = key(plan.className(), plan.sectionName());
            String firstSheet = sectionSheets.putIfAbsent(sectionKey, name);
            if (firstSheet != null) {
                draft.sheetProblem("This is the same class and section as the sheet \"" + firstSheet
                        + "\". Put each section on one sheet.");
                continue;
            }
            if (plan.status() != SectionStatus.EXISTS && plan.sectionName().length() > 20) {
                draft.sheetProblem("A section name can have at most 20 characters.");
                continue;
            }
            if (plan.status() == SectionStatus.NEW_CLASS && plan.className().length() > MAX_CLASS_NAME) {
                draft.sheetProblem("A class name can have at most " + MAX_CLASS_NAME + " characters.");
                continue;
            }
            if (plan.status() == SectionStatus.MISSING) {
                draft.sheetProblem(plan.className() + " - " + plan.sectionName() + " is not set up in School setup. "
                        + "Add it there, or let the import create missing classes and sections.");
            }
            draft.plan = plan;
            readStudentSheet(draft, rows, plan, studentRows);
        }

        int studentCount = studentRows.size();
        int staffCount = staffRows.size();
        if (studentCount > MAX_STUDENTS) {
            throw ApiException.badRequest("A file can have at most " + format(MAX_STUDENTS) + " students. This one has "
                    + format(studentCount) + ". Split it into two files.", "file");
        }
        if (staffCount > MAX_STAFF) {
            throw ApiException.badRequest("A file can have at most " + format(MAX_STAFF) + " staff. This one has "
                    + format(staffCount) + ".", "file");
        }
        if (studentCount + staffCount == 0 && drafts.stream().noneMatch(d -> !d.problems.isEmpty())) {
            throw ApiException.badRequest("The file has no students or staff in it. Fill in the sheets below the "
                    + "headings and try again.", "file");
        }

        List<SectionInfo> planned = drafts.stream()
                .filter(d -> d.plan != null && d.plan.status() != SectionStatus.EXISTS)
                .map(d -> d.plan.info()).toList();
        StudentBatch studentBatch = students.check(studentRows, planned);
        StaffBatch staffBatch = staff.check(staffRows, createMissing);

        for (var e : studentBatch.errors()) {
            drafts.get(e.row() / LINE_STRIDE).rowProblem(e.row() % LINE_STRIDE,
                    ImportColumns.heading(ImportColumns.STUDENT, e.column()), e.message());
        }
        for (Integer line : studentBatch.invalidLines()) {
            drafts.get(line / LINE_STRIDE).invalidRows++;
        }
        for (var e : staffBatch.errors()) {
            drafts.get(e.row() / LINE_STRIDE).rowProblem(e.row() % LINE_STRIDE,
                    ImportColumns.heading(ImportColumns.STAFF, e.column()), e.message());
        }
        for (Integer line : staffBatch.invalidLines()) {
            drafts.get(line / LINE_STRIDE).invalidRows++;
        }

        List<String> newClasses = drafts.stream()
                .filter(d -> d.plan != null && d.plan.status() == SectionStatus.NEW_CLASS)
                .map(d -> d.plan.className()).distinct().toList();
        List<String> newSections = planned.stream().map(s -> s.className() + " - " + s.name()).toList();
        ToCreate toCreate = new ToCreate(createMissing ? newClasses : List.of(),
                createMissing ? newSections : List.of(), staffBatch.newDepartments());

        int invalid = drafts.stream().mapToInt(d -> d.invalidRows).sum();
        int sheetProblems = drafts.stream().mapToInt(d -> d.sheetProblems).sum();
        boolean ready = invalid == 0 && sheetProblems == 0 && studentCount + staffCount > 0;
        int total = studentCount + staffCount;
        if (dryRun || !ready) {
            return result(dryRun, false, createMissing, ready, studentCount, staffCount, total - invalid, invalid,
                    sheetProblems, 0, 0, toCreate, drafts);
        }

        // Everything is clean: write it all, in this one transaction.
        Map<UUID, UUID> sectionIds = new HashMap<>();
        Map<UUID, UUID> classIds = new HashMap<>();
        for (SectionInfo s : planned) {
            UUID classId = s.classId();
            if (plannedClassIds.containsValue(classId)) {
                UUID real = classIds.get(classId);
                if (real == null) {
                    real = academics.createClass(s.className(), null, actor).id();
                    classIds.put(classId, real);
                }
                classId = real;
            }
            sectionIds.put(s.id(), academics.createSection(classId, s.name(), null, null, actor).id());
        }
        Map<String, Object> details = Map.of("source", "xlsx");
        int staffCreated = staffCount == 0 ? 0 : staff.commit(staffBatch, actor, details);
        int studentsCreated = studentCount == 0 ? 0 : students.commit(studentBatch, sectionIds, actor, details);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("students", studentsCreated);
        summary.put("staff", staffCreated);
        summary.put("studentSheets", drafts.stream().filter(d -> d.kind == SheetKind.STUDENTS).count());
        summary.put("classesAdded", classIds.size());
        summary.put("sectionsAdded", sectionIds.size());
        summary.put("departmentsAdded", staffBatch.newDepartments().size());
        Map<String, Integer> perSheet = new LinkedHashMap<>();
        drafts.stream().filter(d -> d.kind != SheetKind.IGNORED).forEach(d -> perSheet.put(d.name, d.rows));
        summary.put("rowsPerSheet", perSheet);
        if (actor == null) {
            audit.record("school.bulk_imported", "school", null, summary);
        } else {
            audit.record(actor, "school.bulk_imported", "school", null, summary);
        }
        return result(false, true, createMissing, true, studentCount, staffCount, total, 0, 0, studentsCreated,
                staffCreated, toCreate, drafts);
    }

    // ------------------------------------------------------------------ sheets

    private void readStudentSheet(Draft draft, List<Row> rows, SectionPlan plan,
            List<StudentImportService.SourceRow> out) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Integer> columns = header(draft, rows.getFirst(), true);
        if (columns == null) {
            return;
        }
        for (Row row : rows.subList(1, rows.size())) {
            Map<String, String> values = new HashMap<>();
            columns.forEach((field, index) -> values.put(field, row.cell(index)));
            values.put("class", plan.className());
            values.put("section", plan.sectionName());
            out.add(new StudentImportService.SourceRow(draft.index * LINE_STRIDE + row.number(),
                    "row " + row.number() + " of " + draft.name, values));
            draft.rows++;
        }
    }

    private void readStaffSheet(Draft draft, List<Row> rows, List<StaffImportService.SourceRow> out) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Integer> columns = header(draft, rows.getFirst(), false);
        if (columns == null) {
            return;
        }
        for (Row row : rows.subList(1, rows.size())) {
            Map<String, String> values = new HashMap<>();
            columns.forEach((field, index) -> values.put(field, row.cell(index)));
            out.add(new StaffImportService.SourceRow(draft.index * LINE_STRIDE + row.number(),
                    "row " + row.number() + " of " + draft.name, values));
            draft.rows++;
        }
    }

    /** The field of each heading of the first row, or null (with a sheet problem) when required ones are missing. */
    private static Map<String, Integer> header(Draft draft, Row first, boolean students) {
        List<ImportColumns.Column> expected = students ? ImportColumns.STUDENT : ImportColumns.STAFF;
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < first.cells().size(); i++) {
            String heading = first.cell(i).trim();
            if (heading.isEmpty()) {
                continue;
            }
            String field = students ? ImportColumns.studentField(heading) : ImportColumns.staffField(heading);
            if (field == null) {
                draft.ignoredColumns.add(heading);
            } else if (columns.putIfAbsent(field, i) != null) {
                draft.sheetProblem("The column " + heading + " appears twice in the first row.");
                return null;
            }
        }
        List<String> missing = expected.stream().filter(c -> c.required() && !columns.containsKey(c.field()))
                .map(ImportColumns.Column::heading).toList();
        if (!missing.isEmpty()) {
            draft.sheetProblem("The first row must hold the column headings. Missing: " + String.join(", ", missing)
                    + ".");
            return null;
        }
        return columns;
    }

    private static boolean looksLikeStudentHeader(Row row) {
        return row.cells().stream().anyMatch(c -> "admission_no".equals(ImportColumns.studentField(c)));
    }

    /** Matches a sheet to a section of the school, or plans the class and section to create. */
    private static SectionPlan plan(String className, String sectionName, Map<String, SectionInfo> sections,
            Map<String, ClassView> classes, Map<String, UUID> plannedClassIds, Map<String, String> plannedClassNames,
            boolean createMissing) {
        List<String> candidates = CLASS_NUMBER.matcher(className).matches()
                ? List.of(className, "Class " + className) : List.of(className);
        for (String c : candidates) {
            SectionInfo existing = sections.get(key(c, sectionName));
            if (existing != null) {
                return new SectionPlan(existing.className(), existing.name(), SectionStatus.EXISTS, existing);
            }
        }
        ClassView existingClass = null;
        for (String c : candidates) {
            existingClass = classes.get(c.toLowerCase(Locale.ROOT));
            if (existingClass != null) {
                break;
            }
        }
        if (existingClass != null) {
            SectionInfo info = new SectionInfo(UUID.randomUUID(), sectionName, null, existingClass.id(),
                    existingClass.name(), existingClass.displayOrder(), null, null);
            return new SectionPlan(existingClass.name(), sectionName,
                    createMissing ? SectionStatus.NEW_SECTION : SectionStatus.MISSING, info);
        }
        String newClassName = candidates.getLast();
        String classKey = newClassName.toLowerCase(Locale.ROOT);
        UUID classId = plannedClassIds.computeIfAbsent(classKey, k -> UUID.randomUUID());
        String shownName = plannedClassNames.computeIfAbsent(classKey, k -> newClassName);
        SectionInfo info = new SectionInfo(UUID.randomUUID(), sectionName, null, classId, shownName, 0, null, null);
        return new SectionPlan(shownName, sectionName, createMissing ? SectionStatus.NEW_CLASS : SectionStatus.MISSING,
                info);
    }

    private static String key(String className, String sectionName) {
        return className.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + "|"
                + sectionName.trim().toLowerCase(Locale.ROOT);
    }

    private static String format(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static ImportResult result(boolean dryRun, boolean committed, boolean createMissing, boolean ready,
            int studentRows, int staffRows, int validRows, int invalidRows, int sheetProblems, int studentsCreated,
            int staffCreated, ToCreate toCreate, List<Draft> drafts) {
        int budget = MAX_PROBLEMS;
        List<SheetResult> sheets = new ArrayList<>();
        for (Draft d : drafts) {
            d.problems.sort((a, b) -> Integer.compare(a.row() == null ? 0 : a.row(), b.row() == null ? 0 : b.row()));
            int shown = Math.min(d.problems.size(), Math.min(MAX_PROBLEMS_PER_SHEET, budget));
            budget -= shown;
            sheets.add(new SheetResult(d.name, d.kind, d.className, d.sectionName, d.section, d.rows,
                    d.rows - d.invalidRows, d.invalidRows, List.copyOf(d.problems.subList(0, shown)),
                    d.problems.size() - shown, List.copyOf(d.ignoredColumns)));
        }
        return new ImportResult(dryRun, committed, createMissing, ready, studentRows, staffRows, validRows,
                invalidRows, sheetProblems, studentsCreated, staffCreated, toCreate, sheets);
    }

    /** What is known about one sheet while the file is checked. */
    private static final class Draft {

        final String name;
        final int index;
        SheetKind kind = SheetKind.IGNORED;
        String className;
        String sectionName;
        SectionStatus section;
        SectionPlan plan;
        int rows;
        int invalidRows;
        int sheetProblems;
        final List<Problem> problems = new ArrayList<>();
        final List<String> ignoredColumns = new ArrayList<>();

        Draft(String name, int index) {
            this.name = name;
            this.index = index;
        }

        void sheetProblem(String message) {
            sheetProblems++;
            problems.add(new Problem(null, null, message));
        }

        void rowProblem(int row, String column, String message) {
            problems.add(new Problem(row, column, message));
        }
    }
}
