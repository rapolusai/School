package com.akshara.govreport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.FeeSetupService;
import com.akshara.fees.FeeViews.StructureSummary;
import com.akshara.govreport.GovReportTypes.CodeListType;
import com.akshara.govreport.GovReportTypes.ExportKind;
import com.akshara.govreport.GovReportViews.CodeView;
import com.akshara.govreport.GovReportViews.ExportPreview;
import com.akshara.govreport.GovReportViews.ExportTable;
import com.akshara.govreport.GovReportViews.TeacherCounts;
import com.akshara.platform.SchoolProfile;
import com.akshara.platform.TenantDirectory;
import com.akshara.reports.Xlsx;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffService;
import com.akshara.staff.StaffService.StaffDetail;
import com.akshara.staff.StaffService.StaffQuery;
import com.akshara.staff.StaffService.StaffRow;
import com.akshara.staff.StaffStatus;
import com.akshara.students.Gender;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.Family;
import com.akshara.students.StudentRoster.FamilyGuardian;
import com.akshara.students.StudentService.StudentRow;

/**
 * The files the school uploads to the government portals itself: the UDISE+ DCF-style tables (school profile,
 * enrolment by class, gender and category, teachers, infrastructure, facilities), the CBSE OASIS-like school data
 * sheet and the CBSE registration list for classes 9 and 11 (LOC-style columns). Every layout is our best reading
 * of the public specifications and is labelled "verify against the current portal template". Each is first shown
 * as a preview, and every download is audited with its "as of" date. Nothing is sent anywhere.
 */
@Service
@Transactional
public class GovExportService {

    static final String VERIFY = "Verify against the current portal template before uploading.";
    static final List<String> UDISE_TABLES = List.of("school", "enrolment", "teachers", "infrastructure",
            "facilities");

    private final SchoolProfileService profiles;
    private final StudentFieldsService fields;
    private final CodeListService codes;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final StudentRoster roster;
    private final StaffService staff;
    private final FeeSetupService fees;
    private final TenantDirectory tenants;
    private final AuditService audit;

