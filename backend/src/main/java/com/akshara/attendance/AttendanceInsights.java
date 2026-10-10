package com.akshara.attendance;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.attendance.AttendanceReportService.TodaySection;
import com.akshara.attendance.AttendanceReportService.TodaySummary;
import com.akshara.communication.SchoolCalendar;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Read-only attendance figures for dashboards and reports: today and this month at a glance with the trend over the
 * last school days, the day's absentees across the school, and attendance by class and section for a date range.
 * Every method works out the signed-in person's scope itself, exactly as the attendance screens do: the whole school
 * with attendance.manage, otherwise only the sections they are class teacher of. Whole-school holidays are left out
 * of every count, as in the month register.
 */
@Service
@Transactional(readOnly = true)
public class AttendanceInsights {

    /** School days shown in the trend. */
    public static final int TREND_DAYS = 30;
    /** How far back the trend looks for its school days. */
    static final int TREND_LOOKBACK_DAYS = 120;
    /** How far back "days in a row" looks. */
    static final int STREAK_LOOKBACK_DAYS = 60;

    /** One school day of the trend; {@code presentPercent} is null when no section was marked. */
    public record DayPoint(LocalDate date, int sectionsMarked, AttendanceCounts counts, Double presentPercent) {
    }

    /** A class teacher's own section today: whether it is marked yet and whether they can still mark it. */
    public record OwnSection(UUID sectionId, String label, long students, boolean marked, boolean canMark,
            AttendanceCounts counts, Double presentPercent) {
    }

    /** Totals for a date range: school days (Monday to Saturday less holidays) and the days anyone marked. */
    public record Period(LocalDate from, LocalDate to, int schoolDays, int daysMarked, AttendanceCounts counts,
            Double presentPercent) {
    }

    /**
     * The dashboard's attendance card. {@code wholeSchool} is false for a class teacher, whose figures cover only
     * their own sections ({@code ownSections}). {@code month} is null before the current academic year is set up.
     */
    public record Overview(LocalDate date, boolean wholeSchool, String holiday, int sectionCount, int sectionsMarked,
            long students, AttendanceCounts counts, Double presentPercent, Period month, List<DayPoint> trend,
            List<OwnSection> ownSections) {
    }

    /** A student marked absent (or on leave) on the day. {@code daysInARow} counts back over marked days. */
    public record Absentee(UUID studentId, String fullName, String admissionNo, Integer rollNo, UUID classId,
            String className, UUID sectionId, String sectionName, String sectionLabel, AttendanceStatus status,
            int daysInARow, String markedByName) {
    }

    public record Absentees(LocalDate date, String holiday, UUID classId, boolean includeLeave, int sectionCount,
            int sectionsMarked, int absent, int onLeave, List<Absentee> rows) {
    }

    /** One section's attendance over a date range. {@code students} is today's active roll of the section. */
    public record SectionLine(UUID sectionId, UUID classId, String className, String sectionName, String label,
            long students, int daysMarked, AttendanceCounts counts, Double presentPercent) {
    }

    public record ClassLine(UUID classId, String className, long students, AttendanceCounts counts,
            Double presentPercent, List<SectionLine> sections) {
    }

    public record SectionReport(LocalDate from, LocalDate to, UUID classId, int schoolDays, int holidays,
            List<ClassLine> classes, long students, int daysMarked, AttendanceCounts counts, Double presentPercent) {
    }

    private final AttendanceAccess access;
    private final AttendanceReportService reports;
    private final AcademicsDirectory academics;
    private final StudentRoster roster;
    private final SchoolCalendar calendar;
    private final EntityManager entityManager;

