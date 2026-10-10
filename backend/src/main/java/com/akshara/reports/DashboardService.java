package com.akshara.reports;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.admissions.AdmissionsInsights;
import com.akshara.admissions.AdmissionsInsights.FollowUps;
import com.akshara.admissions.AdmissionsInsights.FunnelFilter;
import com.akshara.admissions.AdmissionsInsights.FunnelStage;
import com.akshara.admissions.AdmissionsService;
import com.akshara.attendance.AttendanceInsights;
import com.akshara.attendance.AttendanceInsights.Overview;
import com.akshara.attendance.AttendanceService;
import com.akshara.communication.CircularService;
import com.akshara.communication.CircularService.CircularList;
import com.akshara.communication.CommunicationTypes.Status;
import com.akshara.communication.NoticeCaller;
import com.akshara.fees.FeeInsights;
import com.akshara.fees.FeeInsights.DayTotal;
import com.akshara.fees.FeeReportService;
import com.akshara.fees.FeeViews.CollectionTotal;
import com.akshara.homework.HomeworkInsights;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;
import com.akshara.staff.LeaveInsights;
import com.akshara.staff.StaffAttendanceService;
import com.akshara.staff.StaffAttendanceService.TodaySummary;
import com.akshara.students.StudentInsights;
import com.akshara.timetable.SubstitutionService;
import com.akshara.timetable.TimetableService;
import com.akshara.timetable.TimetableViews.ClashReport;
import com.akshara.timetable.TimetableViews.DayEntry;
import com.akshara.timetable.TimetableViews.DayPeriod;
import com.akshara.timetable.TimetableViews.SubstitutionDay;
import com.akshara.timetable.TimetableViews.TeacherDay;

/**
 * The staff dashboard: one call that gathers what the signed-in person should see. Each card is filled only when
 * they hold the permission that guards its data (and is null otherwise), using the owning module's own services, so
 * a card never shows more than that module's screens would. Nothing here writes; each module reads in its own
 * transaction.
 */
@Service
public class DashboardService {

    /** Circulars waiting for approval listed on the card. */
    static final int CIRCULAR_ITEMS = 5;

    public record StudentsCard(long onRoll, long admittedThisMonth, long admittedThisYear) {
    }

    public record FeesCard(CollectionTotal today, CollectionTotal thisMonth, long outstandingPaise, long overduePaise,
            long overdueStudents, List<DayTotal> byDay) {
    }

    /** Pipeline tiles for open applications of every year, the funnel for this year and later, and follow-ups. */
    public record AdmissionsCard(long openEnquiries, long inProgress, long offersPending, long admittedThisYear,
            long upcomingSlots, List<FunnelStage> funnel, FollowUps followUps) {
    }

    public record LeaveCard(int waitingForMe) {
    }

    public record CircularItem(UUID id, String title, String createdByName, Instant submittedAt) {
    }

    public record CircularsCard(long pendingApproval, List<CircularItem> items) {
    }

    public record HomeworkCard(int waitingForReview) {
    }

    public record TimetableCard(int clashes, int warnings, int periodsToCover, int periodsCovered) {
    }

    /** A period the signed-in teacher covers today for someone who is away. */
    public record SubstitutionItem(Integer period, String label, String startsAt, String endsAt, String sectionLabel,
            String subjectName, String room, String absentTeacherName) {
    }

    public record MyDayCard(LocalDate date, boolean workingDay, boolean absent, int classes,
            List<SubstitutionItem> substitutions) {
    }

    public record Summary(LocalDate date, String academicYearName, StudentsCard students, Overview attendance,
            FeesCard fees, AdmissionsCard admissions, TodaySummary staff, LeaveCard leave, CircularsCard circulars,
            HomeworkCard homework, TimetableCard timetable, MyDayCard myDay) {
    }

    private final AcademicsDirectory academics;
    private final StudentInsights students;
    private final AttendanceInsights attendance;
    private final FeeReportService feeReports;
    private final FeeInsights fees;
    private final AdmissionsService admissionsService;
    private final AdmissionsInsights admissions;
    private final StaffAttendanceService staffAttendance;
    private final LeaveInsights leave;
    private final CircularService circulars;
    private final HomeworkInsights homework;
    private final TimetableService timetable;
    private final SubstitutionService substitutions;

    DashboardService(AcademicsDirectory academics, StudentInsights students, AttendanceInsights attendance,
            FeeReportService feeReports, FeeInsights fees, AdmissionsService admissionsService,
            AdmissionsInsights admissions, StaffAttendanceService staffAttendance, LeaveInsights leave,
            CircularService circulars, HomeworkInsights homework, TimetableService timetable,
            SubstitutionService substitutions) {
        this.academics = academics;
        this.students = students;
        this.attendance = attendance;
        this.feeReports = feeReports;
        this.fees = fees;
        this.admissionsService = admissionsService;
        this.admissions = admissions;
        this.staffAttendance = staffAttendance;
        this.leave = leave;
        this.circulars = circulars;
        this.homework = homework;
        this.timetable = timetable;
        this.substitutions = substitutions;
    }

