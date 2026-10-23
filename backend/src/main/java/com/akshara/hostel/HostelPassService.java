package com.akshara.hostel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.hostel.HostelForms.DecisionForm;
import com.akshara.hostel.HostelForms.PassRequest;
import com.akshara.hostel.HostelForms.WardenPassForm;
import com.akshara.hostel.HostelPeople.Pupil;
import com.akshara.hostel.HostelViews.PassView;
import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster.GuardianContact;

/**
 * Outing (day) and home-leave passes of boarders. A parent asks in the family app, or the warden raises one; the
 * warden approves or refuses, which tells the parent through the notifications outbox (simulated); the warden
 * marks the boarder out and back in against the pass, and a return after the return time is flagged late (an
 * alert row). Everything is audited.
 */
@Service
@Transactional
public class HostelPassService {

    static final String RELATED = "hostel_pass";
    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    private final HostelPassRepository passes;
    private final AllotmentRepository allotments;
    private final HostelRepository hostels;
    private final HostelAlertRepository alerts;
    private final HostelPeople people;
    private final HostelScope scope;
    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final TenantDirectory tenants;
    private final AuditService audit;

    HostelPassService(HostelPassRepository passes, AllotmentRepository allotments, HostelRepository hostels,
            HostelAlertRepository alerts, HostelPeople people, HostelScope scope, NotificationQueue queue,
            NotificationSettingsService messageSettings, TenantDirectory tenants, AuditService audit) {
        this.passes = passes;
        this.allotments = allotments;
        this.hostels = hostels;
        this.alerts = alerts;
        this.people = people;
        this.scope = scope;
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.tenants = tenants;
        this.audit = audit;
    }

    /* ------------------------------------------------------------------ reading */

    /** Open passes of the hostels in scope, plus those starting in the date range when given. */
    @Transactional(readOnly = true)
    public List<PassView> list(UUID hostelId, LocalDate from, LocalDate to) {
        TenantContext.require();
        Set<UUID> ids = hostelId == null ? scope.visibleIds() : Set.of(scope.require(hostelId).getId());
        if (ids.isEmpty()) {
            return List.of();
        }
        List<HostelPass> rows = new ArrayList<>(passes.openIn(ids));
        if (from != null || to != null) {
            LocalDate start = from == null ? to.minusDays(30) : from;
            LocalDate end = to == null ? start.plusDays(30) : to;
            Set<UUID> seen = rows.stream().map(HostelPass::getId).collect(Collectors.toSet());
            passes.betweenIn(ids, start, end).stream().filter(p -> seen.add(p.getId())).forEach(rows::add);
        }
        return views(rows);
    }

    @Transactional(readOnly = true)
    public PassView detail(UUID id) {
        TenantContext.require();
        HostelPass pass = passes.findById(id).orElseThrow(() -> ApiException.notFound("Pass"));
        scope.require(pass.getHostelId());
        return views(List.of(pass)).get(0);
    }

    @Transactional(readOnly = true)
    public List<PassView> ofChild(UUID parentUserId, UUID studentId) {
        TenantContext.require();
        people.requireChild(parentUserId, studentId);
        return views(passes.ofStudents(List.of(studentId)));
    }

    /* ------------------------------------------------------------------ requesting */

    /** A parent asks for an outing or home leave of their boarding child. */
    public PassView request(UUID parentUserId, UUID studentId, PassRequest form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        return create(pupil, HostelPassSource.PARENT_APP, form.kind(), form.fromDate(), form.toDate(),
                form.reason(), actor);
    }

    /** The warden raises a pass for a boarder of their hostel. */
    public PassView raise(WardenPassForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireActivePupil(form.studentId(), "studentId");
        Allotment a = allotments.activeOfStudent(pupil.id()).orElseThrow(() -> ApiException.badRequest(
                pupil.fullName() + " is not a boarder.", "studentId"));
        scope.requireWarden(a.getHostelId());
        return create(pupil, HostelPassSource.WARDEN, form.kind(), form.fromDate(), form.toDate(), form.reason(),
                actor);
    }