    AttendanceInsights(AttendanceAccess access, AttendanceReportService reports, AcademicsDirectory academics,
            StudentRoster roster, SchoolCalendar calendar, EntityManager entityManager) {
        this.access = access;
        this.reports = reports;
        this.academics = academics;
        this.roster = roster;
        this.calendar = calendar;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ the dashboard

    /** Today, this month and the trend over the last {@value #TREND_DAYS} school days, in the caller's scope. */
    public Overview overview() {
        TenantContext.require();
        AttendanceScope scope = access.current();
        TodaySummary today = reports.today(scope);
        LocalDate date = today.date();
        String holiday = calendar.holidayOn(date).orElse(null);
        List<OwnSection> own = new ArrayList<>();
        if (!scope.wholeSchool()) {
            today.classes().forEach(c -> c.sections().forEach((TodaySection s) -> own.add(new OwnSection(
                    s.sectionId(), s.label(), s.students(), s.marked(), holiday == null && scope.canMark(s.sectionId()),
                    s.counts(), s.presentPercent()))));
        }
        Optional<YearInfo> year = academics.currentYear()
                .filter(y -> !date.isBefore(y.startsOn()) && !date.isAfter(y.endsOn()));
        if (year.isEmpty()) {
            return new Overview(date, scope.wholeSchool(), holiday, today.sectionCount(), today.sectionsMarked(),
                    today.students(), today.counts(), today.presentPercent(), null, List.of(), own);
        }
        Set<UUID> sections = visibleSections(scope, null);
        LocalDate monthStart = max(date.withDayOfMonth(1), year.get().startsOn());
        Period month = period(sections, monthStart, date);
        List<DayPoint> trend = trend(sections, date, year.get().startsOn());
        return new Overview(date, scope.wholeSchool(), holiday, today.sectionCount(), today.sectionsMarked(),
                today.students(), today.counts(), today.presentPercent(), month, trend, own);
    }

    private Period period(Set<UUID> sections, LocalDate from, LocalDate to) {
        Map<LocalDate, String> holidays = calendar.holidaysBetween(from, to);
        AttendanceCounts counts = AttendanceCounts.NONE;
        for (Object[] row : countsBy("r.attendanceDate", sections, from, to, holidays.keySet())) {
            counts = counts.plus((AttendanceStatus) row[1], ((Number) row[2]).intValue());
        }
        int daysMarked = markedDates(sections, from, to, holidays.keySet()).size();
        return new Period(from, to, schoolDays(from, to, holidays), daysMarked, counts, counts.presentPercent());
    }

    private List<DayPoint> trend(Set<UUID> sections, LocalDate today, LocalDate yearStart) {
        LocalDate earliest = max(today.minusDays(TREND_LOOKBACK_DAYS), yearStart);
        Map<LocalDate, String> holidays = calendar.holidaysBetween(earliest, today);
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = today; !d.isBefore(earliest) && days.size() < TREND_DAYS; d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.containsKey(d)) {
                days.add(0, d);
            }
        }
        if (days.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, AttendanceCounts> byDay = new HashMap<>();
        for (Object[] row : countsBy("r.attendanceDate", sections, days.getFirst(), today, holidays.keySet())) {
            byDay.merge((LocalDate) row[0], AttendanceCounts.NONE.plus((AttendanceStatus) row[1],
                    ((Number) row[2]).intValue()), AttendanceCounts::plus);
        }
        Map<LocalDate, Long> marked = new HashMap<>();
        if (!sections.isEmpty()) {
            entityManager.createQuery("select r.attendanceDate, count(r) from AttendanceRegister r "
                    + "where r.attendanceDate between :from and :to and r.sectionId in :sections "
                    + "group by r.attendanceDate", Object[].class)
                    .setParameter("from", days.getFirst())
                    .setParameter("to", today)
                    .setParameter("sections", sections)
                    .getResultList()
                    .forEach(r -> marked.put((LocalDate) r[0], ((Number) r[1]).longValue()));
        }
        return days.stream().map(d -> {
            AttendanceCounts c = byDay.getOrDefault(d, AttendanceCounts.NONE);
            return new DayPoint(d, marked.getOrDefault(d, 0L).intValue(), c, c.presentPercent());
        }).toList();
    }

    // ------------------------------------------------------------------ daily absentees

