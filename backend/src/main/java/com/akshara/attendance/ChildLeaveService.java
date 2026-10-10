package com.akshara.attendance;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.communication.SchoolCalendar;
import com.akshara.notifications.NotificationQueue;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;
import com.akshara.students.StudentStatus;

/**
 * A child's leave (absence notes): a parent applies for their own child's absence, the class teacher of the child's
 * section (or anyone with attendance.manage) approves or rejects it, and the parent sees how it stands. Approved leave
 * pre-fills the register ({@link #approvedOn}) and stops absence alerts for those days. Every change is audited and
 * publishes {@link ChildLeaveRequested} or {@link ChildLeaveDecided}.
 */
@Service
@Transactional
public class ChildLeaveService {

    /** The longest note, in calendar days. */
    public static final int MAX_RANGE_DAYS = 31;
    /** How far back a note may start: parents often write after the child was away. */
    public static final int MAX_DAYS_BACK = 30;
    public static final int REASON_MAX = 500;
    public static final int COMMENT_MAX = 500;
    /** How long decided requests stay in a teacher's list. */
    static final Duration RECENT = Duration.ofDays(30);
    static final int RECENT_MAX = 50;

    /**
     * A request as the parent, the student or a teacher sees it. {@code schoolDays} counts Mondays to Saturdays that
     * are not whole-school holidays (a half day counts as one). {@code canCancel} and {@code canDecide} are for the
     * person asking.
     */
    public record ChildLeaveView(UUID id, UUID studentId, String studentName, String admissionNo, Integer rollNo,
            UUID sectionId, String sectionLabel, LocalDate fromDate, LocalDate toDate, boolean halfDay, int schoolDays,
            String reason, ChildLeaveStatus status, String requestedByName, Instant createdAt, String decidedByName,
            Instant decidedAt, String decisionComment, String cancelledByName, Instant cancelledAt, boolean canCancel,
            boolean canDecide) {
    }

    /**
     * One child's requests, for their parent (who can apply between {@code earliest} and {@code latest}) or for the
     * student themselves (read only). {@code canApply} is false for a student, for a child who is not in a class this
     * year, or when the school has no current academic year.
     */
    public record FamilyLeave(UUID studentId, String studentName, String sectionLabel, String classTeacherName,
            LocalDate today, LocalDate earliest, LocalDate latest, boolean canApply, List<ChildLeaveView> requests) {
    }

    /** A teacher's list: requests waiting for them (oldest first), and those closed in the last 30 days. */
    public record Inbox(List<ChildLeaveView> pending, List<ChildLeaveView> recent, boolean wholeSchool) {
    }

    public record Application(LocalDate fromDate, LocalDate toDate, boolean halfDay, String reason) {
    }

    /** Approved leave of one student on one day: the register pre-fills {@code prefill} (LEAVE, or HALF_DAY). */
    public record ApprovedLeave(UUID requestId, boolean halfDay, AttendanceStatus prefill) {
    }

    private final ChildLeaveRepository requests;
    private final AcademicsDirectory academics;
    private final StudentRoster roster;
    private final StudentService students;
    private final SchoolCalendar calendar;
    private final NotificationQueue queue;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;

