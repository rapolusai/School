package com.akshara.reports;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.admissions.AdmissionsInsights;
import com.akshara.admissions.AdmissionsInsights.Funnel;
import com.akshara.admissions.AdmissionsInsights.FunnelFilter;
import com.akshara.admissions.AdmissionsInsights.FunnelLine;
import com.akshara.admissions.AdmissionsInsights.FunnelStage;
import com.akshara.admissions.ApplicationSource;
import com.akshara.admissions.ApplicationStage;
import com.akshara.attendance.AttendanceCounts;
import com.akshara.attendance.AttendanceInsights;
import com.akshara.attendance.AttendanceInsights.Absentee;
import com.akshara.attendance.AttendanceInsights.Absentees;
import com.akshara.attendance.AttendanceInsights.ClassLine;
import com.akshara.attendance.AttendanceInsights.SectionLine;
import com.akshara.attendance.AttendanceInsights.SectionReport;
import com.akshara.attendance.AttendanceService;
import com.akshara.attendance.AttendanceStatus;
import com.akshara.audit.AuditService;
import com.akshara.homework.HomeworkInsights;
import com.akshara.homework.HomeworkInsights.Completion;
import com.akshara.homework.HomeworkInsights.CompletionLine;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantView;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;
import com.akshara.staff.LeaveInsights;
import com.akshara.staff.LeaveInsights.LeaveTaken;
import com.akshara.staff.LeaveInsights.LeaveTypeRef;
import com.akshara.staff.LeaveInsights.StaffLeaveLine;

/**
 * The reports the hub adds: daily absentees, attendance by class and section, homework completion, the admissions
 * funnel and leave taken. Each reads its module's figures through the module's public insights service, which applies
 * the caller's scope; this class checks filter ids (another school's class, subject or year is 404), fills in default
 * dates and writes the Excel export. Every export is audited as report.exported with the report and its filters
 * (never the rows themselves).
 */