    /**
     * Students marked absent on the day (and on leave too with {@code includeLeave}) in the caller's sections, in
     * class order and then by roll number. A future date is refused; {@code classId} narrows to one class.
     */
    public Absentees absentees(LocalDate date, UUID classId, boolean includeLeave) {
        TenantContext.require();
        if (date.isAfter(AttendanceService.today())) {
            throw ApiException.badRequest("Pick today or an earlier day.", "date");
        }
        AttendanceScope scope = access.current();
        Map<UUID, SectionInfo> sections = sectionMap(scope, classId);
        String holiday = calendar.holidayOn(date).orElse(null);
        Map<UUID, AttendanceRegister> registers = sections.isEmpty() ? Map.of()
                : entityManager.createQuery("select r from AttendanceRegister r where r.attendanceDate = :date "
                        + "and r.sectionId in :sections", AttendanceRegister.class)
                        .setParameter("date", date)
                        .setParameter("sections", sections.keySet())
                        .getResultList().stream()
                        .collect(Collectors.toMap(AttendanceRegister::getId, Function.identity()));
        List<AttendanceStatus> wanted = includeLeave ? List.of(AttendanceStatus.ABSENT, AttendanceStatus.LEAVE)
                : List.of(AttendanceStatus.ABSENT);
        List<AttendanceEntry> found = registers.isEmpty() ? List.of()
                : entityManager.createQuery("select e from AttendanceEntry e where e.registerId in :registers "
                        + "and e.status in :statuses", AttendanceEntry.class)
                        .setParameter("registers", registers.keySet())
                        .setParameter("statuses", wanted)
                        .getResultList();
        UUID rollYear = yearContaining(date).map(YearInfo::id).orElse(null);
        Map<UUID, RosterStudent> students = roster.students(found.stream().map(AttendanceEntry::getStudentId)
                .toList(), rollYear);
        Map<UUID, Integer> streaks = streaks(found, date);
        List<UUID> order = new ArrayList<>(sections.keySet());
        List<Absentee> rows = found.stream()
                .filter(e -> students.containsKey(e.getStudentId()))
                .map(e -> {
                    AttendanceRegister r = registers.get(e.getRegisterId());
                    SectionInfo s = sections.get(r.getSectionId());
                    RosterStudent st = students.get(e.getStudentId());
                    return new Absentee(st.id(), st.fullName(), st.admissionNo(), st.rollNo(), s.classId(),
                            s.className(), s.id(), s.name(), s.label(), e.getStatus(),
                            streaks.getOrDefault(e.getStudentId(), 1), r.getMarkedByName());
                })
                .sorted(Comparator.comparingInt((Absentee a) -> order.indexOf(a.sectionId()))
                        .thenComparing(a -> a.rollNo() == null ? Integer.MAX_VALUE : a.rollNo())
                        .thenComparing(a -> a.fullName().toLowerCase(Locale.ROOT)))
                .toList();
        int absent = (int) rows.stream().filter(a -> a.status() == AttendanceStatus.ABSENT).count();
        Set<UUID> markedSections = registers.values().stream().map(AttendanceRegister::getSectionId)
                .collect(Collectors.toSet());
        return new Absentees(date, holiday, classId, includeLeave, sections.size(), markedSections.size(), absent,
                rows.size() - absent, rows);
    }

    /** Consecutive marked days, counting back from the date, with the same mark as on the date. */
    private Map<UUID, Integer> streaks(List<AttendanceEntry> found, LocalDate date) {
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AttendanceStatus> statusOn = found.stream()
                .collect(Collectors.toMap(AttendanceEntry::getStudentId, AttendanceEntry::getStatus, (a, b) -> a));
        Map<UUID, List<AttendanceStatus>> history = new HashMap<>();
        entityManager.createQuery("select e.studentId, e.status from AttendanceEntry e join AttendanceRegister r "
                + "on r.id = e.registerId where e.studentId in :students and r.attendanceDate between :from and :to "
                + "order by r.attendanceDate desc", Object[].class)
                .setParameter("students", statusOn.keySet())
                .setParameter("from", date.minusDays(STREAK_LOOKBACK_DAYS))
                .setParameter("to", date)
                .getResultList()
                .forEach(r -> history.computeIfAbsent((UUID) r[0], k -> new ArrayList<>())
                        .add((AttendanceStatus) r[1]));
        Map<UUID, Integer> result = new HashMap<>();
        statusOn.forEach((student, status) -> {
            int n = 0;
            for (AttendanceStatus s : history.getOrDefault(student, List.of())) {
                if (s != status) {
                    break;
                }
                n++;
            }
            result.put(student, Math.max(n, 1));
        });
        return result;
    }

    // ------------------------------------------------------------------ by class and section