    ChildLeaveService(ChildLeaveRepository requests, AcademicsDirectory academics, StudentRoster roster,
            StudentService students, SchoolCalendar calendar, NotificationQueue queue, AuditService audit,
            ApplicationEventPublisher events, EntityManager entityManager) {
        this.requests = requests;
        this.academics = academics;
        this.roster = roster;
        this.students = students;
        this.calendar = calendar;
        this.queue = queue;
        this.audit = audit;
        this.events = events;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ parents and students

    /** A parent's own child's requests. Any other student, of this school or another, is 404. */
    @Transactional(readOnly = true)
    public FamilyLeave forChild(UUID parentUserId, UUID studentId, LocalDate today) {
        TenantContext.require();
        return family(ownChild(parentUserId, studentId), today, true);
    }

    /** The signed-in student's own requests, read only. 404 when the sign-in is not linked to a student record. */
    @Transactional(readOnly = true)
    public FamilyLeave forStudent(UUID userId, LocalDate today) {
        TenantContext.require();
        ChildView me = students.studentOf(userId).orElseThrow(() -> ApiException.notFound("Student record"));
        return family(me, today, false);
    }

    /** A parent applies for their child's absence: whole days in a range, or half of one day. */
    public ChildLeaveView apply(UUID parentUserId, UUID studentId, Application form, Actor actor, LocalDate today,
            Instant at) {
        TenantContext.require();
        ChildView child = ownChild(parentUserId, studentId);
        if (child.status() != StudentStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Not at school", child.fullName() + " is no longer at the "
                    + "school, so leave cannot be requested.");
        }
        YearInfo year = academics.currentYear().orElseThrow(() -> ApiException.badRequest(
                "The school has not set up the current academic year yet.", "fromDate"));
        UUID sectionId = roster.sectionOf(child.id(), year.id()).orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT, "Not in a class", child.fullName() + " is not in a class this year yet."));
        String reason = form.reason() == null ? "" : form.reason().strip();
        if (reason.isEmpty()) {
            throw ApiException.badRequest("Say why your child will be away.", "reason");
        }
        if (reason.length() > REASON_MAX) {
            throw ApiException.badRequest("Keep the reason to " + REASON_MAX + " characters.", "reason");
        }
        LocalDate from = form.fromDate();
        LocalDate to = form.toDate();
        checkDates(from, to, form.halfDay(), year, today);
        if (calendar.schoolDaysBetween(from, to) == 0) {
            throw ApiException.badRequest("Those days are Sundays or school holidays, so no leave is needed.",
                    "fromDate");
        }
        lock(child.id());
        List<ChildLeaveRequest> overlapping = requests.findOpenOverlapping(child.id(), from, to);
        if (!overlapping.isEmpty()) {
            ChildLeaveRequest other = overlapping.getFirst();
            String detail = "There is already a " + other.getStatus().name().toLowerCase() + " request for "
                    + (other.getFromDate().equals(other.getToDate()) ? other.getFromDate().toString()
                            : other.getFromDate() + " to " + other.getToDate())
                    + ". Cancel it first, or pick other days.";
            throw new ApiException(HttpStatus.CONFLICT, "Overlapping request", detail, Map.of("fromDate", detail));
        }
        ChildLeaveRequest request = requests.saveAndFlush(new ChildLeaveRequest(child.id(), sectionId, year.id(),
                from, to, form.halfDay(), reason, actor, at));
        SectionInfo section = academics.section(sectionId).orElseThrow();
        audit.record(actor, "child_leave.requested", "child_leave", request.getId(), details(request, section));
        events.publishEvent(new ChildLeaveRequested(TenantContext.require(), request.getId(), child.id(), sectionId,
                from, to, form.halfDay(), actor.id(), at));
        return views(List.of(request), Viewer.PARENT, today).getFirst();
    }

    /**
     * The parent withdraws a request: any time while it waits, and approved leave until the day it starts. After that
     * only the school can change the register.
     */
    public ChildLeaveView cancel(UUID parentUserId, UUID studentId, UUID requestId, Actor actor, LocalDate today,
            Instant at) {
        TenantContext.require();
        ChildView child = ownChild(parentUserId, studentId);
        ChildLeaveRequest request = requests.findById(requestId)
                .filter(r -> r.getStudentId().equals(child.id()))
                .orElseThrow(() -> ApiException.notFound("Leave request"));
        if (!request.getStatus().isOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "Already closed",
                    "This request is already " + request.getStatus().name().toLowerCase() + ".");
        }
        boolean wasApproved = request.getStatus() == ChildLeaveStatus.APPROVED;
        if (wasApproved && !today.isBefore(request.getFromDate())) {
            throw new ApiException(HttpStatus.CONFLICT, "Already started",
                    "This leave has started. Ask the class teacher if the dates need to change.");
        }
        request.cancel(actor, at);
        requests.flush();
        Map<String, Object> details = details(request, academics.section(request.getSectionId()).orElse(null));
        details.put("wasApproved", wasApproved);
        audit.record(actor, "child_leave.cancelled", "child_leave", request.getId(), details);
        return views(List.of(request), Viewer.PARENT, today).getFirst();
    }

    // ------------------------------------------------------------------ class teachers

    /** Requests of the sections the caller can see: waiting ones, and those closed in the last 30 days. */
    @Transactional(readOnly = true)
    public Inbox inbox(AttendanceScope scope, LocalDate today, Instant now) {
        TenantContext.require();
        List<ChildLeaveRequest> pending = requests.findPending().stream()
                .filter(r -> scope.canRead(r.getSectionId()))
                .toList();
        List<ChildLeaveRequest> recent = requests.findClosedSince(now.minus(RECENT)).stream()
                .filter(r -> scope.canRead(r.getSectionId()))
                .limit(RECENT_MAX)
                .toList();
        Viewer viewer = Viewer.staff(scope);
        return new Inbox(views(pending, viewer, today), views(recent, viewer, today), scope.wholeSchool());
    }

    /** Approves a waiting request; queued absence alerts for its days are cancelled. */
    public ChildLeaveView approve(AttendanceScope scope, UUID id, String comment, Actor actor, LocalDate today,
            Instant at) {
        TenantContext.require();
        ChildLeaveRequest request = pendingFor(scope, id);
        request.decide(ChildLeaveStatus.APPROVED, actor, blankToNull(comment), at);
        requests.flush();
        int cancelled = 0;
        LocalDate last = request.getToDate().isAfter(today) ? today : request.getToDate();
        for (LocalDate d = request.getFromDate(); !d.isAfter(last); d = d.plusDays(1)) {
            cancelled += queue.skip(AbsenceAlerts.dedupeKey(request.getStudentId(), d), "On approved leave");
        }
        Map<String, Object> details = details(request, academics.section(request.getSectionId()).orElse(null));
        details.put("alertsCancelled", cancelled);
        audit.record(actor, "child_leave.approved", "child_leave", id, details);
        publishDecision(request, actor, at);
        return views(List.of(request), Viewer.staff(scope), today).getFirst();
    }

    /** Rejects a waiting request; the parent sees the comment, which is required. */
    public ChildLeaveView reject(AttendanceScope scope, UUID id, String comment, Actor actor, LocalDate today,
            Instant at) {
        TenantContext.require();
        String reason = blankToNull(comment);
        if (reason == null) {
            throw ApiException.badRequest("Say why the request is rejected.", "comment");
        }
        ChildLeaveRequest request = pendingFor(scope, id);
        request.decide(ChildLeaveStatus.REJECTED, actor, reason, at);
        requests.flush();
        audit.record(actor, "child_leave.rejected", "child_leave", id,
                details(request, academics.section(request.getSectionId()).orElse(null)));
        publishDecision(request, actor, at);
        return views(List.of(request), Viewer.staff(scope), today).getFirst();
    }

    // ------------------------------------------------------------------ registers, alerts and reports

    /** Approved leave on the date, for each of these students that has some. */
    @Transactional(readOnly = true)
    public Map<UUID, ApprovedLeave> approvedOn(LocalDate date, Collection<UUID> studentIds) {
        TenantContext.require();
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ApprovedLeave> result = new HashMap<>();
        for (ChildLeaveRequest r : requests.findApprovedOn(date, Set.copyOf(studentIds))) {
            result.putIfAbsent(r.getStudentId(), new ApprovedLeave(r.getId(), r.isHalfDay(),
                    r.isHalfDay() ? AttendanceStatus.HALF_DAY : AttendanceStatus.LEAVE));
        }
        return result;
    }

    /** The days of approved leave of one student between two dates (both included), with whether each is a half day. */
    @Transactional(readOnly = true)
    public Map<LocalDate, Boolean> approvedDays(UUID studentId, LocalDate from, LocalDate to) {
        TenantContext.require();
        Map<LocalDate, Boolean> days = new LinkedHashMap<>();
        for (ChildLeaveRequest r : requests.findApprovedBetween(studentId, from, to)) {
            LocalDate first = r.getFromDate().isBefore(from) ? from : r.getFromDate();
            LocalDate last = r.getToDate().isAfter(to) ? to : r.getToDate();
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                days.putIfAbsent(d, r.isHalfDay());
            }
        }
        return days;
    }

    // ------------------------------------------------------------------ helpers

    /** Who is looking: the parent (may cancel), the student (read only) or staff with a scope (may decide). */
    private record Viewer(boolean parent, AttendanceScope scope) {

        static final Viewer PARENT = new Viewer(true, null);
        static final Viewer STUDENT = new Viewer(false, null);

        static Viewer staff(AttendanceScope scope) {
            return new Viewer(false, scope);
        }
    }

    private ChildView ownChild(UUID parentUserId, UUID studentId) {
        if (!roster.childIdsOf(parentUserId).contains(studentId)) {
            throw ApiException.notFound("Student");
        }
        return students.childrenOf(parentUserId).stream().filter(c -> c.id().equals(studentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Student"));
    }

    private FamilyLeave family(ChildView child, LocalDate today, boolean parent) {
        Optional<YearInfo> year = academics.currentYear();
        Optional<UUID> sectionId = year.flatMap(y -> roster.sectionOf(child.id(), y.id()));
        LocalDate earliest = today.minusDays(MAX_DAYS_BACK);
        LocalDate latest = today;
        if (year.isPresent()) {
            earliest = earliest.isBefore(year.get().startsOn()) ? year.get().startsOn() : earliest;
            latest = year.get().endsOn();
        }
        boolean canApply = parent && child.status() == StudentStatus.ACTIVE && sectionId.isPresent()
                && !latest.isBefore(earliest);
        String sectionLabel = child.className() == null ? null
                : child.className() + (child.sectionName() == null ? "" : " " + child.sectionName());
        return new FamilyLeave(child.id(), child.fullName(), sectionLabel, child.classTeacherName(), today, earliest,
                latest, canApply, views(requests.findByStudent(child.id()), parent ? Viewer.PARENT : Viewer.STUDENT,
                        today));
    }

    private void checkDates(LocalDate from, LocalDate to, boolean halfDay, YearInfo year, LocalDate today) {
        if (from == null) {
            throw ApiException.badRequest("Pick the first day.", "fromDate");
        }
        if (to == null) {
            throw ApiException.badRequest("Pick the last day.", "toDate");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The last day must be on or after the first day.", "toDate");
        }
        if (halfDay && !to.equals(from)) {
            throw ApiException.badRequest("A half day is one day: pick the same first and last day.", "halfDay");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Apply for at most " + MAX_RANGE_DAYS + " days at a time.", "toDate");
        }
        if (from.isBefore(today.minusDays(MAX_DAYS_BACK))) {
            throw ApiException.badRequest("Leave can be requested for days up to " + MAX_DAYS_BACK
                    + " days ago.", "fromDate");
        }
        if (from.isBefore(year.startsOn()) || to.isAfter(year.endsOn())) {
            throw ApiException.badRequest("Pick days in the current academic year, " + year.name() + " ("
                    + year.startsOn() + " to " + year.endsOn() + ").", from.isBefore(year.startsOn()) ? "fromDate"
                            : "toDate");
        }
    }

    private ChildLeaveRequest pendingFor(AttendanceScope scope, UUID id) {
        ChildLeaveRequest request = requests.findById(id).orElseThrow(() -> ApiException.notFound("Leave request"));
        if (!scope.canMark(request.getSectionId())) {
            throw new AccessDeniedException("Not this person's section");
        }
        if (request.getStatus() != ChildLeaveStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Already decided",
                    "This request is already " + request.getStatus().name().toLowerCase() + ".");
        }
        return request;
    }

    private void publishDecision(ChildLeaveRequest r, Actor actor, Instant at) {
        events.publishEvent(new ChildLeaveDecided(TenantContext.require(), r.getId(), r.getStudentId(),
                r.getSectionId(), r.getStatus(), r.getFromDate(), r.getToDate(), r.isHalfDay(), r.getRequestedById(),
                actor.id(), at));
    }

    /** Serialises applications for one child, so two sent at once cannot both pass the overlap check. */
    private void lock(UUID studentId) {
        long key = studentId.getMostSignificantBits() ^ studentId.getLeastSignificantBits() ^ 0x6368696c644c7645L;
        entityManager.createNativeQuery("select count(*) from (select pg_advisory_xact_lock(?1)) l")
                .setParameter(1, key)
                .getSingleResult();
    }

    /** Ids, the section and the dates; never the child's name or the reason, which may be medical. */
    private static Map<String, Object> details(ChildLeaveRequest r, SectionInfo section) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("studentId", r.getStudentId().toString());
        if (section != null) {
            d.put("section", section.label());
        }
        d.put("fromDate", r.getFromDate().toString());
        d.put("toDate", r.getToDate().toString());
        d.put("halfDay", r.isHalfDay());
        return d;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > COMMENT_MAX) {
            throw ApiException.badRequest("Keep the comment to " + COMMENT_MAX + " characters.", "comment");
        }
        return v;
    }

    private List<ChildLeaveView> views(List<ChildLeaveRequest> list, Viewer viewer, LocalDate today) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, RosterStudent> people = roster.students(list.stream().map(ChildLeaveRequest::getStudentId)
                .collect(Collectors.toSet()), academics.currentYear().map(YearInfo::id).orElse(null));
        Map<UUID, SectionInfo> sections = academics.sections().stream()
                .collect(Collectors.toMap(SectionInfo::id, Function.identity()));
        SchoolDays schoolDays = new SchoolDays(list);
        return list.stream().map(r -> {
            RosterStudent s = people.get(r.getStudentId());
            SectionInfo section = sections.get(r.getSectionId());
            boolean canCancel = viewer.parent() && (r.getStatus() == ChildLeaveStatus.PENDING
                    || (r.getStatus() == ChildLeaveStatus.APPROVED && today.isBefore(r.getFromDate())));
            boolean canDecide = viewer.scope() != null && r.getStatus() == ChildLeaveStatus.PENDING
                    && viewer.scope().canMark(r.getSectionId());
            return new ChildLeaveView(r.getId(), r.getStudentId(), s == null ? null : s.fullName(),
                    s == null ? null : s.admissionNo(), s == null ? null : s.rollNo(), r.getSectionId(),
                    section == null ? null : section.label(), r.getFromDate(), r.getToDate(), r.isHalfDay(),
                    schoolDays.between(r.getFromDate(), r.getToDate()), r.getReason(), r.getStatus(),
                    r.getRequestedByName(), r.getCreatedAt(), r.getDecidedByName(), r.getDecidedAt(),
                    r.getDecisionComment(), r.getCancelledByName(), r.getCancelledAt(), canCancel, canDecide);
        }).toList();
    }

    /** Counts school days for many requests with one look at the school calendar when their dates are close. */
    private final class SchoolDays {

        private final Map<LocalDate, String> holidays;

        SchoolDays(List<ChildLeaveRequest> list) {
            LocalDate first = list.stream().map(ChildLeaveRequest::getFromDate).min(LocalDate::compareTo).orElseThrow();
            LocalDate last = list.stream().map(ChildLeaveRequest::getToDate).max(LocalDate::compareTo).orElseThrow();
            holidays = ChronoUnit.DAYS.between(first, last) <= SchoolCalendar.MAX_RANGE_DAYS
                    ? calendar.holidaysBetween(first, last) : null;
        }

        int between(LocalDate from, LocalDate to) {
            if (holidays == null) {
                return calendar.schoolDaysBetween(from, to);
            }
            int days = 0;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.containsKey(d)) {
                    days++;
                }
            }
            return days;
        }
    }
}
