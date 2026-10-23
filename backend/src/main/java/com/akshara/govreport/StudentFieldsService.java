package com.akshara.govreport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.govreport.GovReportForms.FamilyFieldsForm;
import com.akshara.govreport.GovReportForms.StudentFieldsForm;
import com.akshara.govreport.GovReportTypes.CodeListType;
import com.akshara.govreport.GovReportTypes.UpdatedVia;
import com.akshara.govreport.GovReportViews.BulkResult;
import com.akshara.govreport.GovReportViews.CodeView;
import com.akshara.govreport.GovReportViews.CompletenessReport;
import com.akshara.govreport.GovReportViews.FamilyFields;
import com.akshara.govreport.GovReportViews.FamilyView;
import com.akshara.govreport.GovReportViews.ImportResult;
import com.akshara.govreport.GovReportViews.RowError;
import com.akshara.govreport.GovReportViews.SectionCompleteness;
import com.akshara.govreport.GovReportViews.SectionGrid;
import com.akshara.govreport.GovReportViews.StudentFieldsRow;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * Students' government fields: the bulk entry grid by section, the CSV import with validation, the completeness
 * report by section, and what a parent may fill from the family app (the mother tongue, and the APAAR ID once).
 */
@Service
@Transactional
public class StudentFieldsService {

    /** The fields a student's UDISE+ record needs, in the order the grid shows them. */
    static final List<String> REQUIRED = List.of("apaarId", "pen", "socialCategory", "religion", "motherTongue",
            "bpl", "cwsnType", "rteAdmitted");

    /** The columns of the import file, in order; admission_no identifies the student. */
    static final List<String> IMPORT_COLUMNS = List.of("admission_no", "apaar_id", "pen", "social_category",
            "religion", "mother_tongue", "bpl", "cwsn_type", "rte_admitted", "previous_school_udise");
    static final int MAX_ROWS = 2_000;

    private final StudentGovRecordRepository records;
    private final StudentService students;
    private final StudentRoster roster;
    private final AcademicsDirectory academics;
    private final CodeListService codes;
    private final SchoolProfileService profiles;
    private final AuditService audit;

