package com.akshara.staff;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffForms.BalanceFields;
import com.akshara.staff.StaffForms.LeaveApplication;
import com.akshara.staff.StaffForms.LeaveTypeFields;
import com.akshara.staff.StaffRoster.Member;
import com.akshara.staff.StaffService.LeaveApprover;
import com.akshara.staff.StaffService.PersonRef;

/**
 * Leave: the school's leave types, balances per person and academic year, applying (working days counted by
 * {@link WorkingDayCalendar}), approval by the department head or the School Admin/Principal, cancelling, and the
 * staff attendance days that approved leave marks ON_LEAVE. Every change is audited; approvals publish
 * {@link StaffLeaveApproved}.
 */
@Service
@Transactional
public class LeaveService {

    /** The longest request, in calendar days (26 weeks of maternity leave fit). */
    public static final int MAX_RANGE_DAYS = 200;

    public record YearRef(UUID id, String name, LocalDate startsOn, LocalDate endsOn, boolean current) {

        static YearRef of(YearInfo y) {
            return new YearRef(y.id(), y.name(), y.startsOn(), y.endsOn(), y.current());
        }
    }

    /** {@code inUse}: requests or hand-set balances refer to it, so it can be switched off but not deleted. */
    public record LeaveTypeView(UUID id, String name, String code, BigDecimal yearlyQuota, BigDecimal carryForwardCap,
            boolean halfDayAllowed, boolean lossOfPay, boolean active, boolean inUse) {
    }

    /** {@code available} = opening + accrued − taken; null for loss of pay, which has no limit. */
    public record BalanceView(UUID leaveTypeId, String leaveTypeName, String code, boolean lossOfPay,
            boolean halfDayAllowed, boolean active, BigDecimal opening, BigDecimal accrued, BigDecimal taken,
            BigDecimal pending, BigDecimal available, boolean setByHand) {
    }

    /**
     * A leave request as its requester, an approver or an admin sees it. {@code routing} is DEPARTMENT_HEAD (with
     * {@code approverName}) or SCHOOL. {@code canCancel} and {@code canDecide} are for the person asking.
     * {@code available} is the requester's balance of the type, filled in the approver inbox.
     */
    public record LeaveRequestView(UUID id, UUID userId, String userName, String employeeCode, String departmentName,
            UUID leaveTypeId, String leaveTypeName, String leaveTypeCode, boolean lossOfPay, UUID academicYearId,
            String academicYearName, LocalDate fromDate, LocalDate toDate, boolean halfDay, BigDecimal days,
            String reason, LeaveStatus status, String routing, String approverName, String decidedByName,
            Instant decidedAt, String decisionComment, String cancelledByName, Instant cancelledAt,
            String cancelComment, Instant createdAt, boolean canCancel, boolean canDecide, BigDecimal available) {
    }

    /** The signed-in person's leave for one academic year. {@code waitingForMe}: requests they can decide. */
    public record MyLeave(YearRef year, List<YearRef> years, LeaveApprover approver, List<BalanceView> balances,
            List<LeaveRequestView> requests, int waitingForMe) {
    }

    /** One staff member's leave for one academic year, for their profile page. */
    public record StaffLeave(UUID userId, YearRef year, List<YearRef> years, List<BalanceView> balances,
            List<LeaveRequestView> requests) {
    }

    /**
     * What applying would cost: the working days in the range ({@code nonWorkingDays} were skipped), the balance of
     * the type now, and after this request and the ones still waiting. {@code enough} is always true for loss of pay.
     */
    public record Preview(YearRef year, LocalDate fromDate, LocalDate toDate, boolean halfDay, List<LocalDate> workingDays,
            int nonWorkingDays, BigDecimal days, BalanceView balance, BigDecimal availableAfter, boolean enough,
            boolean overlaps) {
    }

    /** Who is asking: their id and name, and whether they hold leave.approve (decide anyone's leave). */
    public record Caller(Actor actor, boolean approvesAll) {

        UUID id() {
            return actor.id();
        }
    }