    /**
     * Attendance per section and class between two days (both included) in the caller's sections, with the school
     * total. At most {@link AttendanceReportService#MAX_RANGE_DAYS} days.
     */
    public SectionReport bySection(LocalDate from, LocalDate to, UUID classId) {
        TenantContext.require();
        checkRange(from, to);
        AttendanceScope scope = access.current();
        Map<UUID, SectionInfo> sections = sectionMap(scope, classId);
        Map<LocalDate, String> holidays = calendar.holidaysBetween(from, to);
        Map<UUID, AttendanceCounts> counts = new HashMap<>();
        for (Object[] row : countsBy("r.sectionId", sections.keySet(), from, to, holidays.keySet())) {
            counts.merge((UUID) row[0], AttendanceCounts.NONE.plus((AttendanceStatus) row[1],
                    ((Number) row[2]).intValue()), AttendanceCounts::plus);
        }
        Map<UUID, Integer> daysMarked = new HashMap<>();
        if (!sections.isEmpty()) {
            TypedQuery<Object[]> q = entityManager.createQuery("select r.sectionId, count(r) from AttendanceRegister r "
                    + "where r.attendanceDate between :from and :to and r.sectionId in :sections"
                    + (holidays.isEmpty() ? "" : " and r.attendanceDate not in :holidays")
                    + " group by r.sectionId", Object[].class)
                    .setParameter("from", from)
                    .setParameter("to", to)
                    .setParameter("sections", sections.keySet());
            if (!holidays.isEmpty()) {
                q.setParameter("holidays", holidays.keySet());
            }
            q.getResultList().forEach(r -> daysMarked.put((UUID) r[0], ((Number) r[1]).intValue()));
        }
        Map<UUID, Long> enrolled = academics.currentYear().map(y -> roster.activePerSection(y.id()))
                .orElse(Map.of());

        Map<UUID, List<SectionLine>> byClass = new LinkedHashMap<>();
        Map<UUID, String> classNames = new LinkedHashMap<>();
        for (SectionInfo s : sections.values()) {
            AttendanceCounts c = counts.getOrDefault(s.id(), AttendanceCounts.NONE);
            classNames.putIfAbsent(s.classId(), s.className());
            byClass.computeIfAbsent(s.classId(), k -> new ArrayList<>()).add(new SectionLine(s.id(), s.classId(),
                    s.className(), s.name(), s.label(), enrolled.getOrDefault(s.id(), 0L),
                    daysMarked.getOrDefault(s.id(), 0), c, c.presentPercent()));
        }
        List<ClassLine> classes = byClass.entrySet().stream().map(e -> {
            AttendanceCounts c = e.getValue().stream().map(SectionLine::counts).reduce(AttendanceCounts.NONE,
                    AttendanceCounts::plus);
            long students = e.getValue().stream().mapToLong(SectionLine::students).sum();
            return new ClassLine(e.getKey(), classNames.get(e.getKey()), students, c, c.presentPercent(),
                    e.getValue());
        }).toList();
        AttendanceCounts all = classes.stream().map(ClassLine::counts).reduce(AttendanceCounts.NONE,
                AttendanceCounts::plus);
        long students = classes.stream().mapToLong(ClassLine::students).sum();
        int marked = markedDates(sections.keySet(), from, to, holidays.keySet()).size();
        return new SectionReport(from, to, classId, schoolDays(from, to, holidays), holidays.size(), classes,
                students, marked, all, all.presentPercent());
    }

    static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end date must not be before the start date.", "to");
        }
        if (ChronoUnit.DAYS.between(from, to) >= AttendanceReportService.MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Choose at most " + AttendanceReportService.MAX_RANGE_DAYS + " days.", "to");
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The caller's sections in class order, narrowed to one class when asked. */
    private Map<UUID, SectionInfo> sectionMap(AttendanceScope scope, UUID classId) {
        Map<UUID, SectionInfo> map = new LinkedHashMap<>();
        (classId == null ? academics.sections() : academics.sectionsOfClass(classId)).stream()
                .filter(s -> scope.canRead(s.id()))
                .forEach(s -> map.put(s.id(), s));
        return map;
    }

    private Set<UUID> visibleSections(AttendanceScope scope, UUID classId) {
        return new HashSet<>(sectionMap(scope, classId).keySet());
    }

    /** Rows of [key, status, count] for the sections' registers between the dates, holidays left out. */
    private List<Object[]> countsBy(String key, Collection<UUID> sections, LocalDate from, LocalDate to,
            Collection<LocalDate> holidays) {
        if (sections.isEmpty()) {
            return List.of();
        }
        TypedQuery<Object[]> q = entityManager.createQuery("select " + key + ", e.status, count(e) "
                + "from AttendanceEntry e join AttendanceRegister r on r.id = e.registerId "
                + "where r.attendanceDate between :from and :to and r.sectionId in :sections"
                + (holidays.isEmpty() ? "" : " and r.attendanceDate not in :holidays")
                + " group by " + key + ", e.status", Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("sections", sections);
        if (!holidays.isEmpty()) {
            q.setParameter("holidays", holidays);
        }
        return q.getResultList();
    }

    /** The days on which at least one of the sections was marked, holidays left out. */
    private Set<LocalDate> markedDates(Collection<UUID> sections, LocalDate from, LocalDate to,
            Collection<LocalDate> holidays) {
        if (sections.isEmpty()) {
            return Set.of();
        }
        TypedQuery<LocalDate> q = entityManager.createQuery("select distinct r.attendanceDate "
                + "from AttendanceRegister r where r.attendanceDate between :from and :to and r.sectionId in :sections"
                + (holidays.isEmpty() ? "" : " and r.attendanceDate not in :holidays"), LocalDate.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("sections", sections);
        if (!holidays.isEmpty()) {
            q.setParameter("holidays", holidays);
        }
        return new HashSet<>(q.getResultList());
    }

    private static int schoolDays(LocalDate from, LocalDate to, Map<LocalDate, String> holidays) {
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.containsKey(d)) {
                days++;
            }
        }
        return days;
    }

    private Optional<YearInfo> yearContaining(LocalDate date) {
        return academics.years().stream()
                .filter(y -> !date.isBefore(y.startsOn()) && !date.isAfter(y.endsOn()))
                .findFirst()
                .or(academics::currentYear);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
