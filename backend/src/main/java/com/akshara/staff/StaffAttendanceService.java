package com.akshara.staff;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffForms.DayEntry;
import com.akshara.staff.StaffRoster.Member;

/**
 * Staff attendance: self check-in and check-out (once a day, server time in India), the admin's daily sheet to mark
 * and correct a day (audited), the monthly report and today's numbers for the dashboard. Approved leave marks days
 * ON_LEAVE through {@link #applyLeave}; nothing else can set ON_LEAVE.
 */
@Service
@Transactional
public class StaffAttendanceService {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    public static final int MAX_ENTRIES = 500;
    /** The daily sheet goes back at most this many days. */
    public static final int MAX_DAYS_BACK = 366;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    /** Days of each kind. */
    public record Counts(int present, int halfDay, int absent, int onLeave) {

        static final Counts NONE = new Counts(0, 0, 0, 0);

        static Counts of(Iterable<StaffAttendanceStatus> statuses) {
            int p = 0;
            int h = 0;
            int a = 0;
            int l = 0;
            for (StaffAttendanceStatus s : statuses) {
                switch (s) {
                    case PRESENT -> p++;
                    case HALF_DAY -> h++;
                    case ABSENT -> a++;
                    case ON_LEAVE -> l++;
                }
            }
            return new Counts(p, h, a, l);
        }

        public int marked() {
            return present + halfDay + absent + onLeave;
        }

        /** Days worked: a half day counts as half. */
        public BigDecimal daysWorked() {
            return LeaveMath.scale(BigDecimal.valueOf(present).add(LeaveMath.HALF.multiply(BigDecimal.valueOf(
                    halfDay))));
        }
    }

    /**
     * The signed-in person's day. {@code onLeave}: approved full-day leave, so no check-in. {@code status} is null
     * until the day is marked or the person checks in.
     */
    public record MyDay(LocalDate date, boolean workingDay, StaffAttendanceStatus status, Instant checkInAt,
            Instant checkOutAt, String checkInNote, String checkOutNote, boolean onLeave, boolean canCheckIn,
            boolean canCheckOut) {
    }

    /**
     * A line of the daily sheet. {@code checkIn}/{@code checkOut} are "HH:mm" in India time. {@code onLeaveRequest}:
     * the day comes from approved leave and only cancelling the leave changes it.
     */
    public record DayRow(UUID userId, String name, String employeeCode, String designation, String departmentName,
            boolean onRoll, StaffAttendanceStatus status, AttendanceSource source, String checkIn, String checkOut,
            Instant checkInAt, Instant checkOutAt, String checkInNote, String checkOutNote, boolean onLeaveRequest,
            String markedByName, String updatedByName, Instant editedAt) {
    }

    /** One day for the whole staff. {@code canEdit}: the caller may mark it (not a future day, not too old). */
    public record DaySheet(LocalDate date, LocalDate today, boolean workingDay, boolean canEdit, Counts counts,
            int notMarked, List<DayRow> rows) {
    }

    /** {@code marked}: days newly marked; {@code corrected}: days that were already marked and changed. */
    public record SaveResult(DaySheet sheet, int marked, int corrected) {
    }

    public record MonthDay(LocalDate date, boolean workingDay, Counts counts) {
    }

    /**
     * One person's month: a mark per day (P, A, H, L or null). {@code notMarked} counts working days up to today, while
     * they were on the staff, with no mark.
     */
    public record MonthLine(UUID userId, String name, String employeeCode, String departmentName, boolean active,
            List<String> marks, Counts counts, int notMarked, BigDecimal daysWorked) {
    }

    public record MonthReport(String month, UUID departmentId, int workingDays, List<MonthDay> days,
            List<MonthLine> staff, Counts totals) {
    }

    /** Today's staff attendance for the dashboard, over staff on the roll today. */
    public record TodaySummary(LocalDate date, boolean workingDay, int activeStaff, int present, int halfDay,
            int onLeave, int absent, int notMarked, int checkedIn) {
    }

    public record PersonDay(LocalDate date, boolean workingDay, StaffAttendanceStatus status, AttendanceSource source,
            Instant checkInAt, Instant checkOutAt) {
    }