    GovExportService(SchoolProfileService profiles, StudentFieldsService fields, CodeListService codes,
            AcademicsDirectory academics, AcademicsService academicsService, StudentRoster roster, StaffService staff,
            FeeSetupService fees, TenantDirectory tenants, AuditService audit) {
        this.profiles = profiles;
        this.fields = fields;
        this.codes = codes;
        this.academics = academics;
        this.academicsService = academicsService;
        this.roster = roster;
        this.staff = staff;
        this.fees = fees;
        this.tenants = tenants;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ UDISE+

    public ExportPreview udise(UUID yearId, LocalDate asOf) {
        YearInfo year = profiles.year(yearId);
        LocalDate day = asOf != null ? asOf : GovReportAuth.today();
        GovSchoolProfile p = profiles.saved(year.id());
        SchoolProfile school = tenants.profile(TenantContext.require()).orElse(null);
        Map<String, CodeView> categories = codes.inForce(CodeListType.SOCIAL_CATEGORY, day);
        Map<String, CodeView> schoolCategories = codes.inForce(CodeListType.SCHOOL_CATEGORY, day);
        Map<String, CodeView> managements = codes.inForce(CodeListType.MANAGEMENT, day);
        List<ExportTable> tables = new ArrayList<>();

        // School profile: one row of UDISE+ section 1 fields.
        tables.add(new ExportTable("school", "School profile", List.of("UDISE code", "School name", "State",
                "District code", "Block code", "School category", "Management", "Board", "Affiliation no",
                "Recognition authority", "Recognition no", "Lowest class", "Highest class", "Medium of instruction",
                "Academic year", "As of"),
                List.of(row(value(p, GovSchoolProfile::getUdiseCode, school == null ? null : school.udiseCode()),
                        school == null ? null : school.name(), value(p, GovSchoolProfile::getStateCode, null),
                        value(p, GovSchoolProfile::getDiseDistrictCode, null),
                        value(p, GovSchoolProfile::getDiseBlockCode, null),
                        codes.portalValue(schoolCategories, value(p, GovSchoolProfile::getSchoolCategoryCode, null)),
                        codes.portalValue(managements, value(p, GovSchoolProfile::getManagementCode, null)),
                        value(p, GovSchoolProfile::getBoard, school == null || school.board() == null ? null
                                : school.board().name()),
                        value(p, GovSchoolProfile::getAffiliationNo, null),
                        value(p, GovSchoolProfile::getRecognitionAuthority, null),
                        value(p, GovSchoolProfile::getRecognitionNo, null),
                        value(p, GovSchoolProfile::getLowestClass, null),
                        value(p, GovSchoolProfile::getHighestClass, null),
                        value(p, GovSchoolProfile::getMediumOfInstruction, null), year.name(), day))));

        // Enrolment by class, gender and social category.
        List<String> categoryCodes = new ArrayList<>(categories.keySet());
        List<String> columns = new ArrayList<>(List.of("Class", "Boys", "Girls", "Other", "Total"));
        for (String c : categoryCodes) {
            columns.add(categories.get(c).label() + " boys");
            columns.add(categories.get(c).label() + " girls");
        }
        columns.add("CWSN");
        columns.add("BPL");
        columns.add("RTE admitted");
        columns.add("Category not recorded");
        List<StudentRow> pupils = fields.studentsOf(year.id(), null);
        Map<UUID, StudentGovRecord> saved = fields.recordsOf(pupils.stream().map(StudentRow::id).toList());
        Map<UUID, ClassView> classes = academicsService.classes().stream()
                .collect(Collectors.toMap(ClassView::id, Function.identity()));
        Map<UUID, List<StudentRow>> byClass = new TreeMap<>(Comparator.comparingInt(
                (UUID id) -> classes.containsKey(id) ? classes.get(id).displayOrder() : Integer.MAX_VALUE)
                .thenComparing(UUID::toString));
        pupils.stream().filter(s -> s.classId() != null)
                .forEach(s -> byClass.computeIfAbsent(s.classId(), k -> new ArrayList<>()).add(s));
        List<List<Object>> enrolment = new ArrayList<>();
        for (Map.Entry<UUID, List<StudentRow>> e : byClass.entrySet()) {
            enrolment.add(enrolmentRow(classes.containsKey(e.getKey()) ? classes.get(e.getKey()).name() : "—",
                    e.getValue(), saved, categoryCodes));
        }
        enrolment.add(enrolmentRow("Total", pupils, saved, categoryCodes));
        tables.add(new ExportTable("enrolment", "Enrolment by class, gender and category", columns, enrolment));

        // Teachers.
        TeacherCounts t = profiles.teacherCounts();
        tables.add(new ExportTable("teachers", "Teachers", List.of("Teachers", "Trained (B.Ed/M.Ed/D.El.Ed)",
                "Post graduate", "Graduate", "Other", "Profile incomplete"),
                List.of(row(t.total(), t.trained(), t.postGraduate(), t.graduate(), t.other(),
                        t.profileIncomplete()))));

        // Infrastructure and facilities.
        tables.add(new ExportTable("infrastructure", "Infrastructure", List.of("Classrooms", "Other rooms",
                "Boys' toilets", "Girls' toilets", "CWSN toilets", "Boundary wall", "Ramps"),
                List.of(row(value(p, GovSchoolProfile::getClassrooms, null), value(p, GovSchoolProfile::getOtherRooms,
                        null), value(p, GovSchoolProfile::getToiletsBoys, null),
                        value(p, GovSchoolProfile::getToiletsGirls, null),
                        value(p, GovSchoolProfile::getToiletsCwsn, null), yn(value(p, GovSchoolProfile::getBoundaryWall,
                                null)), yn(value(p, GovSchoolProfile::getRamps, null))))));
        tables.add(new ExportTable("facilities", "Facilities", List.of("Drinking water", "Water source",
                "Electricity", "Library", "Library books", "Playground", "Computers", "Internet"),
                List.of(row(yn(value(p, GovSchoolProfile::getDrinkingWater, null)),
                        value(p, GovSchoolProfile::getDrinkingWaterSource, null),
                        yn(value(p, GovSchoolProfile::getElectricity, null)), yn(value(p, GovSchoolProfile::getLibrary,
                                null)), value(p, GovSchoolProfile::getLibraryBooks, null),
                        yn(value(p, GovSchoolProfile::getPlayground, null)), value(p, GovSchoolProfile::getComputers,
                                null), yn(value(p, GovSchoolProfile::getInternet, null))))));

        List<String> notes = new ArrayList<>();
        notes.add(VERIFY);
        notes.add("The school profile has " + SchoolProfileService.completeness(p).missing().size()
                + " field(s) still empty.");
        long incomplete = pupils.stream().filter(s -> !StudentFieldsService.missing(saved.get(s.id())).isEmpty())
                .count();
        notes.add(incomplete + " of " + pupils.size() + " students have government fields missing.");
        notes.add("Teacher counts are read from the staff profiles' qualifications text.");
        return new ExportPreview(ExportKind.UDISE_XLSX, year.id(), year.name(), day, true, notes, tables);
    }

    private List<Object> enrolmentRow(String label, List<StudentRow> pupils, Map<UUID, StudentGovRecord> saved,
            List<String> categoryCodes) {
        long boys = pupils.stream().filter(s -> s.gender() == Gender.MALE).count();
        long girls = pupils.stream().filter(s -> s.gender() == Gender.FEMALE).count();
        List<Object> row = new ArrayList<>(List.of(label, boys, girls, pupils.size() - boys - girls,
                (long) pupils.size()));
        long unknown = 0;
        long cwsn = 0;
        long bpl = 0;
        long rte = 0;
        Map<String, long[]> byCategory = new LinkedHashMap<>();
        categoryCodes.forEach(c -> byCategory.put(c, new long[2]));
        for (StudentRow s : pupils) {
            StudentGovRecord r = saved.get(s.id());
            String c = r == null ? null : r.getSocialCategory();
            if (c == null || !byCategory.containsKey(c)) {
                unknown++;
            } else {
                byCategory.get(c)[s.gender() == Gender.FEMALE ? 1 : 0]++;
            }
            if (r != null && r.getCwsnType() != null) {
                cwsn++;
            }
            if (r != null && Boolean.TRUE.equals(r.getBpl())) {
                bpl++;
            }
            if (r != null && Boolean.TRUE.equals(r.getRteAdmitted())) {
                rte++;
            }
        }
        for (String c : categoryCodes) {
            row.add(byCategory.get(c)[0]);
            row.add(byCategory.get(c)[1]);
        }
        row.add(cwsn);
        row.add(bpl);
        row.add(rte);
        row.add(unknown);
        return row;
    }

    // ------------------------------------------------------------------ CBSE OASIS

    public ExportPreview oasis(UUID yearId, LocalDate asOf) {
        YearInfo year = profiles.year(yearId);
        LocalDate day = asOf != null ? asOf : GovReportAuth.today();
        GovSchoolProfile p = profiles.saved(year.id());
        SchoolProfile school = tenants.profile(TenantContext.require()).orElse(null);
        List<ExportTable> tables = new ArrayList<>();
        tables.add(new ExportTable("affiliation", "Affiliation", List.of("School name", "Affiliation no",
                "Affiliation valid until", "UDISE code", "Board", "Address", "City", "Phone", "Email",
                "Medium of instruction", "Academic year", "As of"),
                List.of(row(school == null ? null : school.name(), value(p, GovSchoolProfile::getAffiliationNo, null),
                        value(p, GovSchoolProfile::getAffiliationValidUntil, null),
                        value(p, GovSchoolProfile::getUdiseCode, school == null ? null : school.udiseCode()),
                        value(p, GovSchoolProfile::getBoard, school == null || school.board() == null ? null
                                : school.board().name()), school == null ? null : school.address(),
                        school == null ? null : school.city(), school == null ? null : school.phone(),
                        school == null ? null : school.contactEmail(),
                        value(p, GovSchoolProfile::getMediumOfInstruction, null), year.name(), day))));

        // Sections per class with their strength.
        List<SectionInfo> sections = academics.sections();
        Map<UUID, Long> counts = roster.activePerSection(year.id());
        Map<String, List<SectionInfo>> byClass = new LinkedHashMap<>();
        sections.forEach(s -> byClass.computeIfAbsent(s.className(), k -> new ArrayList<>()).add(s));
        List<List<Object>> classRows = new ArrayList<>();
        for (Map.Entry<String, List<SectionInfo>> e : byClass.entrySet()) {
            long students = e.getValue().stream().mapToLong(s -> counts.getOrDefault(s.id(), 0L)).sum();
            classRows.add(row(e.getKey(), (long) e.getValue().size(), students,
                    e.getValue().stream().map(SectionInfo::name).collect(Collectors.joining(", "))));
        }
        tables.add(new ExportTable("sections", "Sections per class", List.of("Class", "Sections", "Students",
                "Section names"), classRows));

        // Staff with qualifications; PAN is not read at all (it lives in payroll and is shown masked there).
        List<List<Object>> staffRows = new ArrayList<>();
        for (int page = 0;; page++) {
            List<StaffRow> rows = staff.list(new StaffQuery(null, null, null, StaffStatus.ACTIVE, null, null, page,
                    StaffService.MAX_PAGE_SIZE)).items();
            for (StaffRow r : rows) {
                String qualifications = null;
                if (r.profileComplete()) {
                    StaffDetail d = staff.detail(r.userId());
                    qualifications = d.profile() == null ? null : d.profile().qualifications();
                }
                staffRows.add(row(r.employeeCode(), r.name(), r.designation(),
                        r.department() == null ? null : r.department().name(),
                        r.employmentType() == null ? null : r.employmentType().name(), r.dateOfJoining(),
                        qualifications, "XXXXXXXXXX"));
            }
            if (rows.size() < StaffService.MAX_PAGE_SIZE) {
                break;
            }
        }
        tables.add(new ExportTable("staff", "Staff", List.of("Employee code", "Name", "Designation", "Department",
                "Employment type", "Date of joining", "Qualifications", "PAN (masked)"), staffRows));

        tables.add(new ExportTable("infrastructure", "Infrastructure", List.of("Classrooms", "Other rooms",
                "Boys' toilets", "Girls' toilets", "CWSN toilets", "Library", "Library books", "Playground",
                "Computers", "Internet", "Ramps", "Boundary wall", "Drinking water", "Electricity"),
                List.of(row(value(p, GovSchoolProfile::getClassrooms, null), value(p, GovSchoolProfile::getOtherRooms,
                        null), value(p, GovSchoolProfile::getToiletsBoys, null),
                        value(p, GovSchoolProfile::getToiletsGirls, null),
                        value(p, GovSchoolProfile::getToiletsCwsn, null), yn(value(p, GovSchoolProfile::getLibrary,
                                null)), value(p, GovSchoolProfile::getLibraryBooks, null),
                        yn(value(p, GovSchoolProfile::getPlayground, null)), value(p, GovSchoolProfile::getComputers,
                                null), yn(value(p, GovSchoolProfile::getInternet, null)),
                        yn(value(p, GovSchoolProfile::getRamps, null)), yn(value(p, GovSchoolProfile::getBoundaryWall,
                                null)), yn(value(p, GovSchoolProfile::getDrinkingWater, null)),
                        yn(value(p, GovSchoolProfile::getElectricity, null))))));

        // Fee structure summary from the fees module's public views (annual total per class, in rupees).
        List<List<Object>> feeRows = new ArrayList<>();
        for (StructureSummary s : fees.structures(year.id())) {
            feeRows.add(row(s.className(), s.status() == null ? "No structure" : s.status(),
                    java.math.BigDecimal.valueOf(s.totalPaise(), 2), s.instalmentCount()));
        }
        tables.add(new ExportTable("fees", "Fee structure summary", List.of("Class", "Status", "Annual fee (Rs)",
                "Instalments"), feeRows));
        return new ExportPreview(ExportKind.OASIS_XLSX, year.id(), year.name(), day, true,
                List.of(VERIFY, "PAN is never exported; the column is masked on purpose."), tables);
    }

    // ------------------------------------------------------------------ CBSE registration list (classes 9 and 11)

    /** The classes whose name ends in 9 or 11 (the registration classes), for the preview's class choice. */
    public List<ClassView> registrationClasses() {
        TenantContext.require();
        return academicsService.classes().stream()
                .filter(c -> c.name().matches("(?i).*\\b(9|ix|11|xi)\\b.*")).toList();
    }

    public ExportPreview cbseRegistration(UUID yearId, UUID classId, LocalDate asOf) {
        YearInfo year = profiles.year(yearId);
        LocalDate day = asOf != null ? asOf : GovReportAuth.today();
        if (classId == null) {
            throw ApiException.badRequest("Choose the class (9 or 11).", "classId");
        }
        ClassView cls = academicsService.classes().stream().filter(c -> c.id().equals(classId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Class"));
        Map<String, CodeView> categories = codes.inForce(CodeListType.SOCIAL_CATEGORY, day);
        Map<String, CodeView> religions = codes.inForce(CodeListType.RELIGION, day);
        List<StudentRow> pupils = fields.studentsOf(year.id(), null).stream()
                .filter(s -> classId.equals(s.classId())).toList();
        Map<UUID, StudentGovRecord> saved = fields.recordsOf(pupils.stream().map(StudentRow::id).toList());
        List<UUID> sectionIds = cls.sections().stream().map(s -> s.id()).toList();
        Map<UUID, Family> families = roster.families(year.id(), sectionIds).stream()
                .collect(Collectors.toMap(Family::studentId, Function.identity(), (a, b) -> a));
        List<List<Object>> rows = new ArrayList<>();
        int serial = 0;
        for (StudentRow s : pupils) {
            StudentGovRecord r = saved.get(s.id());
            Family f = families.get(s.id());
            serial++;
            rows.add(row(serial, s.admissionNo(), s.fullName().toUpperCase(Locale.ROOT), parentName(f, "FATHER"),
                    parentName(f, "MOTHER"), s.dateOfBirth(), genderCode(s.gender()),
                    codes.label(categories, r == null ? null : r.getSocialCategory()),
                    codes.label(religions, r == null ? null : r.getReligion()),
                    r == null ? null : r.getApaarId(), r == null ? null : r.getPen(),
                    r == null || r.getCwsnType() == null ? "N" : "Y", s.className() + " " + nullTo(s.sectionName()),
                    s.rollNo()));
        }
        ExportTable table = new ExportTable("registration", "CBSE registration list " + cls.name(),
                List.of("S.No", "Admission no", "Student name", "Father's name", "Mother's name", "Date of birth",
                        "Gender", "Category", "Religion", "APAAR ID", "PEN", "CWSN", "Class and section", "Roll no"),
                rows);
        long missing = pupils.stream().filter(s -> saved.get(s.id()) == null || saved.get(s.id()).getApaarId() == null)
                .count();
        return new ExportPreview(ExportKind.CBSE_REGISTRATION_XLSX, year.id(), year.name(), day, true,
                List.of(VERIFY, missing + " of " + pupils.size() + " students have no APAAR ID yet."),
                List.of(table));
    }

    private static String parentName(Family f, String relation) {
        if (f == null) {
            return null;
        }
        // The roster gives names and relations through the guardian link; the relation is not on FamilyGuardian,
        // so the primary guardian is listed as the first parent and the next as the second.
        List<FamilyGuardian> guardians = f.guardians();
        if (guardians.isEmpty()) {
            return null;
        }
        if ("FATHER".equals(relation)) {
            return guardians.getFirst().name();
        }
        return guardians.size() > 1 ? guardians.get(1).name() : null;
    }

    // ------------------------------------------------------------------ files and audit

    byte[] workbook(ExportPreview preview) {
        List<Xlsx.Sheet> sheets = new ArrayList<>();
        for (ExportTable t : preview.tables()) {
            Xlsx.Sheet sheet = new Xlsx.Sheet(t.title());
            sheet.title(t.title());
            sheet.line(preview.academicYearName() + " · as of " + preview.asOf() + " · " + VERIFY);
            sheet.blank();
            sheet.header(t.columns().toArray(String[]::new));
            for (List<Object> row : t.rows()) {
                sheet.row(row);
            }
            sheets.add(sheet);
        }
        return Xlsx.write(sheets);
    }

    GovCsv csv(ExportTable table) {
        GovCsv csv = new GovCsv(table.columns().toArray(String[]::new));
        for (List<Object> row : table.rows()) {
            csv.row(row.toArray());
        }
        return csv;
    }

    ExportTable table(ExportPreview preview, String key) {
        return preview.tables().stream().filter(t -> t.key().equals(key)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Export table"));
    }

    @Transactional
    public void recordExport(ExportKind kind, ExportPreview preview, String table, Actor actor) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind.name());
        details.put("academicYear", preview.academicYearName());
        details.put("asOf", preview.asOf().toString());
        if (table != null) {
            details.put("table", table);
        }
        details.put("rows", preview.tables().stream().mapToInt(t -> t.rows().size()).sum());
        audit.record(actor, "govreport_export.downloaded", "govreport_export", preview.academicYearId(), details);
    }

    private static <T> T value(GovSchoolProfile p, Function<GovSchoolProfile, T> getter, T fallback) {
        T v = p == null ? null : getter.apply(p);
        return v != null ? v : fallback;
    }

    private static String yn(Boolean b) {
        return b == null ? null : b ? "Y" : "N";
    }

    private static String genderCode(Gender g) {
        return g == null ? null : switch (g) {
            case MALE -> "M";
            case FEMALE -> "F";
            default -> "O";
        };
    }

    private static String nullTo(String s) {
        return s == null ? "" : s;
    }

    private static List<Object> row(Object... cells) {
        List<Object> row = new ArrayList<>(cells.length);
        for (Object c : cells) {
            row.add(c);
        }
        return row;
    }
}