    StudentFieldsService(StudentGovRecordRepository records, StudentService students, StudentRoster roster,
            AcademicsDirectory academics, CodeListService codes, SchoolProfileService profiles, AuditService audit) {
        this.records = records;
        this.students = students;
        this.roster = roster;
        this.academics = academics;
        this.codes = codes;
        this.profiles = profiles;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ the grid

    @Transactional
    public SectionGrid grid(UUID sectionId, UUID yearId) {
        YearInfo year = profiles.year(yearId);
        SectionInfo section = academics.section(sectionId).orElseThrow(() -> ApiException.notFound("Section"));
        List<StudentRow> pupils = studentsOf(year.id(), section.id());
        List<StudentFieldsRow> rows = rows(pupils);
        int complete = (int) rows.stream().filter(r -> r.missing().isEmpty()).count();
        return new SectionGrid(section.id(), section.label(), year.id(), rows.size(), complete, rows);
    }

    /** Active students enrolled in the section (or, with a null section, the whole year), as the student list. */
    List<StudentRow> studentsOf(UUID yearId, UUID sectionId) {
        List<StudentRow> all = new ArrayList<>();
        for (int page = 0;; page++) {
            List<StudentRow> items = students.list(new StudentQuery(yearId, null, sectionId, StudentStatus.ACTIVE,
                    null, page, StudentService.MAX_PAGE_SIZE)).items();
            all.addAll(items);
            if (items.size() < StudentService.MAX_PAGE_SIZE) {
                return all;
            }
        }
    }

    /** The grid rows of these students, with their saved fields (none when nothing was entered yet). */
    List<StudentFieldsRow> rows(List<StudentRow> pupils) {
        Map<UUID, StudentGovRecord> saved = recordsOf(pupils.stream().map(StudentRow::id).toList());
        List<StudentFieldsRow> rows = new ArrayList<>();
        for (StudentRow s : pupils) {
            rows.add(row(s, saved.get(s.id())));
        }
        return rows;
    }

    Map<UUID, StudentGovRecord> recordsOf(Collection<UUID> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        return records.findByStudentIdIn(studentIds).stream()
                .collect(Collectors.toMap(StudentGovRecord::getStudentId, Function.identity()));
    }

    static StudentFieldsRow row(StudentRow s, StudentGovRecord r) {
        List<String> missing = missing(r);
        if (r == null) {
            return new StudentFieldsRow(s.id(), s.fullName(), s.admissionNo(), s.rollNo(), s.gender(),
                    s.dateOfBirth(), s.className(), s.sectionName(), null, false, null, null, null, null, null, null,
                    null, null, missing, null, null, null);
        }
        return new StudentFieldsRow(s.id(), s.fullName(), s.admissionNo(), s.rollNo(), s.gender(), s.dateOfBirth(),
                s.className(), s.sectionName(), r.getApaarId(), r.isApaarLocked(), r.getPen(), r.getSocialCategory(),
                r.getReligion(), r.getMotherTongue(), r.getBpl(), r.getCwsnType(), r.getRteAdmitted(),
                r.getPreviousSchoolUdise(), missing, r.getUpdatedVia(), r.getUpdatedByName(), r.getUpdatedAt());
    }

    /** The required fields still empty. A CWSN type is "filled" once the record exists (null means none). */
    static List<String> missing(StudentGovRecord r) {
        if (r == null) {
            return REQUIRED;
        }
        List<String> missing = new ArrayList<>();
        if (r.getApaarId() == null) {
            missing.add("apaarId");
        }
        if (r.getPen() == null) {
            missing.add("pen");
        }
        if (r.getSocialCategory() == null) {
            missing.add("socialCategory");
        }
        if (r.getReligion() == null) {
            missing.add("religion");
        }
        if (r.getMotherTongue() == null) {
            missing.add("motherTongue");
        }
        if (r.getBpl() == null) {
            missing.add("bpl");
        }
        if (r.getRteAdmitted() == null) {
            missing.add("rteAdmitted");
        }
        return missing;
    }

    // ------------------------------------------------------------------ saving

    /** Saves the grid's rows. Codes are checked against the lists in force; a bad row answers 400 on its field. */
    public BulkResult save(List<StudentFieldsForm> entries, Actor actor, UpdatedVia via) {
        TenantContext.require();
        LocalDate today = GovReportAuth.today();
        Lists lists = lists(today);
        Map<UUID, StudentRow> known = studentsById(entries.stream().map(StudentFieldsForm::studentId).toList());
        Map<UUID, StudentGovRecord> saved = recordsOf(known.keySet());
        checkUnique(entries, saved);
        List<StudentFieldsRow> rows = new ArrayList<>();
        int i = 0;
        for (StudentFieldsForm f : entries) {
            String prefix = "entries[" + i + "].";
            StudentRow s = known.get(f.studentId());
            if (s == null) {
                throw ApiException.badRequest("That student is not in this school.", prefix + "studentId");
            }
            String category = lists.check(CodeListType.SOCIAL_CATEGORY, f.socialCategory(),
                    prefix + "socialCategory");
            String religion = lists.check(CodeListType.RELIGION, f.religion(), prefix + "religion");
            String tongue = lists.check(CodeListType.MOTHER_TONGUE, f.motherTongue(), prefix + "motherTongue");
            String cwsn = lists.check(CodeListType.CWSN_TYPE, f.cwsnType(), prefix + "cwsnType");
            StudentGovRecord r = saved.computeIfAbsent(s.id(), StudentGovRecord::new);
            r.updateByOffice(GovReportAuth.blankToNull(f.apaarId()), f.apaarLocked(),
                    GovReportAuth.blankToNull(f.pen()), category, religion, tongue, f.bpl(), cwsn, f.rteAdmitted(),
                    GovReportAuth.blankToNull(f.previousSchoolUdise()), actor, via);
            records.save(r);
            rows.add(row(s, r));
            i++;
        }
        records.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("students", entries.size());
        details.put("via", via.name());
        audit.record(actor, via == UpdatedVia.IMPORT ? "govreport_fields.imported" : "govreport_fields.saved",
                "govreport_fields", null, details);
        return new BulkResult(rows.size(), rows);
    }

    /**
     * A student appears once in a save (400), and an APAAR ID belongs to one student of the school (409, also when
     * two rows of the save carry it). A student of the save whose ID is changing frees the old one.
     */
    private void checkUnique(List<StudentFieldsForm> entries, Map<UUID, StudentGovRecord> saved) {
        Set<UUID> students = new java.util.HashSet<>();
        Map<String, UUID> claimed = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            StudentFieldsForm f = entries.get(i);
            if (!students.add(f.studentId())) {
                throw ApiException.badRequest("This student appears more than once.",
                        "entries[" + i + "].studentId");
            }
            String apaar = GovReportAuth.blankToNull(f.apaarId());
            if (apaar != null && claimed.putIfAbsent(apaar, f.studentId()) != null) {
                throw ApiException.conflict("This APAAR ID is given for two students.", "entries[" + i + "].apaarId");
            }
        }
        if (claimed.isEmpty()) {
            return;
        }
        Map<UUID, String> newIds = new HashMap<>();
        entries.forEach(f -> newIds.put(f.studentId(), GovReportAuth.blankToNull(f.apaarId())));
        for (StudentGovRecord other : records.findByApaarIdIn(claimed.keySet())) {
            UUID claimant = claimed.get(other.getApaarId());
            boolean freed = newIds.containsKey(other.getStudentId())
                    && !other.getApaarId().equals(newIds.get(other.getStudentId()));
            if (!other.getStudentId().equals(claimant) && !freed) {
                int i = 0;
                while (!entries.get(i).studentId().equals(claimant)) {
                    i++;
                }
                throw ApiException.conflict("This APAAR ID is already recorded for another student.",
                        "entries[" + i + "].apaarId");
            }
        }
    }