@Service
public class ReportsService {

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);
    static final int HOMEWORK_DEFAULT_DAYS = 30;

    /** An export ready to download. */
    public record Download(String fileName, byte[] bytes) {
    }

    /** The funnel with the filters it was worked out for; {@code academicYearId} null means every year. */
    public record FunnelReport(UUID academicYearId, String academicYearName, UUID classId, ApplicationSource source,
            LocalDate from, LocalDate to, Funnel funnel) {
    }

    private final AttendanceInsights attendance;
    private final HomeworkInsights homework;
    private final AdmissionsInsights admissions;
    private final LeaveInsights leave;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final TenantDirectory tenants;
    private final AuditService audit;

    ReportsService(AttendanceInsights attendance, HomeworkInsights homework, AdmissionsInsights admissions,
            LeaveInsights leave, AcademicsDirectory academics, AcademicsService academicsService,
            TenantDirectory tenants, AuditService audit) {
        this.attendance = attendance;
        this.homework = homework;
        this.admissions = admissions;
        this.leave = leave;
        this.academics = academics;
        this.academicsService = academicsService;
        this.tenants = tenants;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ attendance

    /** Students absent on the day (today by default), optionally with those on leave. */
    public Absentees absentees(LocalDate date, UUID classId, boolean includeLeave) {
        TenantContext.require();
        className(classId);
        return attendance.absentees(date != null ? date : AttendanceService.today(), classId, includeLeave);
    }

    @Transactional
    public Download absenteesXlsx(LocalDate date, UUID classId, boolean includeLeave) {
        Absentees report = absentees(date, classId, includeLeave);
        Xlsx.Sheet sheet = new Xlsx.Sheet("Absentees");
        heading(sheet, "Daily absentees", "Date: " + DAY.format(report.date()) + " · Class: " + classLabel(classId)
                + " · On leave: " + (includeLeave ? "included" : "not included"));
        sheet.line("Sections marked: " + report.sectionsMarked() + " of " + report.sectionCount()
                + (report.holiday() == null ? "" : " · Holiday: " + report.holiday()));
        sheet.blank();
        sheet.header("Class", "Section", "Roll no", "Admission no", "Student", "Status", "Days in a row", "Marked by");
        for (Absentee a : report.rows()) {
            sheet.row(a.className(), a.sectionName(), a.rollNo(), a.admissionNo(), a.fullName(), status(a.status()),
                    a.daysInARow(), a.markedByName());
        }
        sheet.total("Absent", null, null, null, report.absent());
        if (includeLeave) {
            sheet.total("On leave", null, null, null, report.onLeave());
        }
        return export("attendance-absentees", Map.of("date", report.date(), "classId", optional(classId),
                "includeLeave", includeLeave), report.rows().size(),
                "absentees-" + report.date() + ".xlsx", sheet);
    }

    /** Attendance by class and section between two days; this month to date by default. */
    public SectionReport sections(LocalDate from, LocalDate to, UUID classId) {
        TenantContext.require();
        className(classId);
        LocalDate today = AttendanceService.today();
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        return attendance.bySection(start, end, classId);
    }

    @Transactional
    public Download sectionsXlsx(LocalDate from, LocalDate to, UUID classId) {
        SectionReport report = sections(from, to, classId);
        Xlsx.Sheet bySection = new Xlsx.Sheet("By section");
        String filters = "From " + DAY.format(report.from()) + " to " + DAY.format(report.to()) + " · Class: "
                + classLabel(classId);
        heading(bySection, "Attendance by class and section", filters);
        bySection.line("School days: " + report.schoolDays() + " · Holidays: " + report.holidays()
                + " · Days marked: " + report.daysMarked());
        bySection.blank();
        bySection.header("Class", "Section", "Students", "Days marked", "Present", "Late", "Half day", "Absent",
                "Leave", "Attendance %");
        for (ClassLine c : report.classes()) {
            for (SectionLine s : c.sections()) {
                bySection.row(countCells(List.of(s.className(), s.sectionName(), s.students(), s.daysMarked()),
                        s.counts(), s.presentPercent()));
            }
            bySection.total(countCells(listOf(c.className() + " total", null, c.students(), null), c.counts(),
                    c.presentPercent()));
        }
        bySection.total(countCells(listOf("School total", null, report.students(), report.daysMarked()),
                report.counts(), report.presentPercent()));

        Xlsx.Sheet byClass = new Xlsx.Sheet("By class");
        heading(byClass, "Attendance by class", filters);
        byClass.blank();
        byClass.header("Class", "Students", "Present", "Late", "Half day", "Absent", "Leave", "Attendance %");
        for (ClassLine c : report.classes()) {
            byClass.row(countCells(List.of(c.className(), c.students()), c.counts(), c.presentPercent()));
        }
        byClass.total(countCells(List.of("School total", report.students()), report.counts(),
                report.presentPercent()));
        int rows = report.classes().stream().mapToInt(c -> c.sections().size()).sum();
        return export("attendance-sections", Map.of("from", report.from(), "to", report.to(), "classId",
                optional(classId)), rows, "attendance-by-section-" + report.from() + "-to-" + report.to() + ".xlsx",
                bySection, byClass);
    }

    private static List<Object> countCells(List<Object> first, AttendanceCounts c, Double percent) {
        List<Object> cells = new ArrayList<>(first);
        cells.addAll(List.of(c.present(), c.late(), c.halfDay(), c.absent(), c.leave()));
        cells.add(percent);
        return cells;
    }

    // ------------------------------------------------------------------ homework

    /** Homework due between two days, by section and subject; the last 30 days by default. */
    public Completion homework(LocalDate from, LocalDate to, UUID classId, UUID subjectId) {
        TenantContext.require();
        className(classId);
        subjectName(subjectId);
        LocalDate end = to != null ? to : AttendanceService.today();
        LocalDate start = from != null ? from : end.minusDays(HOMEWORK_DEFAULT_DAYS);
        return homework.completion(start, end, classId, subjectId);
    }

    @Transactional
    public Download homeworkXlsx(LocalDate from, LocalDate to, UUID classId, UUID subjectId) {
        Completion report = homework(from, to, classId, subjectId);
        Xlsx.Sheet sheet = new Xlsx.Sheet("Homework completion");
        heading(sheet, "Homework completion by section and subject", "Due from " + DAY.format(report.from())
                + " to " + DAY.format(report.to()) + " · Class: " + classLabel(classId) + " · Subject: "
                + (subjectId == null ? "All subjects" : subjectName(subjectId)));
        sheet.blank();
        sheet.header("Class", "Section", "Subject", "Homework", "Online submission", "Expected", "Submitted", "Late",
                "Reviewed", "Needs redo", "Waiting for review", "Completion %");
        for (CompletionLine l : report.rows()) {
            sheet.row(completionCells(l.className(), l.sectionLabel(), l.subjectName(), l));
        }
        sheet.total(completionCells("Total", null, null, report.total()));
        return export("homework-completion", Map.of("from", report.from(), "to", report.to(), "classId",
                optional(classId), "subjectId", optional(subjectId)), report.rows().size(),
                "homework-completion-" + report.from() + "-to-" + report.to() + ".xlsx", sheet);
    }

    private static List<Object> completionCells(String className, String section, String subject, CompletionLine l) {
        return listOf(className, section, subject, l.homework(), l.online(), l.expected(), l.submitted(), l.late(),
                l.reviewed(), l.needsRedo(), l.waiting(), l.completionPercent());
    }

    // ------------------------------------------------------------------ admissions

    /** The funnel for the filters; every academic year when none is given. */
    public FunnelReport funnel(UUID yearId, UUID classId, ApplicationSource source, LocalDate from, LocalDate to) {
        TenantContext.require();
        YearInfo year = yearId == null ? null
                : academics.year(yearId).orElseThrow(() -> ApiException.notFound("Academic year"));
        className(classId);
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("The end date must not be before the start date.", "to");
        }
        Funnel funnel = admissions.funnel(new FunnelFilter(year == null ? null : List.of(year.id()), classId, source,
                from, to));
        return new FunnelReport(year == null ? null : year.id(), year == null ? null : year.name(), classId, source,
                from, to, funnel);
    }

    @Transactional
    public Download funnelXlsx(UUID yearId, UUID classId, ApplicationSource source, LocalDate from, LocalDate to) {
        FunnelReport report = funnel(yearId, classId, source, from, to);
        Funnel f = report.funnel();
        String filters = "Year: " + (report.academicYearName() == null ? "All years" : report.academicYearName())
                + " · Class: " + classLabel(classId) + " · Source: " + (source == null ? "All sources" : source(source))
                + " · Made: " + (from == null ? "any time" : "from " + DAY.format(from))
                + (to == null ? "" : " to " + DAY.format(to));
        Xlsx.Sheet funnelSheet = new Xlsx.Sheet("Funnel");
        heading(funnelSheet, "Admissions funnel", filters);
        funnelSheet.blank();
        funnelSheet.header("Stage", "Reached", "In this stage now", "From previous stage %", "From enquiry %");
        for (FunnelStage s : f.stages()) {
            funnelSheet.row(stage(s.stage()), s.reached(), s.current(), s.fromPrevious(), s.fromEnquiry());
        }
        funnelSheet.blank();
        funnelSheet.row("Still open", f.open());
        funnelSheet.row("Rejected", f.rejected());
        funnelSheet.row("Withdrawn", f.withdrawn());
        funnelSheet.total("Applications", f.total());
        funnelSheet.total("Admitted out of all applications %", f.conversionPercent());

        String[] header = {"Applications", "Applied", "Assessed", "Offered", "Admitted", "Rejected", "Withdrawn",
                "Conversion %"};
        Xlsx.Sheet classes = new Xlsx.Sheet("By class");
        heading(classes, "Admissions funnel by class", filters);
        classes.blank();
        classes.header(prepend("Class", header));
        f.byClass().forEach(l -> classes.row(funnelCells(l.className(), l)));
        Xlsx.Sheet sources = new Xlsx.Sheet("By source");
        heading(sources, "Admissions funnel by source", filters);
        sources.blank();
        sources.header(prepend("Source", header));
        f.bySource().forEach(l -> sources.row(funnelCells(source(l.source()), l)));
        String name = "admissions-funnel-" + (report.academicYearName() == null ? "all-years"
                : report.academicYearName().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "")) + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("academicYearId", optional(yearId));
        details.put("classId", optional(classId));
        details.put("source", source == null ? "" : source.name());
        details.put("from", from == null ? "" : from.toString());
        details.put("to", to == null ? "" : to.toString());
        return export("admissions-funnel", details, (int) f.total(), name.toLowerCase(Locale.ROOT), funnelSheet,
                classes, sources);
    }

    private static List<Object> funnelCells(String label, FunnelLine l) {
        return listOf(label, l.total(), l.applied(), l.assessed(), l.offered(), l.admitted(), l.rejected(),
                l.withdrawn(), l.conversionPercent());
    }

    // ------------------------------------------------------------------ staff

    /** Approved leave by staff and type for the academic year (the current one by default). */
    public LeaveTaken leave(UUID yearId, UUID departmentId, UUID leaveTypeId) {
        TenantContext.require();
        return leave.taken(yearId, departmentId, leaveTypeId);
    }

    @Transactional
    public Download leaveXlsx(UUID yearId, UUID departmentId, UUID leaveTypeId) {
        LeaveTaken report = leave(yearId, departmentId, leaveTypeId);
        Xlsx.Sheet sheet = new Xlsx.Sheet("Leave taken");
        String department = departmentId == null ? "All departments"
                : report.staff().stream().map(StaffLeaveLine::departmentName).filter(n -> n != null).findFirst()
                        .orElse("One department");
        heading(sheet, "Leave taken by staff and type", "Year: "
                + (report.academicYearName() == null ? "none set up" : report.academicYearName()) + " · Department: "
                + department + " · Leave type: " + (leaveTypeId == null ? "All types"
                        : report.types().stream().map(LeaveTypeRef::name).findFirst().orElse("")));
        sheet.line("Approved days; pending days are shown separately and not counted in the total.");
        sheet.blank();
        List<String> header = new ArrayList<>(List.of("Staff", "Employee code", "Department"));
        report.types().forEach(t -> header.add(t.name()));
        header.addAll(List.of("Total", "Loss of pay", "Pending"));
        sheet.header(header.toArray(String[]::new));
        for (StaffLeaveLine l : report.staff()) {
            List<Object> cells = listOf(l.active() ? l.name() : l.name() + " (left)", l.employeeCode(),
                    l.departmentName());
            cells.addAll(l.days());
            cells.addAll(List.of(l.total(), l.lossOfPay(), l.pending()));
            sheet.row(cells);
        }
        List<Object> total = listOf("Total", null, null);
        total.addAll(report.typeTotals());
        total.addAll(List.of(report.total(), report.lossOfPay(), report.pending()));
        sheet.total(total);
        String year = report.academicYearName() == null ? "no-year"
                : report.academicYearName().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return export("staff-leave", Map.of("academicYearId", optional(report.academicYearId()), "departmentId",
                optional(departmentId), "leaveTypeId", optional(leaveTypeId)), report.staff().size(),
                ("leave-taken-" + year + ".xlsx").toLowerCase(Locale.ROOT), sheet);
    }

    // ------------------------------------------------------------------ helpers

    /** School name, report title, filters and when and by whom it was made: the top of every export sheet. */
    private void heading(Xlsx.Sheet sheet, String title, String filters) {
        sheet.title(schoolName());
        sheet.header(title);
        sheet.line(filters);
        sheet.line("Generated " + TIME.format(ZonedDateTime.now(AttendanceService.INDIA)) + " IST"
                + CurrentUser.name().map(n -> " by " + n).orElse(""));
    }

    private String schoolName() {
        return tenants.findById(TenantContext.require()).map(TenantView::name).orElse("School");
    }

    private Download export(String report, Map<String, ?> filters, int rows, String fileName, Xlsx.Sheet... sheets) {
        byte[] bytes = Xlsx.write(List.of(sheets));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("report", report);
        details.put("format", "xlsx");
        filters.forEach((k, v) -> details.put(k, v == null ? "" : v.toString()));
        details.put("rows", rows);
        audit.record("report.exported", "report", report, details);
        return new Download(fileName, bytes);
    }

    /** The class's name, or null for none; 404 for a class that is not this school's. */
    private String className(UUID classId) {
        return classId == null ? null : academicsService.classView(classId).name();
    }

    private String classLabel(UUID classId) {
        return classId == null ? "All classes" : className(classId);
    }

    private String subjectName(UUID subjectId) {
        if (subjectId == null) {
            return null;
        }
        return academicsService.subjects().stream().filter(s -> s.id().equals(subjectId)).map(SubjectView::name)
                .findFirst().orElseThrow(() -> ApiException.notFound("Subject"));
    }

    private static String optional(Object value) {
        return value == null ? "" : value.toString();
    }

    private static List<Object> listOf(Object... cells) {
        List<Object> list = new ArrayList<>();
        for (Object c : cells) {
            list.add(c);
        }
        return list;
    }

    private static String[] prepend(String first, String[] rest) {
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    static String status(AttendanceStatus status) {
        return switch (status) {
            case PRESENT -> "Present";
            case ABSENT -> "Absent";
            case LATE -> "Late";
            case HALF_DAY -> "Half day";
            case LEAVE -> "On leave";
        };
    }

    static String stage(ApplicationStage stage) {
        return switch (stage) {
            case ENQUIRY -> "Enquiry";
            case APPLICATION -> "Application";
            case ASSESSMENT -> "Test or interview";
            case OFFERED -> "Offered";
            case ADMITTED -> "Admitted";
            case REJECTED -> "Rejected";
            case WITHDRAWN -> "Withdrawn";
        };
    }

    static String source(ApplicationSource source) {
        return switch (source) {
            case WALK_IN -> "Walk-in";
            case WEBSITE -> "Website";
            case PHONE -> "Phone";
            case REFERRAL -> "Referral";
            case OTHER -> "Other";
        };
    }
}