    private record Checked(LeaveType type, YearInfo year, List<LocalDate> workingDays, int nonWorkingDays,
            BigDecimal days) {
    }

    private final LeaveTypeRepository types;
    private final LeaveRequestRepository requests;
    private final LeaveBalanceRepository balanceRows;
    private final LeaveBalances balances;
    private final DepartmentRepository departments;
    private final StaffRoster roster;
    private final AcademicsDirectory academics;
    private final WorkingDayCalendar calendar;
    private final StaffAttendanceService attendance;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final PersonLock lock;

    public LeaveService(LeaveTypeRepository types, LeaveRequestRepository requests, LeaveBalanceRepository balanceRows,
            LeaveBalances balances, DepartmentRepository departments, StaffRoster roster, AcademicsDirectory academics,
            WorkingDayCalendar calendar, StaffAttendanceService attendance, AuditService audit,
            ApplicationEventPublisher events, PersonLock lock) {
        this.types = types;
        this.requests = requests;
        this.balanceRows = balanceRows;
        this.balances = balances;
        this.departments = departments;
        this.roster = roster;
        this.academics = academics;
        this.calendar = calendar;
        this.attendance = attendance;
        this.audit = audit;
        this.events = events;
        this.lock = lock;
    }

    // ------------------------------------------------------------------ leave types

    @Transactional(readOnly = true)
    public List<LeaveTypeView> types(boolean includeInactive) {
        TenantContext.require();
        return types.findAllOrdered().stream()
                .filter(t -> includeInactive || t.isActive())
                .map(this::typeView)
                .toList();
    }

    public LeaveTypeView createType(LeaveTypeFields form, Actor actor) {
        TenantContext.require();
        LeaveType.Fields fields = cleanType(form, new UUID(0, 0));
        LeaveType type = types.saveAndFlush(new LeaveType(fields));
        record(actor, "leave_type.created", "leave_type", type.getId(), typeDetails(type));
        return typeView(type);
    }

    public LeaveTypeView updateType(UUID id, LeaveTypeFields form, Actor actor) {
        TenantContext.require();
        LeaveType type = types.findById(id).orElseThrow(() -> ApiException.notFound("Leave type"));
        LeaveType.Fields fields = cleanType(form, id);
        type.apply(fields);
        types.flush();
        record(actor, "leave_type.updated", "leave_type", id, typeDetails(type));
        return typeView(type);
    }

    public void deleteType(UUID id, Actor actor) {
        TenantContext.require();
        LeaveType type = types.findById(id).orElseThrow(() -> ApiException.notFound("Leave type"));
        if (inUse(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "In use", type.getName()
                    + " has leave requests or balances. Switch it off instead, so the history stays.");
        }
        types.delete(type);
        types.flush();
        record(actor, "leave_type.deleted", "leave_type", id, Map.of("name", type.getName()));
    }

    /**
     * Adds the usual Indian school leave types that the school does not have yet (by name or code): casual, sick,
     * earned, maternity (26 weeks, Sundays not counted), paternity and loss of pay. Returns every type.
     */
    public List<LeaveTypeView> addStandardTypes(Actor actor) {
        TenantContext.require();
        List<LeaveTypeFields> standard = List.of(
                new LeaveTypeFields("Casual leave", "CL", new BigDecimal("12"), BigDecimal.ZERO, true, false, true),
                new LeaveTypeFields("Sick leave", "SL", new BigDecimal("10"), new BigDecimal("20"), true, false, true),
                new LeaveTypeFields("Earned leave", "EL", new BigDecimal("15"), new BigDecimal("30"), false, false,
                        true),
                new LeaveTypeFields("Maternity leave", "ML", new BigDecimal("156"), BigDecimal.ZERO, false, false,
                        true),
                new LeaveTypeFields("Paternity leave", "PL", new BigDecimal("15"), BigDecimal.ZERO, false, false,
                        true),
                new LeaveTypeFields("Loss of pay", "LOP", BigDecimal.ZERO, BigDecimal.ZERO, true, true, true));
        UUID none = new UUID(0, 0);
        for (LeaveTypeFields f : standard) {
            if (!types.nameTaken(f.name(), none) && !types.codeTaken(f.code(), none)) {
                createType(f, actor);
            }
        }
        return types(true);
    }

