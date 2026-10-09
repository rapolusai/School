package com.akshara.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Attendance reports: a section's month register (students by days), one student's summary for a date range, the
 * school's attendance today for the dashboard, and a parent's view of their own child.
 */
@Service
@Transactional(readOnly = true)
public class AttendanceReportService {

    public static final int MAX_RANGE_DAYS = 400;
    static final int RECENT_ABSENCES = 5;

    public record DayColumn(LocalDate date, boolean marked, AttendanceCounts counts, Double presentPercent) {
    }

    /** One student's line of a month register: a mark letter (P, A, L, H, E) or null for each day. */
    public record StudentLine(UUID studentId, String fullName, String admissionNo, Integer rollNo, boolean inSection,
            List<String> marks, int daysMarked, AttendanceCounts counts, Double presentPercent) {
    }

    public record MonthRegister(UUID sectionId, String className, String sectionName, String label, String month,
            List<DayColumn> days, List<StudentLine> students, int daysMarked, AttendanceCounts counts,
            Double presentPercent) {
    }

    public record DayMark(LocalDate date, AttendanceStatus status) {
    }

    public record StudentSummary(UUID studentId, String fullName, String admissionNo, String className,
            String sectionName, LocalDate from, LocalDate to, int daysMarked, AttendanceCounts counts,
            Double presentPercent, List<LocalDate> absences, List<DayMark> days) {
    }

    public record TodaySection(UUID sectionId, String sectionName, String label, String classTeacherName,
            long students, boolean marked, String markedByName, Instant markedAt, AttendanceCounts counts,
            Double presentPercent) {
    }

    public record TodayClass(UUID classId, String className, int sectionCount, int sectionsMarked,
            AttendanceCounts counts, Double presentPercent, List<TodaySection> sections) {
    }

    public record TodaySummary(LocalDate date, String academicYearName, int sectionCount, int sectionsMarked,
            long students, AttendanceCounts counts, Double presentPercent, List<TodayClass> classes) {
    }

    public record ChildAttendance(UUID studentId, String fullName, String month, int daysMarked,
            AttendanceCounts counts, Double presentPercent, List<DayMark> days, List<DayMark> recentAbsences) {
    }

    private final AttendanceRegisterRepository registers;
    private final AttendanceEntryRepository entries;
    private final AttendanceService attendance;
    private final AcademicsDirectory academics;
    private final StudentRoster roster;

    public AttendanceReportService(AttendanceRegisterRepository registers, AttendanceEntryRepository entries,
            AttendanceService attendance, AcademicsDirectory academics, StudentRoster roster) {
        this.registers = registers;
        this.entries = entries;
        this.attendance = attendance;
        this.academics = academics;
        this.roster = roster;
    }

    // ------------------------------------------------------------------ month register

    /** Every day of the month as a column; students of the section this year plus anyone marked in the month. */
    public MonthRegister month(AttendanceScope scope, UUID sectionId, YearMonth month) {
        TenantContext.require();
        SectionInfo section = attendance.section(sectionId);
        if (!scope.canRead(sectionId)) {
            throw new AccessDeniedException("Not this person's section");
        }
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        List<AttendanceRegister> monthRegisters = registers.findInRange(sectionId, first, last);
        Map<UUID, LocalDate> dateOf = monthRegisters.stream()
                .collect(Collectors.toMap(AttendanceRegister::getId, AttendanceRegister::getAttendanceDate));
        // date -> student -> status
        Map<LocalDate, Map<UUID, AttendanceStatus>> byDay = new HashMap<>();
        Set<UUID> markedStudents = new LinkedHashSet<>();
        if (!dateOf.isEmpty()) {
            for (AttendanceEntry e : entries.findByRegisterIdIn(dateOf.keySet())) {
                byDay.computeIfAbsent(dateOf.get(e.getRegisterId()), d -> new HashMap<>())
                        .put(e.getStudentId(), e.getStatus());
                markedStudents.add(e.getStudentId());
            }
        }

        Optional<YearInfo> current = academics.currentYear()
                .filter(y -> !last.isBefore(y.startsOn()) && !first.isAfter(y.endsOn()));
        UUID rollYear = current.map(YearInfo::id).orElse(monthRegisters.isEmpty() ? null
                : monthRegisters.getFirst().getAcademicYearId());
        Map<UUID, RosterStudent> students = new LinkedHashMap<>();
        Set<UUID> inSection = new LinkedHashSet<>();
        current.ifPresent(y -> roster.activeInSection(y.id(), sectionId).forEach(s -> {
            students.put(s.id(), s);
            inSection.add(s.id());
        }));
        List<UUID> others = markedStudents.stream().filter(id -> !students.containsKey(id)).toList();
        students.putAll(roster.students(others, rollYear));

        List<DayColumn> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            Map<UUID, AttendanceStatus> marks = byDay.get(d);
            AttendanceCounts c = marks == null ? AttendanceCounts.NONE : AttendanceCounts.of(marks.values());
            days.add(new DayColumn(d, marks != null, c, c.presentPercent()));
        }