    /** True when another student of the school already has this APAAR ID. */
    private boolean apaarTaken(String apaarId, UUID studentId) {
        return records.findByApaarIdIn(List.of(apaarId)).stream()
                .anyMatch(r -> !r.getStudentId().equals(studentId));
    }

    private Map<UUID, StudentRow> studentsById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, StudentRow> result = new HashMap<>();
        // The student list gives names and classes; ids of another school are simply missing.
        Set<UUID> wanted = Set.copyOf(ids);
        for (int page = 0;; page++) {
            List<StudentRow> items = students.list(new StudentQuery(null, null, null, null, null, page,
                    StudentService.MAX_PAGE_SIZE)).items();
            for (StudentRow s : items) {
                if (wanted.contains(s.id())) {
                    result.put(s.id(), s);
                }
            }
            if (items.size() < StudentService.MAX_PAGE_SIZE || result.size() == wanted.size()) {
                return result;
            }
        }
    }

    /** The code lists in force, for checking codes. */
    record Lists(Map<CodeListType, Map<String, CodeView>> byList) {

        /** The code, upper-cased, or null when blank; 400 when it is not in the list. */
        String check(CodeListType list, String code, String field) {
            String c = GovReportAuth.blankToNull(code);
            if (c == null) {
                return null;
            }
            c = c.toUpperCase(Locale.ROOT);
            if (!byList.get(list).containsKey(c)) {
                throw ApiException.badRequest("Choose a code from the " + list.name().toLowerCase(Locale.ROOT)
                        .replace('_', ' ') + " list.", field);
            }
            return c;
        }

        String problem(CodeListType list, String code) {
            String c = GovReportAuth.blankToNull(code);
            if (c == null || byList.get(list).containsKey(c.toUpperCase(Locale.ROOT))) {
                return null;
            }
            return "Not in the " + list.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " list: " + c;
        }
    }

    Lists lists(LocalDate day) {
        Map<CodeListType, Map<String, CodeView>> byList = new LinkedHashMap<>();
        for (CodeListType t : List.of(CodeListType.SOCIAL_CATEGORY, CodeListType.RELIGION,
                CodeListType.MOTHER_TONGUE, CodeListType.CWSN_TYPE)) {
            byList.put(t, codes.inForce(t, day));
        }
        return new Lists(byList);
    }

    // ------------------------------------------------------------------ import

    /** The import template: the header line and one example row. */
    public String template() {
        GovCsv csv = new GovCsv(IMPORT_COLUMNS.toArray(String[]::new));
        csv.row("ADM-2026-001", "123456789012", "PEN123456", "GEN", "HINDU", "TELUGU", "N", "", "N", "");
        return csv.toString();
    }

    /**
     * Checks (dryRun) or imports a CSV of government fields by admission number. A real import with any bad row
     * writes nothing. Blank cells leave a field unchanged; "-" clears it.
     */
    public ImportResult importCsv(String csv, boolean dryRun, Actor actor) {
        TenantContext.require();
        List<GovCsvParser.Row> rows = GovCsvParser.parse(csv);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("The file is empty.", "csv");
        }
        List<String> header = rows.getFirst().values().stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT).replace(' ', '_')).toList();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i), i);
        }
        if (!col.containsKey("admission_no")) {
            throw ApiException.badRequest("The first line must name the columns, starting with admission_no.", "csv");
        }
        List<GovCsvParser.Row> data = rows.subList(1, rows.size()).stream().filter(r -> !r.isBlank()).toList();
        if (data.size() > MAX_ROWS) {
            throw ApiException.badRequest("A file can have at most " + MAX_ROWS + " rows.", "csv");
        }
        Map<String, StudentRow> byAdmission = new HashMap<>();
        for (int page = 0;; page++) {
            List<StudentRow> items = students.list(new StudentQuery(null, null, null, null, null, page,
                    StudentService.MAX_PAGE_SIZE)).items();
            items.forEach(s -> byAdmission.put(s.admissionNo().toLowerCase(Locale.ROOT), s));
            if (items.size() < StudentService.MAX_PAGE_SIZE) {
                break;
            }
        }
        Lists lists = lists(GovReportAuth.today());
        List<RowError> errors = new ArrayList<>();
        List<StudentFieldsForm> valid = new ArrayList<>();
        Map<UUID, StudentGovRecord> saved = recordsOf(byAdmission.values().stream().map(StudentRow::id).toList());
        Set<UUID> seen = new java.util.HashSet<>();
        Map<String, UUID> apaarInFile = new HashMap<>();
        for (GovCsvParser.Row row : data) {
            int before = errors.size();
            String admission = cell(row, col, "admission_no");
            StudentRow s = admission == null ? null : byAdmission.get(admission.toLowerCase(Locale.ROOT));
            if (s == null) {
                errors.add(new RowError(row.line(), "admission_no", "No student with admission number "
                        + (admission == null ? "(blank)" : admission) + "."));
                continue;
            }
            if (!seen.add(s.id())) {
                errors.add(new RowError(row.line(), "admission_no", "Admission number " + admission
                        + " appears more than once."));
                continue;
            }
            StudentGovRecord current = saved.get(s.id());
            String apaar = merge(cell(row, col, "apaar_id"), current == null ? null : current.getApaarId());
            if (apaar != null && !apaar.matches("[0-9]{12}")) {
                errors.add(new RowError(row.line(), "apaar_id", "An APAAR ID has 12 digits."));
            } else if (apaar != null && apaarInFile.putIfAbsent(apaar, s.id()) != null) {
                errors.add(new RowError(row.line(), "apaar_id", "APAAR ID " + apaar
                        + " is given for another student in this file."));
            } else if (apaar != null && (current == null || !apaar.equals(current.getApaarId()))
                    && apaarTaken(apaar, s.id())) {
                errors.add(new RowError(row.line(), "apaar_id", "APAAR ID " + apaar
                        + " is already recorded for another student."));
            }
            String pen = merge(cell(row, col, "pen"), current == null ? null : current.getPen());
            if (pen != null && !pen.matches("[A-Za-z0-9]{1,20}")) {
                errors.add(new RowError(row.line(), "pen", "A PEN has up to 20 letters or digits."));
            }
            String category = code(row, col, "social_category", CodeListType.SOCIAL_CATEGORY, lists, errors,
                    current == null ? null : current.getSocialCategory());
            String religion = code(row, col, "religion", CodeListType.RELIGION, lists, errors,
                    current == null ? null : current.getReligion());
            String tongue = code(row, col, "mother_tongue", CodeListType.MOTHER_TONGUE, lists, errors,
                    current == null ? null : current.getMotherTongue());
            String cwsn = code(row, col, "cwsn_type", CodeListType.CWSN_TYPE, lists, errors,
                    current == null ? null : current.getCwsnType());
            Boolean bpl = flag(row, col, "bpl", errors, current == null ? null : current.getBpl());
            Boolean rte = flag(row, col, "rte_admitted", errors, current == null ? null : current.getRteAdmitted());
            String previous = merge(cell(row, col, "previous_school_udise"),
                    current == null ? null : current.getPreviousSchoolUdise());
            if (previous != null && !previous.matches("[0-9]{11}")) {
                errors.add(new RowError(row.line(), "previous_school_udise", "A UDISE code has 11 digits."));
            }
            if (errors.size() == before) {
                valid.add(new StudentFieldsForm(s.id(), apaar, current != null && current.isApaarLocked() ? true
                        : null, pen, category, religion, tongue, bpl, cwsn, rte, previous));
            }
        }
        int invalid = data.size() - valid.size();
        if (dryRun || invalid > 0) {
            return new ImportResult(dryRun, false, data.size(), valid.size(), invalid, 0, errors);
        }
        int count = valid.isEmpty() ? 0 : save(valid, actor, UpdatedVia.IMPORT).saved();
        return new ImportResult(false, true, data.size(), valid.size(), 0, count, errors);
    }

    private static String cell(GovCsvParser.Row row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= row.values().size()) {
            return null;
        }
        return GovReportAuth.blankToNull(row.values().get(i));
    }

    /** A blank cell keeps the current value; "-" clears it. */
    private static String merge(String cell, String current) {
        if (cell == null) {
            return current;
        }
        return "-".equals(cell) ? null : cell;
    }

    private static String code(GovCsvParser.Row row, Map<String, Integer> col, String name, CodeListType list,
            Lists lists, List<RowError> errors, String current) {
        String value = merge(cell(row, col, name), current);
        if (value == null) {
            return null;
        }
        String problem = lists.problem(list, value);
        if (problem != null) {
            errors.add(new RowError(row.line(), name, problem));
            return null;
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static Boolean flag(GovCsvParser.Row row, Map<String, Integer> col, String name, List<RowError> errors,
            Boolean current) {
        String cell = cell(row, col, name);
        if (cell == null) {
            return current;
        }
        if ("-".equals(cell)) {
            return null;
        }
        return switch (cell.toUpperCase(Locale.ROOT)) {
            case "Y", "YES", "TRUE", "1" -> Boolean.TRUE;
            case "N", "NO", "FALSE", "0" -> Boolean.FALSE;
            default -> {
                errors.add(new RowError(row.line(), name, "Use Y or N."));
                yield null;
            }
        };
    }

    // ------------------------------------------------------------------ completeness

    @Transactional
    public CompletenessReport completeness(UUID yearId) {
        YearInfo year = profiles.year(yearId);
        List<StudentRow> pupils = studentsOf(year.id(), null);
        Map<UUID, StudentGovRecord> saved = recordsOf(pupils.stream().map(StudentRow::id).toList());
        Map<UUID, SectionInfo> sections = academics.sections().stream()
                .collect(Collectors.toMap(SectionInfo::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<UUID, List<StudentRow>> bySection = new LinkedHashMap<>();
        for (SectionInfo s : sections.values()) {
            bySection.put(s.id(), new ArrayList<>());
        }
        for (StudentRow s : pupils) {
            if (s.sectionId() != null) {
                bySection.computeIfAbsent(s.sectionId(), k -> new ArrayList<>()).add(s);
            }
        }
        List<SectionCompleteness> rows = new ArrayList<>();
        int complete = 0;
        for (Map.Entry<UUID, List<StudentRow>> e : bySection.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            Map<String, Integer> missingByField = new LinkedHashMap<>();
            REQUIRED.forEach(f -> missingByField.put(f, 0));
            int done = 0;
            for (StudentRow s : e.getValue()) {
                List<String> missing = missing(saved.get(s.id()));
                if (missing.isEmpty()) {
                    done++;
                }
                missing.forEach(f -> missingByField.merge(f, 1, Integer::sum));
            }
            complete += done;
            SectionInfo info = sections.get(e.getKey());
            int n = e.getValue().size();
            rows.add(new SectionCompleteness(e.getKey(), info == null ? "—" : info.label(), n, done,
                    (int) Math.floor(done * 100.0 / n), missingByField));
        }
        int n = pupils.size();
        return new CompletenessReport(year.id(), year.name(), n, complete, n == 0 ? 100
                : (int) Math.floor(complete * 100.0 / n), rows);
    }

    // ------------------------------------------------------------------ the family app

    @Transactional
    public FamilyView mine(UUID userId) {
        TenantContext.require();
        List<ChildView> children = students.childrenOf(userId);
        Map<UUID, StudentGovRecord> saved = recordsOf(children.stream().map(ChildView::id).toList());
        List<FamilyFields> fields = children.stream().map(c -> {
            StudentGovRecord r = saved.get(c.id());
            return new FamilyFields(c.id(), c.fullName(), c.className(), c.sectionName(),
                    r == null ? null : r.getMotherTongue(), r == null ? null : r.getApaarId(),
                    r == null || (r.getApaarId() == null && !r.isApaarLocked()));
        }).toList();
        List<CodeView> tongues = codes.inForce(CodeListType.MOTHER_TONGUE, GovReportAuth.today()).values().stream()
                .toList();
        return new FamilyView(fields, tongues);
    }

    /** A parent fills a child's mother tongue, and the APAAR ID once. 404 for a child that is not theirs. */
    public FamilyView saveMine(UUID userId, UUID studentId, FamilyFieldsForm form, Actor actor) {
        TenantContext.require();
        if (!roster.childIdsOf(userId).contains(studentId)) {
            throw ApiException.notFound("Student");
        }
        Lists lists = lists(GovReportAuth.today());
        String tongue = lists.check(CodeListType.MOTHER_TONGUE, form.motherTongue(), "motherTongue");
        String apaar = GovReportAuth.blankToNull(form.apaarId());
        StudentGovRecord r = records.findByStudentId(studentId).orElseGet(() -> new StudentGovRecord(studentId));
        if (apaar != null && (r.getApaarId() != null || r.isApaarLocked()) && !apaar.equals(r.getApaarId())) {
            throw ApiException.badRequest("The APAAR ID has been recorded; ask the school office to change it.",
                    "apaarId");
        }
        if (apaar != null && !apaar.equals(r.getApaarId()) && apaarTaken(apaar, studentId)) {
            throw ApiException.conflict("This APAAR ID is already recorded for another student; check it with the "
                    + "school office.", "apaarId");
        }
        r.updateByFamily(tongue, apaar, actor);
        records.saveAndFlush(r);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentId", studentId.toString());
        details.put("motherTongue", tongue);
        details.put("apaarGiven", apaar != null);
        audit.record(actor, "govreport_fields.family_updated", "govreport_fields", r.getId(), details);
        return mine(userId);
    }
}