    // ------------------------------------------------------------------ my leave

    /** The caller's balances and requests for an academic year (the current one by default). */
    @Transactional(readOnly = true)
    public MyLeave mine(Caller caller, UUID yearId) {
        TenantContext.require();
        Member me = roster.find(caller.id()).orElseThrow(() -> ApiException.notFound("Staff member"));
        LeaveApprover approver = departmentHeadOf(me)
                .map(h -> new LeaveApprover(LeaveApprover.DEPARTMENT_HEAD, new PersonRef(h.userId(), h.name())))
                .orElse(new LeaveApprover(LeaveApprover.SCHOOL, null));
        int waiting = inbox(caller, false).size();
        List<YearRef> years = academics.years().stream().map(YearRef::of).toList();
        Optional<YearInfo> year = pickYear(yearId);
        if (year.isEmpty()) {
            return new MyLeave(null, years, approver, List.of(), List.of(), waiting);
        }
        return new MyLeave(YearRef.of(year.get()), years, approver, balancesOf(me, year.get(), false),
                views(requests.findByUserAndYear(me.userId(), year.get().id()), caller, false), waiting);
    }

    /** A staff member's balances and requests for an academic year, for their profile page. */
    @Transactional(readOnly = true)
    public StaffLeave ofStaff(UUID userId, UUID yearId, Caller caller) {
        TenantContext.require();
        Member member = roster.find(userId).orElseThrow(() -> ApiException.notFound("Staff member"));
        List<YearRef> years = academics.years().stream().map(YearRef::of).toList();
        Optional<YearInfo> year = pickYear(yearId);
        if (year.isEmpty()) {
            return new StaffLeave(userId, null, years, List.of(), List.of());
        }
        return new StaffLeave(userId, YearRef.of(year.get()), years, balancesOf(member, year.get(), true),
                views(requests.findByUserAndYear(userId, year.get().id()), caller, false));
    }