        List<StudentLine> lines = students.values().stream()
                .sorted(Comparator.comparing((RosterStudent s) -> s.rollNo() == null ? Integer.MAX_VALUE : s.rollNo())
                        .thenComparing(s -> s.fullName().toLowerCase(Locale.ROOT)))
                .map(s -> {
                    List<String> marks = new ArrayList<>();
                    List<AttendanceStatus> statuses = new ArrayList<>();
                    for (DayColumn day : days) {
                        AttendanceStatus st = byDay.getOrDefault(day.date(), Map.of()).get(s.id());
                        marks.add(st == null ? null : st.mark());
                        if (st != null) {
                            statuses.add(st);
                        }
                    }
                    AttendanceCounts c = AttendanceCounts.of(statuses);
                    return new StudentLine(s.id(), s.fullName(), s.admissionNo(), s.rollNo(),
                            inSection.contains(s.id()), marks, c.total(), c, c.presentPercent());
                })
                .toList();
        AttendanceCounts all = days.stream().map(DayColumn::counts).reduce(AttendanceCounts.NONE,
                AttendanceCounts::plus);
        return new MonthRegister(section.id(), section.className(), section.name(), section.label(), month.toString(),
                days, lines, (int) days.stream().filter(DayColumn::marked).count(), all, all.presentPercent());
    }

    /** The month register as CSV: one row per student, a column per day, totals at the end of each row. */
    public String monthCsv(AttendanceScope scope, UUID sectionId, YearMonth month) {
        MonthRegister register = month(scope, sectionId, month);
        StringBuilder csv = new StringBuilder();
        List<String> header = new ArrayList<>(List.of("Roll no", "Admission no", "Student"));
        register.days().forEach(d -> header.add(String.format("%02d", d.date().getDayOfMonth())));
        header.addAll(List.of("Days marked", "Present", "Absent", "Late", "Half day", "Leave", "Attendance %"));
        line(csv, header);
        for (StudentLine s : register.students()) {
            List<String> cells = new ArrayList<>();
            cells.add(s.rollNo() == null ? "" : s.rollNo().toString());
            cells.add(s.admissionNo());
            cells.add(s.fullName());
            s.marks().forEach(m -> cells.add(m == null ? "" : m));
            AttendanceCounts c = s.counts();
            cells.addAll(List.of(Integer.toString(s.daysMarked()), Integer.toString(c.present()),
                    Integer.toString(c.absent()), Integer.toString(c.late()), Integer.toString(c.halfDay()),
                    Integer.toString(c.leave()), s.presentPercent() == null ? "" : s.presentPercent().toString()));
            line(csv, cells);
        }
        totalsRow(csv, register, "Present (P+L+H)", c -> c.present() + c.late() + c.halfDay());
        totalsRow(csv, register, "Absent (A)", AttendanceCounts::absent);
        totalsRow(csv, register, "Leave (E)", AttendanceCounts::leave);
        return csv.toString();
    }

    private static void totalsRow(StringBuilder csv, MonthRegister register, String label,
            Function<AttendanceCounts, Integer> value) {
        List<String> cells = new ArrayList<>(List.of("", "", label));
        register.days().forEach(d -> cells.add(d.marked() ? value.apply(d.counts()).toString() : ""));
        line(csv, cells);
    }

    private static void line(StringBuilder csv, List<String> cells) {
        csv.append(cells.stream().map(AttendanceReportService::cell).collect(Collectors.joining(","))).append("\r\n");
    }

    /** RFC 4180 quoting, and a leading apostrophe so a spreadsheet never runs a cell as a formula. */
    static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        boolean quote = v.chars().anyMatch(ch -> ch == '"' || ch == ',' || ch == '\n' || ch == '\r');
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    // ------------------------------------------------------------------ one student

    /** Days marked, present, absent and so on for one student; the current year to date by default. */
    public StudentSummary student(AttendanceScope scope, UUID studentId, LocalDate from, LocalDate to) {
        TenantContext.require();
        Optional<YearInfo> year = academics.currentYear();
        RosterStudent student = roster.student(studentId, year.map(YearInfo::id).orElse(null))
                .orElseThrow(() -> ApiException.notFound("Student"));
        Optional<UUID> sectionId = year.flatMap(y -> roster.sectionOf(studentId, y.id()));
        if (!scope.wholeSchool() && sectionId.filter(scope::canRead).isEmpty()) {
            throw new AccessDeniedException("Not this person's student");
        }
        LocalDate today = AttendanceService.today();
        LocalDate start = from != null ? from : year.map(YearInfo::startsOn).orElse(today.minusDays(30));
        LocalDate end = to != null ? to : year.map(y -> y.endsOn().isBefore(today) ? y.endsOn() : today).orElse(today);
        if (end.isBefore(start)) {
            throw ApiException.badRequest("The end date must not be before the start date.", "to");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Choose at most " + MAX_RANGE_DAYS + " days.", "to");
        }
        List<DayMark> days = daysOf(studentId, start, end);
        AttendanceCounts counts = AttendanceCounts.of(days.stream().map(DayMark::status).toList());
        SectionInfo section = sectionId.flatMap(academics::section).orElse(null);
        return new StudentSummary(student.id(), student.fullName(), student.admissionNo(),
                section == null ? null : section.className(), section == null ? null : section.name(), start, end,
                counts.total(), counts, counts.presentPercent(),
                days.stream().filter(d -> d.status() == AttendanceStatus.ABSENT).map(DayMark::date).toList(), days);
    }

    // ------------------------------------------------------------------ today, for the dashboard

    public TodaySummary today(AttendanceScope scope) {
        TenantContext.require();
        LocalDate date = AttendanceService.today();
        Optional<YearInfo> year = academics.currentYear()
                .filter(y -> !date.isBefore(y.startsOn()) && !date.isAfter(y.endsOn()));
        if (year.isEmpty()) {
            return new TodaySummary(date, academics.currentYear().map(YearInfo::name).orElse(null), 0, 0, 0,
                    AttendanceCounts.NONE, null, List.of());
        }
        List<SectionInfo> visible = academics.sections().stream().filter(s -> scope.canRead(s.id())).toList();
        Map<UUID, AttendanceRegister> bySection = registers.findByAttendanceDate(date).stream()
                .collect(Collectors.toMap(AttendanceRegister::getSectionId, Function.identity()));
        Map<UUID, AttendanceCounts> counts = attendance.countsPerRegister(bySection.values().stream()
                .map(AttendanceRegister::getId).toList());
        Map<UUID, Long> enrolled = roster.activePerSection(year.get().id());

        Map<UUID, List<TodaySection>> byClass = new LinkedHashMap<>();
        Map<UUID, String> classNames = new LinkedHashMap<>();
        for (SectionInfo s : visible) {
            AttendanceRegister r = bySection.get(s.id());
            AttendanceCounts c = r == null ? AttendanceCounts.NONE : counts.getOrDefault(r.getId(), AttendanceCounts.NONE);
            classNames.putIfAbsent(s.classId(), s.className());
            byClass.computeIfAbsent(s.classId(), k -> new ArrayList<>()).add(new TodaySection(s.id(), s.name(),
                    s.label(), s.classTeacherName(), enrolled.getOrDefault(s.id(), 0L), r != null,
                    r == null ? null : r.getMarkedByName(), r == null ? null : r.getMarkedAt(), c,
                    c.presentPercent()));
        }
        List<TodayClass> classes = byClass.entrySet().stream().map(e -> {
            AttendanceCounts c = e.getValue().stream().map(TodaySection::counts).reduce(AttendanceCounts.NONE,
                    AttendanceCounts::plus);
            int marked = (int) e.getValue().stream().filter(TodaySection::marked).count();
            return new TodayClass(e.getKey(), classNames.get(e.getKey()), e.getValue().size(), marked, c,
                    c.presentPercent(), e.getValue());
        }).toList();
        AttendanceCounts all = classes.stream().map(TodayClass::counts).reduce(AttendanceCounts.NONE,
                AttendanceCounts::plus);
        long students = visible.stream().mapToLong(s -> enrolled.getOrDefault(s.id(), 0L)).sum();
        int marked = classes.stream().mapToInt(TodayClass::sectionsMarked).sum();
        return new TodaySummary(date, year.get().name(), visible.size(), marked, students, all, all.presentPercent(),
                classes);
    }

    // ------------------------------------------------------------------ a parent's own child

    /** The month's marks of one of the signed-in parent's children; 404 for any other student. */
    public ChildAttendance child(UUID parentUserId, UUID studentId, YearMonth month) {
        TenantContext.require();
        if (!roster.childIdsOf(parentUserId).contains(studentId)) {
            throw ApiException.notFound("Student");
        }
        RosterStudent student = roster.student(studentId, null).orElseThrow(() -> ApiException.notFound("Student"));
        YearMonth m = month != null ? month : YearMonth.now(AttendanceService.INDIA);
        List<DayMark> days = daysOf(studentId, m.atDay(1), m.atEndOfMonth());
        AttendanceCounts counts = AttendanceCounts.of(days.stream().map(DayMark::status).toList());
        LocalDate today = AttendanceService.today();
        LocalDate since = academics.currentYear().map(YearInfo::startsOn).filter(d -> !d.isAfter(today))
                .orElse(today.minusDays(365));
        List<DayMark> absences = new ArrayList<>(daysOf(studentId, since, today).stream()
                .filter(d -> d.status() == AttendanceStatus.ABSENT).toList());
        Collections.reverse(absences);
        return new ChildAttendance(student.id(), student.fullName(), m.toString(), counts.total(), counts,
                counts.presentPercent(), days, absences.stream().limit(RECENT_ABSENCES).toList());
    }

    private List<DayMark> daysOf(UUID studentId, LocalDate from, LocalDate to) {
        return entries.daysOf(studentId, from, to).stream()
                .map(r -> new DayMark((LocalDate) r[0], (AttendanceStatus) r[1]))
                .toList();
    }
}