    public record PersonMonth(UUID userId, String month, List<PersonDay> days, Counts counts, BigDecimal daysWorked) {
    }

    private final StaffAttendanceRepository days;
    private final StaffRoster roster;
    private final DepartmentRepository departments;
    private final WorkingDayCalendar calendar;
    private final PersonLock lock;
    private final AuditService audit;

    public StaffAttendanceService(StaffAttendanceRepository days, StaffRoster roster, DepartmentRepository departments,
            WorkingDayCalendar calendar, PersonLock lock, AuditService audit) {
        this.days = days;
        this.roster = roster;
        this.departments = departments;
        this.calendar = calendar;
        this.lock = lock;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ self check-in

    @Transactional(readOnly = true)
    public MyDay myToday(UUID userId) {
        TenantContext.require();
        roster.find(userId).orElseThrow(() -> ApiException.notFound("Staff member"));
        LocalDate today = today();
        return myDay(today, days.findByUserIdAndAttendanceDate(userId, today).orElse(null));
    }

    /** Checks the caller in for today, once. A day the admin marked absent becomes present. */
    public MyDay checkIn(Actor actor, String note, Instant at) {
        TenantContext.require();
        Member me = roster.find(actor.id()).filter(Member::active)
                .orElseThrow(() -> ApiException.notFound("Staff member"));
        LocalDate date = LocalDate.ofInstant(at, INDIA);
        lock.lock(me.userId());
        StaffAttendance day = days.findByUserIdAndAttendanceDate(me.userId(), date).orElse(null);
        if (day != null && day.getCheckInAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Already checked in",
                    "You checked in at " + time(day.getCheckInAt()) + " today.");
        }
        if (day != null && day.getStatus() == StaffAttendanceStatus.ON_LEAVE) {
            throw new ApiException(HttpStatus.CONFLICT, "On leave",
                    "You are on approved leave today. Ask the person who approved it to cancel it first.");
        }
        if (day == null) {
            day = new StaffAttendance(me.userId(), date, StaffAttendanceStatus.PRESENT, AttendanceSource.SELF, actor,
                    at);
            day.checkIn(at, blankToNull(note));
            days.saveAndFlush(day);
        } else {
            day.checkIn(at, blankToNull(note));
            days.flush();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("date", date.toString());
        details.put("time", time(at));
        details.put("withNote", day.getCheckInNote() != null);
        audit.record(actor, "staff_attendance.checked_in", "staff_attendance", day.getId(), details);
        return myDay(date, day);
    }

    /** Checks the caller out for today, once, after checking in. */
    public MyDay checkOut(Actor actor, String note, Instant at) {
        TenantContext.require();
        Member me = roster.find(actor.id()).filter(Member::active)
                .orElseThrow(() -> ApiException.notFound("Staff member"));
        LocalDate date = LocalDate.ofInstant(at, INDIA);
        lock.lock(me.userId());
        StaffAttendance day = days.findByUserIdAndAttendanceDate(me.userId(), date)
                .filter(d -> d.getCheckInAt() != null)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Not checked in",
                        "Check in first. You can check out once you have checked in today."));
        if (day.getCheckOutAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Already checked out",
                    "You checked out at " + time(day.getCheckOutAt()) + " today.");
        }
        day.checkOut(at.isBefore(day.getCheckInAt()) ? day.getCheckInAt() : at, blankToNull(note));
        days.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("date", date.toString());
        details.put("time", time(day.getCheckOutAt()));
        details.put("withNote", day.getCheckOutNote() != null);
        audit.record(actor, "staff_attendance.checked_out", "staff_attendance", day.getId(), details);
        return myDay(date, day);
    }

    private MyDay myDay(LocalDate date, StaffAttendance day) {
        boolean onLeave = day != null && day.getStatus() == StaffAttendanceStatus.ON_LEAVE;
        boolean checkedIn = day != null && day.getCheckInAt() != null;
        boolean checkedOut = day != null && day.getCheckOutAt() != null;
        return new MyDay(date, calendar.isWorkingDay(date), day == null ? null : day.getStatus(),
                day == null ? null : day.getCheckInAt(), day == null ? null : day.getCheckOutAt(),
                day == null ? null : day.getCheckInNote(), day == null ? null : day.getCheckOutNote(), onLeave,
                !checkedIn && !onLeave, checkedIn && !checkedOut);
    }

    // ------------------------------------------------------------------ daily sheet

    /** Everyone on the staff that day (plus anyone marked on it), with their mark and times. */
    @Transactional(readOnly = true)
    public DaySheet day(LocalDate date, boolean mayEdit) {
        TenantContext.require();
        Map<UUID, StaffAttendance> marks = days.findByAttendanceDate(date).stream()
                .collect(Collectors.toMap(StaffAttendance::getUserId, d -> d));
        Map<UUID, String> departmentNames = departmentNames();
        List<DayRow> rows = new ArrayList<>();
        for (Member m : roster.all()) {
            boolean onRoll = onRollOn(m, date);
            StaffAttendance d = marks.get(m.userId());
            if (!onRoll && d == null) {
                continue;
            }
            rows.add(new DayRow(m.userId(), m.name(), m.employeeCode(),
                    m.profile() == null ? null : m.profile().getDesignation(),
                    m.departmentId() == null ? null : departmentNames.get(m.departmentId()), onRoll,
                    d == null ? null : d.getStatus(), d == null ? null : d.getSource(),
                    d == null ? null : time(d.getCheckInAt()), d == null ? null : time(d.getCheckOutAt()),
                    d == null ? null : d.getCheckInAt(), d == null ? null : d.getCheckOutAt(),
                    d == null ? null : d.getCheckInNote(), d == null ? null : d.getCheckOutNote(),
                    d != null && d.getLeaveRequestId() != null, d == null ? null : d.getMarkedByName(),
                    d == null ? null : d.getUpdatedByName(), d == null ? null : d.getEditedAt()));
        }
        Counts counts = Counts.of(rows.stream().filter(r -> r.status() != null).map(DayRow::status).toList());
        int notMarked = (int) rows.stream().filter(r -> r.status() == null && r.onRoll()).count();
        return new DaySheet(date, today(), calendar.isWorkingDay(date), mayEdit && editable(date), counts, notMarked,
                rows);
    }

    /**
     * Marks or corrects the day for the listed people. ON_LEAVE comes only from approved leave, and days of approved
     * leave change only by cancelling the leave. Times are optional "HH:mm" in India time; an absent day has none.
     * One audit event records how many days were marked and what each correction changed.
     */
    public SaveResult saveDay(LocalDate date, List<DayEntry> entries, Actor actor) {
        TenantContext.require();
        if (date.isAfter(today())) {
            throw ApiException.badRequest("Attendance cannot be marked for a future date.", "date");
        }
        if (!editable(date)) {
            throw ApiException.badRequest("Pick a date within the last year.", "date");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw ApiException.badRequest("Mark at most " + MAX_ENTRIES + " people at a time.", "entries");
        }
        Map<UUID, Member> staff = new HashMap<>();
        roster.all().forEach(m -> staff.put(m.userId(), m));
        Set<UUID> seen = new HashSet<>();
        Instant now = Instant.now();
        record Change(Member member, StaffAttendanceStatus status, Instant in, Instant out) {
        }
        List<Change> changes = new ArrayList<>();
        for (DayEntry e : entries) {
            Member m = staff.get(e.userId());
            if (m == null) {
                throw ApiException.badRequest("Pick staff members of this school.", "entries");
            }
            if (!seen.add(m.userId())) {
                throw ApiException.badRequest(m.name() + " is listed twice.", "entries");
            }
            if (e.status() == StaffAttendanceStatus.ON_LEAVE) {
                throw ApiException.badRequest(m.name() + ": on leave is set by approving a leave request.",
                        "entries");
            }
            Instant in = at(date, e.checkIn());
            Instant out = at(date, e.checkOut());
            if (e.status() == StaffAttendanceStatus.ABSENT && (in != null || out != null)) {
                throw ApiException.badRequest(m.name() + ": an absent day has no check-in or check-out time.",
                        "entries");
            }
            if (out != null && in == null) {
                throw ApiException.badRequest(m.name() + ": enter the check-in time as well.", "entries");
            }
            if (in != null && out != null && out.isBefore(in)) {
                throw ApiException.badRequest(m.name() + ": check-out cannot be before check-in.", "entries");
            }
            if ((in != null && in.isAfter(now)) || (out != null && out.isAfter(now))) {
                throw ApiException.badRequest(m.name() + ": times cannot be in the future.", "entries");
            }
            changes.add(new Change(m, e.status(), in, out));
        }

        int marked = 0;
        List<Map<String, Object>> corrections = new ArrayList<>();
        List<StaffAttendanceStatus> newStatuses = new ArrayList<>();
        for (Change c : changes) {
            lock.lock(c.member().userId());
            StaffAttendance day = days.findByUserIdAndAttendanceDate(c.member().userId(), date).orElse(null);
            if (day == null) {
                days.save(new StaffAttendance(c.member().userId(), date, c.status(), AttendanceSource.ADMIN, actor, now)
                        .withTimes(c.in(), c.out()));
                newStatuses.add(c.status());
                marked++;
                continue;
            }
            if (day.getLeaveRequestId() != null) {
                if (day.getStatus() != c.status()) {
                    throw new ApiException(HttpStatus.CONFLICT, "On leave", c.member().name()
                            + " is on approved leave on this day. Cancel the leave request to change it.");
                }
                continue;
            }
            String before = describe(day.getStatus(), day.getCheckInAt(), day.getCheckOutAt());
            Instant in = sameMinute(day.getCheckInAt(), c.in()) ? day.getCheckInAt() : c.in();
            Instant out = sameMinute(day.getCheckOutAt(), c.out()) ? day.getCheckOutAt() : c.out();
            if (day.correct(c.status(), in, out, actor)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("userId", c.member().userId().toString());
                change.put("employeeCode", c.member().employeeCode());
                change.put("from", before);
                change.put("to", describe(c.status(), in, out));
                corrections.add(change);
            }
        }
        days.flush();
        Counts newly = Counts.of(newStatuses);
        if (marked > 0 || !corrections.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("date", date.toString());
            details.put("marked", marked);
            details.put("present", newly.present());
            details.put("halfDay", newly.halfDay());
            details.put("absent", newly.absent());
            details.put("corrected", corrections.size());
            if (!corrections.isEmpty()) {
                details.put("changes", corrections);
            }
            audit.record(actor, corrections.isEmpty() ? "staff_attendance.marked" : "staff_attendance.corrected",
                    "staff_attendance_day", date.toString(), details);
        }
        return new SaveResult(day(date, true), marked, corrections.size());
    }

    // ------------------------------------------------------------------ approved leave

    /** Marks the days of approved leave ON_LEAVE (HALF_DAY for a half-day leave), keeping any check-in times. */
    void applyLeave(UUID userId, UUID requestId, List<LocalDate> leaveDays, boolean halfDay, Actor actor, Instant at) {
        StaffAttendanceStatus status = halfDay ? StaffAttendanceStatus.HALF_DAY : StaffAttendanceStatus.ON_LEAVE;
        for (LocalDate date : leaveDays) {
            StaffAttendance day = days.findByUserIdAndAttendanceDate(userId, date).orElse(null);
            if (day == null) {
                days.save(new StaffAttendance(userId, date, status, AttendanceSource.LEAVE, actor, at)
                        .linkedTo(requestId));
            } else {
                day.onLeave(requestId, status, actor);
            }
        }
        days.flush();
    }

    /** Leave was cancelled: its days go back to unmarked, or present where the person checked in. */
    void removeLeave(UUID requestId, Actor actor, Instant at) {
        for (StaffAttendance day : days.findByLeaveRequestId(requestId)) {
            if (day.getCheckInAt() != null) {
                day.leaveCancelled(actor);
            } else {
                days.delete(day);
            }
        }
        days.flush();
    }

    // ------------------------------------------------------------------ reports

    /** The month for the whole staff (or one department): a column per day with P/A/H/L marks. */
    @Transactional(readOnly = true)
    public MonthReport month(YearMonth month, UUID departmentId) {
        TenantContext.require();
        if (departmentId != null && departments.findById(departmentId).isEmpty()) {
            throw ApiException.badRequest("Pick a department of this school.", "departmentId");
        }
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        LocalDate today = today();
        Map<UUID, Map<LocalDate, StaffAttendanceStatus>> byPerson = new HashMap<>();
        for (StaffAttendance d : days.findBetween(first, last)) {
            byPerson.computeIfAbsent(d.getUserId(), k -> new HashMap<>()).put(d.getAttendanceDate(), d.getStatus());
        }
        List<LocalDate> dates = first.datesUntil(last.plusDays(1)).toList();
        Map<UUID, String> departmentNames = departmentNames();
        List<MonthLine> lines = new ArrayList<>();
        for (Member m : roster.all()) {
            if (departmentId != null && !departmentId.equals(m.departmentId())) {
                continue;
            }
            Map<LocalDate, StaffAttendanceStatus> marks = byPerson.getOrDefault(m.userId(), Map.of());
            boolean onRollInMonth = dates.stream().anyMatch(d -> onRollOn(m, d));
            if (!onRollInMonth && marks.isEmpty()) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            int notMarked = 0;
            for (LocalDate d : dates) {
                StaffAttendanceStatus s = marks.get(d);
                cells.add(s == null ? null : s.mark());
                if (s == null && !d.isAfter(today) && calendar.isWorkingDay(d) && onRollOn(m, d)) {
                    notMarked++;
                }
            }
            Counts counts = Counts.of(marks.values());
            lines.add(new MonthLine(m.userId(), m.name(), m.employeeCode(),
                    m.departmentId() == null ? null : departmentNames.get(m.departmentId()), m.active(), cells, counts,
                    notMarked, counts.daysWorked()));
        }
        Set<UUID> shown = lines.stream().map(MonthLine::userId).collect(Collectors.toSet());
        List<MonthDay> columns = new ArrayList<>();
        for (LocalDate d : dates) {
            List<StaffAttendanceStatus> statuses = new ArrayList<>();
            byPerson.forEach((user, marks) -> {
                if (shown.contains(user) && marks.containsKey(d)) {
                    statuses.add(marks.get(d));
                }
            });
            columns.add(new MonthDay(d, calendar.isWorkingDay(d), Counts.of(statuses)));
        }
        Counts totals = Counts.of(byPerson.entrySet().stream()
                .filter(e -> shown.contains(e.getKey()))
                .flatMap(e -> e.getValue().values().stream())
                .toList());
        int workingDays = calendar.workingDays(first, last).size();
        return new MonthReport(month.toString(), departmentId, workingDays, columns, lines, totals);
    }

    /** The month report as CSV: one row per person, a column per day, totals at the end of each row. */
    @Transactional(readOnly = true)
    public String monthCsv(YearMonth month, UUID departmentId) {
        MonthReport report = month(month, departmentId);
        StringBuilder csv = new StringBuilder();
        List<String> header = new ArrayList<>(List.of("Employee code", "Name", "Department"));
        report.days().forEach(d -> header.add(String.format(Locale.ROOT, "%02d%s", d.date().getDayOfMonth(),
                d.workingDay() ? "" : " (off)")));
        header.addAll(List.of("Present", "Half day", "Absent", "On leave", "Not marked", "Days worked"));
        line(csv, header);
        for (MonthLine s : report.staff()) {
            List<String> cells = new ArrayList<>();
            cells.add(s.employeeCode());
            cells.add(s.name());
            cells.add(s.departmentName());
            s.marks().forEach(m -> cells.add(m == null ? "" : m));
            Counts c = s.counts();
            cells.addAll(List.of(Integer.toString(c.present()), Integer.toString(c.halfDay()),
                    Integer.toString(c.absent()), Integer.toString(c.onLeave()), Integer.toString(s.notMarked()),
                    LeaveService.plain(s.daysWorked())));
            line(csv, cells);
        }
        return csv.toString();
    }

    /** Today's numbers over the staff on the roll today. */
    @Transactional(readOnly = true)
    public TodaySummary todaySummary() {
        TenantContext.require();
        LocalDate today = today();
        Map<UUID, StaffAttendance> marks = days.findByAttendanceDate(today).stream()
                .collect(Collectors.toMap(StaffAttendance::getUserId, d -> d));
        List<Member> onRoll = roster.all().stream().filter(m -> onRollOn(m, today)).toList();
        List<StaffAttendanceStatus> statuses = new ArrayList<>();
        int checkedIn = 0;
        for (Member m : onRoll) {
            StaffAttendance d = marks.get(m.userId());
            if (d != null) {
                statuses.add(d.getStatus());
                if (d.getCheckInAt() != null) {
                    checkedIn++;
                }
            }
        }
        Counts c = Counts.of(statuses);
        return new TodaySummary(today, calendar.isWorkingDay(today), onRoll.size(), c.present(), c.halfDay(),
                c.onLeave(), c.absent(), onRoll.size() - c.marked(), checkedIn);
    }

    /** One staff member's month, for their profile page. */
    @Transactional(readOnly = true)
    public PersonMonth personMonth(UUID userId, YearMonth month) {
        TenantContext.require();
        roster.find(userId).orElseThrow(() -> ApiException.notFound("Staff member"));
        Map<LocalDate, StaffAttendance> byDate = days.findOfUserBetween(userId, month.atDay(1), month.atEndOfMonth())
                .stream().collect(Collectors.toMap(StaffAttendance::getAttendanceDate, d -> d));
        List<PersonDay> list = month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1))
                .map(date -> {
                    StaffAttendance d = byDate.get(date);
                    return new PersonDay(date, calendar.isWorkingDay(date), d == null ? null : d.getStatus(),
                            d == null ? null : d.getSource(), d == null ? null : d.getCheckInAt(),
                            d == null ? null : d.getCheckOutAt());
                })
                .toList();
        Counts counts = Counts.of(byDate.values().stream().map(StaffAttendance::getStatus).toList());
        return new PersonMonth(userId, month.toString(), list, counts, counts.daysWorked());
    }

    // ------------------------------------------------------------------ helpers

    /** On the staff that day: joined by then, not left before it, and (with no leaving date) still able to sign in. */
    static boolean onRollOn(Member m, LocalDate date) {
        StaffProfile p = m.profile();
        if (p != null && p.getDateOfJoining() != null && date.isBefore(p.getDateOfJoining())) {
            return false;
        }
        if (p != null && p.getDateOfLeaving() != null) {
            return !date.isAfter(p.getDateOfLeaving());
        }
        return m.accountActive();
    }

    private static boolean editable(LocalDate date) {
        LocalDate today = today();
        return !date.isAfter(today) && !date.isBefore(today.minusDays(MAX_DAYS_BACK));
    }

    private Map<UUID, String> departmentNames() {
        return departments.findAll().stream().collect(Collectors.toMap(Department::getId, Department::getName));
    }

    private static Instant at(LocalDate date, String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return null;
        }
        return date.atTime(LocalTime.parse(hhmm.strip(), HH_MM)).atZone(INDIA).toInstant();
    }

    /** "08:30" in India time, or null. */
    static String time(Instant at) {
        return at == null ? null : HH_MM.format(at.atZone(INDIA));
    }

    private static boolean sameMinute(Instant existing, Instant wanted) {
        return existing != null && wanted != null && existing.truncatedTo(ChronoUnit.MINUTES).equals(wanted);
    }

    private static String describe(StaffAttendanceStatus status, Instant in, Instant out) {
        StringBuilder s = new StringBuilder(status.name());
        if (in != null) {
            s.append(' ').append(time(in)).append('-').append(out == null ? "" : time(out));
        }
        return s.toString();
    }

    private static void line(StringBuilder csv, List<String> cells) {
        csv.append(cells.stream().map(StaffAttendanceService::cell).collect(Collectors.joining(","))).append("\r\n");
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