    /** What a request would cost, without saving anything. Bad dates or types answer 400, like applying. */
    @Transactional(readOnly = true)
    public Preview preview(Caller caller, UUID leaveTypeId, LocalDate from, LocalDate to, boolean halfDay) {
        TenantContext.require();
        Member me = roster.find(caller.id()).orElseThrow(() -> ApiException.notFound("Staff member"));
        Checked c = check(leaveTypeId, from, to, halfDay);
        LeaveBalances.Entry entry = balances.of(me.userId(), joinedOn(me), c.year(), c.type());
        BigDecimal left = entry.balance().availableAfterPending();
        boolean overlaps = !requests.findOverlapping(me.userId(), from, to,
                List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED)).isEmpty();
        return new Preview(YearRef.of(c.year()), from, to, halfDay, c.workingDays(), c.nonWorkingDays(), c.days(),
                balanceView(entry), left == null ? null : left.subtract(c.days()), LeaveMath.fits(entry.balance(),
                        c.days()), overlaps);
    }

    /** Applies for leave. The request goes to the department head when there is one, else to the admins. */
    public LeaveRequestView apply(Caller caller, LeaveApplication form, Instant at) {
        TenantContext.require();
        Member me = roster.find(caller.id()).filter(Member::active)
                .orElseThrow(() -> ApiException.notFound("Staff member"));
        Checked c = check(form.leaveTypeId(), form.fromDate(), form.toDate(), form.halfDay());
        lock.lock(me.userId());
        refuseOverlap(me.userId(), form.fromDate(), form.toDate(), null);
        LeaveBalances.Entry entry = balances.of(me.userId(), joinedOn(me), c.year(), c.type());
        if (!LeaveMath.fits(entry.balance(), c.days())) {
            throw notEnough(c.type(), entry.balance(), c.days(), true);
        }
        UUID approver = departmentHeadOf(me).map(Member::userId).orElse(null);
        LeaveRequest request = requests.saveAndFlush(new LeaveRequest(me.userId(), c.type().getId(), c.year().id(),
                form.fromDate(), form.toDate(), form.halfDay(), c.days(), form.reason().trim(), approver, at));
        Map<String, Object> details = requestDetails(request, c.type());
        details.put("routedTo", approver == null ? LeaveApprover.SCHOOL : LeaveApprover.DEPARTMENT_HEAD);
        audit.record(caller.actor(), "leave_request.created", "leave_request", request.getId(), details);
        return view(request, caller, false);
    }

    // ------------------------------------------------------------------ deciding

    /** Pending requests the caller can decide: routed to them as department head, or (leave.approve) to the school. */
    @Transactional(readOnly = true)
    public List<LeaveRequestView> inbox(Caller caller, boolean everything) {
        TenantContext.require();
        Map<UUID, Member> staff = new HashMap<>();
        roster.all().forEach(m -> staff.put(m.userId(), m));
        List<LeaveRequest> mine = requests.findPending().stream()
                .filter(r -> !r.getUserId().equals(caller.id()))
                .filter(r -> {
                    UUID routed = effectiveApprover(r, staff);
                    if (caller.id().equals(routed)) {
                        return true;
                    }
                    return caller.approvesAll() && (everything || routed == null);
                })
                .toList();
        return views(mine, caller, true);
    }

    public LeaveRequestView approve(UUID id, String comment, Caller caller, Instant at) {
        TenantContext.require();
        LeaveRequest request = pending(id, caller);
        lock.lock(request.getUserId());
        LeaveType type = types.findById(request.getLeaveTypeId()).orElseThrow();
        Member member = roster.find(request.getUserId()).orElseThrow(() -> ApiException.notFound("Staff member"));
        if (!member.active()) {
            throw new ApiException(HttpStatus.CONFLICT, "Has left", member.name() + " has left the school.");
        }
        YearInfo year = academics.year(request.getAcademicYearId()).orElseThrow();
        LeaveMath.Balance balance = balances.of(member.userId(), joinedOn(member), year, type).balance();
        if (balance.available() != null && request.getDays().compareTo(balance.available()) > 0) {
            throw notEnough(type, balance, request.getDays(), false);
        }
        request.decide(LeaveStatus.APPROVED, caller.actor(), blankToNull(comment), at);
        requests.flush();
        List<LocalDate> days = request.isHalfDay() ? List.of(request.getFromDate())
                : calendar.workingDays(request.getFromDate(), request.getToDate());
        attendance.applyLeave(request.getUserId(), request.getId(), days, request.isHalfDay(), caller.actor(), at);
        Map<String, Object> details = requestDetails(request, type);
        details.put("userId", request.getUserId().toString());
        audit.record(caller.actor(), "leave_request.approved", "leave_request", id, details);
        events.publishEvent(new StaffLeaveApproved(TenantContext.require(), id, request.getUserId(), type.getId(),
                request.getFromDate(), request.getToDate(), request.isHalfDay(), request.getDays(), days,
                caller.id(), at));
        return view(request, caller, false);
    }

    public LeaveRequestView reject(UUID id, String comment, Caller caller, Instant at) {
        TenantContext.require();
        String reason = blankToNull(comment);
        if (reason == null) {
            throw ApiException.badRequest("Say why the request is rejected.", "comment");
        }
        LeaveRequest request = pending(id, caller);
        request.decide(LeaveStatus.REJECTED, caller.actor(), reason, at);
        requests.flush();
        LeaveType type = types.findById(request.getLeaveTypeId()).orElseThrow();
        Map<String, Object> details = requestDetails(request, type);
        details.put("userId", request.getUserId().toString());
        audit.record(caller.actor(), "leave_request.rejected", "leave_request", id, details);
        return view(request, caller, false);
    }

    /**
     * Cancels a pending or approved request: the requester any time while it is pending and before an approved leave
     * starts; the approver (department head or leave.approve) any time. Cancelling approved leave gives the days back
     * and clears the ON_LEAVE days (a day the person checked in on becomes present).
     */
    public LeaveRequestView cancel(UUID id, String comment, Caller caller, Instant at) {
        TenantContext.require();
        LeaveRequest request = requests.findById(id).orElseThrow(() -> ApiException.notFound("Leave request"));
        boolean requester = request.getUserId().equals(caller.id());
        if (!requester && !canDecide(request, caller)) {
            throw new AccessDeniedException("Not this person's request");
        }
        if (!request.getStatus().holdsDays()) {
            throw new ApiException(HttpStatus.CONFLICT, "Already closed",
                    "This request is already " + request.getStatus().name().toLowerCase() + ".");
        }
        if (requester && request.getStatus() == LeaveStatus.APPROVED
                && !StaffAttendanceService.today().isBefore(request.getFromDate())) {
            throw new ApiException(HttpStatus.CONFLICT, "Already started",
                    "Approved leave that has started can only be cancelled by the person who approves it.");
        }
        cancel(request, blankToNull(comment), caller.actor(), at);
        return view(request, caller, false);
    }

    /** When someone leaves: cancels their pending requests and approved leave that starts after the leaving day. */
    int cancelForLeaver(UUID userId, LocalDate leftOn, Actor actor) {
        List<LeaveRequest> open = requests.findByUserAndStatusIn(userId,
                List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED));
        int count = 0;
        Instant now = Instant.now();
        for (LeaveRequest r : open) {
            if (r.getStatus() == LeaveStatus.PENDING || r.getFromDate().isAfter(leftOn)) {
                cancel(r, "Left the school", actor, now);
                count++;
            }
        }
        return count;
    }

    private void cancel(LeaveRequest request, String comment, Actor actor, Instant at) {
        boolean wasApproved = request.getStatus() == LeaveStatus.APPROVED;
        request.cancel(actor, comment, at);
        requests.flush();
        if (wasApproved) {
            attendance.removeLeave(request.getId(), actor, at);
            events.publishEvent(new StaffLeaveCancelled(TenantContext.require(), request.getId(), request.getUserId(),
                    request.getFromDate(), request.getToDate(), actor.id(), at));
        }
        LeaveType type = types.findById(request.getLeaveTypeId()).orElseThrow();
        Map<String, Object> details = requestDetails(request, type);
        details.put("userId", request.getUserId().toString());
        details.put("wasApproved", wasApproved);
        audit.record(actor, "leave_request.cancelled", "leave_request", request.getId(), details);
    }

    // ------------------------------------------------------------------ balances set by hand

    /** Sets a person's opening balance and allowance of a type for a year (the current year by default). */
    public BalanceView setBalance(BalanceFields form, Actor actor) {
        TenantContext.require();
        Member member = roster.find(form.userId())
                .orElseThrow(() -> ApiException.badRequest("Pick a staff member of this school.", "userId"));
        LeaveType type = types.findById(form.leaveTypeId())
                .orElseThrow(() -> ApiException.badRequest("Pick a leave type.", "leaveTypeId"));
        if (type.isLossOfPay()) {
            throw ApiException.badRequest("Loss of pay has no balance.", "leaveTypeId");
        }
        YearInfo year = form.academicYearId() == null
                ? academics.currentYear().orElseThrow(() -> ApiException.badRequest(
                        "Set up the current academic year in School setup first.", "academicYearId"))
                : academics.year(form.academicYearId())
                        .orElseThrow(() -> ApiException.badRequest("Pick an academic year.", "academicYearId"));
        BigDecimal opening = checkDays(form.opening(), "opening", 999);
        BigDecimal accrued = checkDays(form.accrued(), "accrued", 999);
        LeaveBalance row = balanceRows.findByUserIdAndLeaveTypeIdAndAcademicYearId(member.userId(), type.getId(),
                year.id()).orElse(null);
        if (row == null) {
            balanceRows.saveAndFlush(new LeaveBalance(member.userId(), type.getId(), year.id(), opening, accrued));
        } else {
            row.set(opening, accrued);
            balanceRows.flush();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userId", member.userId().toString());
        details.put("leaveType", type.getCode());
        details.put("academicYear", year.name());
        details.put("opening", opening);
        details.put("accrued", accrued);
        audit.record(actor, "leave_balance.set", "leave_balance", member.userId(), details);
        return balanceView(balances.of(member.userId(), joinedOn(member), year, type));
    }

    // ------------------------------------------------------------------ routing

    /** The department head who approves this person's leave, if their department has an active head who is not them. */
    Optional<Member> departmentHeadOf(Member member) {
        UUID departmentId = member.departmentId();
        if (departmentId == null) {
            return Optional.empty();
        }
        return departments.findById(departmentId)
                .map(Department::getHeadUserId)
                .filter(head -> !head.equals(member.userId()))
                .flatMap(roster::find)
                .filter(Member::active);
    }

    /** The department head a pending request waits for, or null (the school) when it has none or they left. */
    private static UUID effectiveApprover(LeaveRequest r, Map<UUID, Member> staff) {
        UUID routed = r.getApproverUserId();
        if (routed == null) {
            return null;
        }
        Member head = staff.get(routed);
        return head != null && head.active() ? routed : null;
    }

    private boolean canDecide(LeaveRequest r, Caller caller) {
        if (r.getUserId().equals(caller.id())) {
            return false;
        }
        if (caller.approvesAll()) {
            return true;
        }
        return caller.id().equals(r.getApproverUserId())
                && roster.find(caller.id()).map(Member::active).orElse(false);
    }

    private LeaveRequest pending(UUID id, Caller caller) {
        LeaveRequest request = requests.findById(id).orElseThrow(() -> ApiException.notFound("Leave request"));
        if (request.getUserId().equals(caller.id())) {
            throw new AccessDeniedException("Nobody decides their own leave");
        }
        if (!canDecide(request, caller)) {
            throw new AccessDeniedException("Not this person's request to decide");
        }
        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Already decided",
                    "This request is already " + request.getStatus().name().toLowerCase() + ".");
        }
        return request;
    }

    // ------------------------------------------------------------------ checks

    private Checked check(UUID leaveTypeId, LocalDate from, LocalDate to, boolean halfDay) {
        LeaveType type = types.findById(leaveTypeId).filter(LeaveType::isActive)
                .orElseThrow(() -> ApiException.badRequest("Pick a leave type.", "leaveTypeId"));
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The last day cannot be before the first day.", "toDate");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Apply for at most " + MAX_RANGE_DAYS + " days at a time.", "toDate");
        }
        if (halfDay && !from.equals(to)) {
            throw ApiException.badRequest("A half day is one day: pick the same first and last day.", "halfDay");
        }
        if (halfDay && !type.isHalfDayAllowed()) {
            throw ApiException.badRequest(type.getName() + " cannot be taken for half a day.", "halfDay");
        }
        YearInfo year = academics.years().stream()
                .filter(y -> !from.isBefore(y.startsOn()) && !from.isAfter(y.endsOn()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(
                        "No academic year covers these dates. Ask the school to set it up.", "fromDate"));
        if (to.isAfter(year.endsOn())) {
            throw ApiException.badRequest("Leave cannot run past the end of the academic year (" + year.endsOn()
                    + "). Apply for the rest separately.", "toDate");
        }
        List<LocalDate> working = calendar.workingDays(from, to);
        if (working.isEmpty()) {
            throw ApiException.badRequest(
                    "There are no working days in these dates (Sundays and school holidays are not counted).",
                    "toDate");
        }
        int total = (int) ChronoUnit.DAYS.between(from, to) + 1;
        return new Checked(type, year, working, total - working.size(), LeaveMath.days(working.size(), halfDay));
    }

    private void refuseOverlap(UUID userId, LocalDate from, LocalDate to, UUID exceptId) {
        List<LeaveRequest> overlapping = requests.findOverlapping(userId, from, to,
                List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED)).stream()
                .filter(r -> !r.getId().equals(exceptId))
                .toList();
        if (!overlapping.isEmpty()) {
            LeaveRequest r = overlapping.getFirst();
            throw ApiException.conflict("These dates overlap a leave request that is already "
                    + r.getStatus().name().toLowerCase() + " (" + r.getFromDate()
                    + (r.getToDate().equals(r.getFromDate()) ? "" : " to " + r.getToDate()) + ").", "fromDate");
        }
    }

    private static ApiException notEnough(LeaveType type, LeaveMath.Balance balance, BigDecimal days,
            boolean countPending) {
        BigDecimal left = countPending ? balance.availableAfterPending() : balance.available();
        String detail = "Not enough " + type.getName() + ": " + plain(days) + (days.compareTo(BigDecimal.ONE) == 0
                ? " day" : " days") + " asked, " + plain(left.max(BigDecimal.ZERO)) + " left"
                + (countPending && balance.pending().signum() > 0
                        ? " after " + plain(balance.pending()) + " waiting for approval" : "")
                + ". Choose fewer days or Loss of pay.";
        return ApiException.conflict(detail, "leaveTypeId");
    }

    private LeaveType.Fields cleanType(LeaveTypeFields form, UUID exceptId) {
        String name = form.name().trim();
        String code = form.code().trim().toUpperCase();
        if (types.nameTaken(name, exceptId)) {
            throw ApiException.conflict("A leave type called " + name + " already exists.", "name");
        }
        if (types.codeTaken(code, exceptId)) {
            throw ApiException.conflict("The code " + code + " is already used.", "code");
        }
        BigDecimal quota = form.lossOfPay() ? LeaveMath.ZERO : checkDays(form.yearlyQuota(), "yearlyQuota", 366);
        BigDecimal cap = form.lossOfPay() ? LeaveMath.ZERO : checkDays(form.carryForwardCap(), "carryForwardCap", 366);
        return new LeaveType.Fields(name, code, quota, cap, form.halfDayAllowed(), form.lossOfPay(),
                form.active() == null || form.active());
    }

    private static BigDecimal checkDays(BigDecimal value, String field, int max) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(max)) > 0 || !LeaveMath.isWholeOrHalf(v)) {
            throw ApiException.badRequest("Use whole or half days from 0 to " + max + ".", field);
        }
        return LeaveMath.scale(v.stripTrailingZeros().scale() < 0 ? v.setScale(0) : v);
    }

    // ------------------------------------------------------------------ views

    private List<BalanceView> balancesOf(Member member, YearInfo year, boolean includeInactive) {
        List<LeaveType> all = types.findAllOrdered();
        List<UUID> usedTypes = requests.findByUserAndYear(member.userId(), year.id()).stream()
                .map(LeaveRequest::getLeaveTypeId).distinct().toList();
        List<LeaveType> shown = all.stream()
                .filter(t -> t.isActive() || includeInactive || usedTypes.contains(t.getId()))
                .toList();
        return balances.of(member.userId(), joinedOn(member), year, shown).values().stream()
                .map(this::balanceView)
                .toList();
    }

    private BalanceView balanceView(LeaveBalances.Entry e) {
        LeaveType t = e.type();
        LeaveMath.Balance b = e.balance();
        return new BalanceView(t.getId(), t.getName(), t.getCode(), t.isLossOfPay(), t.isHalfDayAllowed(),
                t.isActive(), b.opening(), b.accrued(), b.taken(), b.pending(), b.available(), e.setByHand());
    }

    private LeaveRequestView view(LeaveRequest r, Caller caller, boolean withBalance) {
        return views(List.of(r), caller, withBalance).getFirst();
    }

    private List<LeaveRequestView> views(List<LeaveRequest> list, Caller caller, boolean withBalance) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, LeaveType> typeById = types.findAll().stream()
                .collect(Collectors.toMap(LeaveType::getId, Function.identity()));
        Map<UUID, YearInfo> yearById = academics.years().stream()
                .collect(Collectors.toMap(YearInfo::id, Function.identity()));
        Map<UUID, Member> staff = new HashMap<>();
        roster.all().forEach(m -> staff.put(m.userId(), m));
        Map<UUID, String> departmentNames = departments.findAll().stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        LocalDate today = StaffAttendanceService.today();
        List<LeaveRequestView> views = new ArrayList<>();
        for (LeaveRequest r : list) {
            LeaveType type = typeById.get(r.getLeaveTypeId());
            Member who = staff.get(r.getUserId());
            UUID routed = effectiveApprover(r, staff);
            Member head = routed == null ? null : staff.get(routed);
            boolean requester = r.getUserId().equals(caller.id());
            boolean decider = !requester && (caller.approvesAll() || caller.id().equals(r.getApproverUserId()));
            boolean canCancel = r.getStatus().holdsDays() && (decider || (requester
                    && (r.getStatus() == LeaveStatus.PENDING || today.isBefore(r.getFromDate()))));
            boolean canDecide = r.getStatus() == LeaveStatus.PENDING && decider;
            BigDecimal available = null;
            if (withBalance && who != null && type != null && !type.isLossOfPay()) {
                YearInfo y = yearById.get(r.getAcademicYearId());
                available = balances.of(who.userId(), joinedOn(who), y, type).balance().available();
            }
            YearInfo year = yearById.get(r.getAcademicYearId());
            views.add(new LeaveRequestView(r.getId(), r.getUserId(), who == null ? null : who.name(),
                    who == null ? null : who.employeeCode(),
                    who == null || who.departmentId() == null ? null : departmentNames.get(who.departmentId()),
                    r.getLeaveTypeId(), type == null ? null : type.getName(), type == null ? null : type.getCode(),
                    type != null && type.isLossOfPay(), r.getAcademicYearId(), year == null ? null : year.name(),
                    r.getFromDate(), r.getToDate(), r.isHalfDay(), r.getDays(), r.getReason(), r.getStatus(),
                    routed == null ? LeaveApprover.SCHOOL : LeaveApprover.DEPARTMENT_HEAD,
                    head == null ? null : head.name(), r.getDecidedByName(), r.getDecidedAt(),
                    r.getDecisionComment(), r.getCancelledByName(), r.getCancelledAt(), r.getCancelComment(),
                    r.getCreatedAt(), canCancel, canDecide, available));
        }
        return views;
    }

    private LeaveTypeView typeView(LeaveType t) {
        return new LeaveTypeView(t.getId(), t.getName(), t.getCode(), t.getYearlyQuota(), t.getCarryForwardCap(),
                t.isHalfDayAllowed(), t.isLossOfPay(), t.isActive(), inUse(t.getId()));
    }

    private boolean inUse(UUID typeId) {
        return requests.countByLeaveTypeId(typeId) > 0 || balanceRows.countByLeaveTypeId(typeId) > 0;
    }

    private Optional<YearInfo> pickYear(UUID yearId) {
        if (yearId != null) {
            return Optional.of(academics.year(yearId).orElseThrow(() -> ApiException.notFound("Academic year")));
        }
        return academics.currentYear();
    }

    private static LocalDate joinedOn(Member m) {
        return m.profile() == null ? null : m.profile().getDateOfJoining();
    }

    private static Map<String, Object> requestDetails(LeaveRequest r, LeaveType type) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("leaveType", type.getCode());
        details.put("from", r.getFromDate().toString());
        details.put("to", r.getToDate().toString());
        details.put("days", r.getDays());
        return details;
    }

    private static Map<String, Object> typeDetails(LeaveType t) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", t.getName());
        details.put("code", t.getCode());
        details.put("yearlyQuota", t.getYearlyQuota());
        details.put("carryForwardCap", t.getCarryForwardCap());
        details.put("halfDayAllowed", t.isHalfDayAllowed());
        details.put("lossOfPay", t.isLossOfPay());
        details.put("active", t.isActive());
        return details;
    }

    private void record(Actor actor, String action, String entityType, UUID id, Map<String, ?> details) {
        if (actor == null) {
            audit.record(action, entityType, id, details);
        } else {
            audit.record(actor, action, entityType, id, details);
        }
    }

    /** 1.0 → "1", 1.5 → "1.5". */
    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