    public Summary summary() {
        TenantContext.require();
        Set<String> can = permissions();
        LocalDate today = AttendanceService.today();
        Optional<YearInfo> year = academics.currentYear();
        return new Summary(today, year.map(YearInfo::name).orElse(null),
                can.contains(Permissions.STUDENTS_READ) ? students(year, today) : null,
                can.contains(Permissions.ATTENDANCE_READ) ? attendance.overview() : null,
                can.contains(Permissions.FEES_READ) ? fees() : null,
                can.contains(Permissions.ADMISSIONS_READ) ? admissions(today) : null,
                can.contains(Permissions.STAFF_READ) || can.contains(Permissions.STAFF_ATTENDANCE_MANAGE)
                        ? staffAttendance.todaySummary() : null,
                can.contains(Permissions.LEAVE_APPROVE) || can.contains(Permissions.LEAVE_REQUEST)
                        ? new LeaveCard(leave.waitingForMe()) : null,
                can.contains(Permissions.NOTICES_APPROVE) ? circulars() : null,
                can.contains(Permissions.HOMEWORK_MANAGE) ? new HomeworkCard(homework.waitingForReview()) : null,
                can.contains(Permissions.TIMETABLE_MANAGE) ? timetable(today) : null,
                can.contains(Permissions.TIMETABLE_READ) ? myDay(today) : null);
    }

    private StudentsCard students(Optional<YearInfo> year, LocalDate today) {
        return new StudentsCard(year.map(y -> students.onRoll(y.id())).orElse(0L),
                students.admittedBetween(today.withDayOfMonth(1), today),
                year.map(y -> students.admittedBetween(y.startsOn(), y.endsOn())).orElse(0L));
    }

    private FeesCard fees() {
        var overview = feeReports.overview();
        LocalDate today = overview.asOf();
        return new FeesCard(overview.today(), overview.thisMonth(), overview.outstandingPaise(),
                overview.overduePaise(), overview.overdueStudents(),
                fees.collectedPerDay(today.withDayOfMonth(1), today));
    }

    private AdmissionsCard admissions(LocalDate today) {
        AdmissionsService.Summary summary = admissionsService.summary();
        List<UUID> openYears = academics.years().stream().filter(y -> !y.endsOn().isBefore(today))
                .map(YearInfo::id).toList();
        List<FunnelStage> funnel = admissions.funnel(new FunnelFilter(openYears.isEmpty() ? null : openYears, null,
                null, null, null)).stages();
        return new AdmissionsCard(summary.openEnquiries(), summary.inProgress(), summary.offersPending(),
                summary.admittedThisYear(), summary.upcomingSlots(), funnel, admissions.followUps());
    }

    private CircularsCard circulars() {
        NoticeCaller approver = new NoticeCaller(CurrentUser.requireId(), CurrentUser.name().orElse(null), false,
                true, false, true, Set.of());
        CircularList list = circulars.list(approver, Status.PENDING_APPROVAL);
        List<CircularItem> items = list.items().stream().limit(CIRCULAR_ITEMS)
                .map(c -> new CircularItem(c.id(), c.title(), c.createdByName(), c.submittedAt())).toList();
        return new CircularsCard(list.counts().getOrDefault(Status.PENDING_APPROVAL, 0L), items);
    }

    private TimetableCard timetable(LocalDate today) {
        ClashReport clashes = timetable.clashReport();
        SubstitutionDay day = substitutions.day(today);
        return new TimetableCard(clashes.clashes().size(), clashes.warnings().size(), day.periodsToCover(),
                day.periodsCovered());
    }

    private MyDayCard myDay(LocalDate today) {
        TeacherDay day = substitutions.teacherDay(CurrentUser.requireId(), today);
        List<SubstitutionItem> subs = new ArrayList<>();
        int classes = 0;
        for (DayPeriod p : day.periods()) {
            for (DayEntry e : p.entries()) {
                if ("SUBSTITUTION".equals(e.kind())) {
                    subs.add(new SubstitutionItem(p.number(), p.label(), p.startsAt(), p.endsAt(), e.sectionLabel(),
                            e.subjectName(), e.room(), e.absentTeacherName()));
                } else if ("CLASS".equals(e.kind())) {
                    classes++;
                }
            }
        }
        return new MyDayCard(day.date(), day.workingDay(), day.absent(), classes, subs);
    }

    static Set<String> permissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? Set.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