    private PassView create(Pupil pupil, HostelPassSource source, PassKind kind, LocalDate from, LocalDate to,
            String reason, Actor actor) {
        if (!pupil.active()) {
            throw new ApiException(HttpStatus.CONFLICT, "Not an active student",
                    pupil.fullName() + " is no longer an active student.");
        }
        Allotment a = allotments.activeOfStudent(pupil.id()).orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT, "Not a boarder", pupil.fullName() + " does not board in a hostel."));
        LocalDate today = HostelText.today();
        String problem = HostelRules.passDatesProblem(kind, from, to, today);
        if ("fromDate".equals(problem)) {
            throw ApiException.badRequest("The date must be today or within the next " + HostelRules.MAX_DAYS_AHEAD
                    + " days.", "fromDate");
        }
        if ("toDate".equals(problem)) {
            throw ApiException.badRequest(kind == PassKind.OUTING ? "An outing is for one day."
                    : "Home leave ends on or after it starts, within " + HostelRules.MAX_LEAVE_DAYS + " days.",
                    "toDate");
        }
        LocalDate end = kind == PassKind.OUTING ? from : to;
        for (HostelPass open : passes.openOfStudent(pupil.id())) {
            if (HostelRules.overlaps(from, end, open.getFromDate(), open.getToDate())) {
                throw new ApiException(HttpStatus.CONFLICT, "Pass overlaps", pupil.fullName()
                        + " already has a pass for " + open.getFromDate() + " to " + open.getToDate() + ".");
            }
        }
        Instant now = Instant.now();
        HostelPass pass = passes.save(new HostelPass(pupil.id(), a.getHostelId(), kind, source, actor, from, end,
                HostelRules.returnBy(kind, from, end), reason.trim(), now));
        Map<String, Object> details = details(pass, pupil);
        details.put("source", source.name());
        audit.record(actor, "hostel_pass.requested", "hostel_pass", pass.getId(), details);
        passes.flush();
        return views(List.of(pass)).get(0);
    }

    /* ------------------------------------------------------------------ the warden decides */

    public PassView approve(UUID id, DecisionForm form, Actor actor) {
        TenantContext.require();
        HostelPass pass = lockForWarden(id);
        if (pass.getStatus() != HostelPassStatus.REQUESTED) {
            throw new ApiException(HttpStatus.CONFLICT, "Already decided",
                    "This pass is " + pass.getStatus().name().toLowerCase() + " and cannot be approved.");
        }
        Instant now = Instant.now();
        String note = HostelText.clean(form == null ? null : form.note());
        pass.approve(note, actor, now);
        Pupil pupil = people.requirePupil(pass.getStudentId());
        tellParent(pass, pupil, true, note, now);
        Map<String, Object> details = details(pass, pupil);
        details.put("note", note == null ? "" : note);
        audit.record(actor, "hostel_pass.approved", "hostel_pass", id, details);
        passes.flush();
        return views(List.of(pass)).get(0);
    }

    public PassView reject(UUID id, DecisionForm form, Actor actor) {
        TenantContext.require();
        HostelPass pass = lockForWarden(id);
        if (!pass.getStatus().pending()) {
            throw new ApiException(HttpStatus.CONFLICT, "Already closed",
                    "This pass is " + pass.getStatus().name().toLowerCase() + " and cannot be refused.");
        }
        String note = HostelText.clean(form == null ? null : form.note());
        if (note == null) {
            throw ApiException.badRequest("Say why the pass is refused.", "note");
        }
        Instant now = Instant.now();
        pass.reject(note, actor, now);
        Pupil pupil = people.requirePupil(pass.getStudentId());
        tellParent(pass, pupil, false, note, now);
        Map<String, Object> details = details(pass, pupil);
        details.put("note", note);
        audit.record(actor, "hostel_pass.rejected", "hostel_pass", id, details);
        passes.flush();
        return views(List.of(pass)).get(0);
    }

    /** The parent withdraws the request before the boarder leaves. */
    public PassView cancel(UUID parentUserId, UUID studentId, UUID id, Actor actor) {
        TenantContext.require();
        people.requireChild(parentUserId, studentId);
        HostelPass pass = passes.lock(id).filter(p -> p.getStudentId().equals(studentId))
                .orElseThrow(() -> ApiException.notFound("Pass"));
        if (!pass.getStatus().pending()) {
            throw new ApiException(HttpStatus.CONFLICT, "Already closed",
                    "This pass is " + pass.getStatus().name().toLowerCase() + " and cannot be cancelled.");
        }
        pass.cancel();
        Pupil pupil = people.requirePupil(studentId);
        audit.record(actor, "hostel_pass.cancelled", "hostel_pass", id, details(pass, pupil));
        passes.flush();
        return views(List.of(pass)).get(0);
    }

    /* ------------------------------------------------------------------ the gate */

    public PassView markOut(UUID id, Actor actor) {
        TenantContext.require();
        HostelPass pass = lockForWarden(id);
        if (pass.getStatus() != HostelPassStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "Not approved",
                    "This pass is " + pass.getStatus().name().toLowerCase() + "; only an approved pass is used.");
        }
        Instant now = Instant.now();
        LocalDate today = HostelText.today();
        if (today.isBefore(pass.getFromDate()) || today.isAfter(pass.getToDate())) {
            throw new ApiException(HttpStatus.CONFLICT, "Not today", "This pass is for " + pass.getFromDate()
                    + (pass.getToDate().equals(pass.getFromDate()) ? "" : " to " + pass.getToDate()) + ".");
        }
        pass.markOut(actor, now);
        Pupil pupil = people.requirePupil(pass.getStudentId());
        audit.record(actor, "hostel_pass.out", "hostel_pass", id, details(pass, pupil));
        passes.flush();
        return views(List.of(pass)).get(0);
    }

    public PassView markIn(UUID id, Actor actor) {
        TenantContext.require();
        HostelPass pass = lockForWarden(id);
        if (pass.getStatus() != HostelPassStatus.OUT) {
            throw new ApiException(HttpStatus.CONFLICT, "Not out",
                    "This pass is " + pass.getStatus().name().toLowerCase() + "; the boarder is not out on it.");
        }
        Instant now = Instant.now();
        boolean late = pass.markIn(actor, now);
        Pupil pupil = people.requirePupil(pass.getStudentId());
        Map<String, Object> details = details(pass, pupil);
        details.put("late", late);
        if (late) {
            alerts.save(new HostelAlert(pass.getHostelId(), pass.getStudentId(), AlertKind.LATE_RETURN,
                    HostelText.dayOf(now), pupil.fullName() + " returned at " + HostelText.time(now)
                            + ", after the return time " + HostelText.time(pass.getReturnBy()) + ".", now));
        }
        audit.record(actor, "hostel_pass.in", "hostel_pass", id, details);
        passes.flush();
        return views(List.of(pass)).get(0);
    }

    /* ------------------------------------------------------------------ helpers */

    private HostelPass lockForWarden(UUID id) {
        HostelPass pass = passes.lock(id).orElseThrow(() -> ApiException.notFound("Pass"));
        scope.requireWarden(pass.getHostelId());
        return pass;
    }

    private void tellParent(HostelPass pass, Pupil pupil, boolean approved, String note, Instant now) {
        GuardianContact guardian = people.primaryGuardians(List.of(pupil.id())).get(pupil.id());
        if (guardian == null || guardian.phone() == null || !guardian.phone().trim().matches(PHONE)) {
            return;
        }
        MessageSettings school = messageSettings.current();
        boolean hindi = MessageTemplates.HINDI.equals(school.alertLanguage());
        Hostel hostel = hostels.findById(pass.getHostelId()).orElse(null);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("guardian", guardian.name());
        params.put("student", pupil.fullName());
        params.put("school", tenants.profile(TenantContext.require()).map(p -> p.name()).orElse(""));
        params.put("hostel", hostel == null ? "" : hostel.getName());
        params.put("kind", pass.getKind() == PassKind.OUTING ? (hindi ? "बाहर जाने की छुट्टी" : "outing")
                : (hindi ? "घर जाने की छुट्टी" : "home leave"));
        params.put("dates", pass.getFromDate().equals(pass.getToDate()) ? pass.getFromDate().toString()
                : pass.getFromDate() + " to " + pass.getToDate());
        params.put("decision", approved ? (hindi ? "स्वीकृत है" : "approved") : (hindi ? "अस्वीकृत है" : "refused"));
        params.put("note", note == null ? "" : note);
        Recipient to = Recipient.phone(school.absenceAlertChannel(), guardian.phone().trim(), guardian.name(),
                Map.of(), "hostel_pass:" + pass.getId() + ":" + pass.getStatus());
        queue.enqueue(MessageTemplates.HOSTEL_PASS_DECIDED, school.alertLanguage(), params,
                new Related(RELATED, pass.getId(), pupil.fullName() + (pupil.className() == null ? ""
                        : " (" + pupil.className() + ")")), List.of(to), now);
    }

    private static Map<String, Object> details(HostelPass p, Pupil pupil) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("student", pupil.fullName());
        d.put("admissionNo", pupil.admissionNo());
        d.put("kind", p.getKind().name());
        d.put("fromDate", p.getFromDate().toString());
        d.put("toDate", p.getToDate().toString());
        d.put("status", p.getStatus().name());
        return d;
    }

    List<PassView> views(Collection<HostelPass> rows) {
        Set<UUID> studentIds = rows.stream().map(HostelPass::getStudentId).collect(Collectors.toSet());
        Map<UUID, Pupil> pupils = people.pupils(studentIds);
        Map<UUID, Hostel> hostelById = hostels.findAllById(rows.stream().map(HostelPass::getHostelId).toList())
                .stream().collect(Collectors.toMap(Hostel::getId, h -> h));
        Set<UUID> myChildren = HostelAuth.isParent() ? people.childrenOf(CurrentUser.requireId()) : Set.of();
        List<PassView> result = new ArrayList<>();
        for (HostelPass p : rows) {
            Pupil pupil = pupils.get(p.getStudentId());
            Hostel h = hostelById.get(p.getHostelId());
            boolean warden = h != null && scope.canWarden(h);
            result.add(new PassView(p.getId(), p.getStudentId(), pupil == null ? null : pupil.fullName(),
                    pupil == null ? null : pupil.admissionNo(), pupil == null ? null : pupil.className(),
                    p.getHostelId(), h == null ? null : h.getName(), p.getKind(), p.getSource(),
                    p.getRequestedByName(), p.getFromDate(), p.getToDate(), p.getReturnBy(), p.getReason(),
                    p.getStatus(), p.getDecidedByName(), p.getDecidedAt(), p.getDecisionNote(), p.getOutAt(),
                    p.getOutByName(), p.getInAt(), p.getInByName(), p.isLate(), p.getCreatedAt(),
                    p.getStatus().pending() && myChildren.contains(p.getStudentId()),
                    warden && p.getStatus().pending(),
                    warden && p.getStatus() == HostelPassStatus.APPROVED,
                    warden && p.getStatus() == HostelPassStatus.OUT));
        }
        return result;
    }
}
