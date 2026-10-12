package com.akshara.frontoffice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.frontoffice.FrontOfficeForms.DecisionForm;
import com.akshara.frontoffice.FrontOfficeForms.OfficePassForm;
import com.akshara.frontoffice.FrontOfficeForms.PickupRequest;
import com.akshara.frontoffice.FrontOfficeForms.ReleaseForm;
import com.akshara.frontoffice.FrontOfficePeople.Pupil;
import com.akshara.frontoffice.FrontOfficeViews.GatePassView;
import com.akshara.frontoffice.FrontOfficeViews.GateScreen;
import com.akshara.frontoffice.FrontOfficeViews.StudentRef;
import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster.GuardianContact;

/**
 * Early pickup gate passes. A parent asks in the app (or the office raises one on their call); the office approves,
 * which draws a six-digit OTP, sends it to the student's primary guardian through the notifications outbox
 * (simulated) and tells the class teacher through the app; the gate enters the OTP the collector reads out and
 * records who collected. The OTP is kept only as a hash and never appears in any response. It expires after four
 * hours; five wrong tries lock the pass. Everything is audited.
 */
@Service
@Transactional
public class GatePassService {

    /** The problem type of a wrong OTP (the detail says how many tries are left) and of a locked pass. */
    public static final String WRONG_OTP_TYPE = "urn:akshara:frontoffice:wrong-otp";
    public static final String LOCKED_TYPE = "urn:akshara:frontoffice:pass-locked";
    /** The {@link Related} type of the OTP message, per pass. */
    static final String RELATED = "gate_pass";
    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    private final GatePassRepository passes;
    private final FrontOfficePeople people;
    private final VisitorService visitors;
    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final TenantDirectory tenants;
    private final ApplicationEventPublisher events;
    private final AuditService audit;

    GatePassService(GatePassRepository passes, FrontOfficePeople people, VisitorService visitors,
            NotificationQueue queue, NotificationSettingsService messageSettings, TenantDirectory tenants,
            ApplicationEventPublisher events, AuditService audit) {
        this.passes = passes;
        this.people = people;
        this.visitors = visitors;
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.tenants = tenants;
        this.events = events;
        this.audit = audit;
    }

    /* ------------------------------------------------------------------ reading */

    /** The office's list: open passes first, then the day's closed ones. */
    @Transactional(readOnly = true)
    public List<GatePassView> list(LocalDate date) {
        TenantContext.require();
        LocalDate day = date == null ? FrontOfficeText.today() : date;
        List<GatePass> rows = new ArrayList<>(passes.open());
        Set<UUID> seen = new java.util.HashSet<>();
        rows.forEach(p -> seen.add(p.getId()));
        passes.between(FrontOfficeText.startOfDay(day), FrontOfficeText.startOfDay(day.plusDays(1))).stream()
                .filter(p -> seen.add(p.getId())).forEach(rows::add);
        return views(rows, Instant.now());
    }

    @Transactional(readOnly = true)
    public GatePassView detail(UUID id) {
        TenantContext.require();
        return views(List.of(require(id)), Instant.now()).get(0);
    }

    @Transactional(readOnly = true)
    public List<StudentRef> searchStudents(String q) {
        TenantContext.require();
        return people.searchStudents(q, 20);
    }

    /** The gate: approved passes (today's, and any still valid), who is inside and who is expected. */
    @Transactional(readOnly = true)
    public List<GatePassView> atGate() {
        TenantContext.require();
        Instant now = Instant.now();
        return views(passes.open().stream().filter(p -> p.getStatus() == PassStatus.APPROVED).toList(), now);
    }

    @Transactional(readOnly = true)
    public List<GatePassView> ofChildren(UUID parentUserId) {
        TenantContext.require();
        Set<UUID> children = people.childrenOf(parentUserId);
        if (children.isEmpty()) {
            return List.of();
        }
        return views(passes.ofStudents(children), Instant.now());
    }

    @Transactional(readOnly = true)
    public List<GatePassView> ofChild(UUID parentUserId, UUID studentId) {
        TenantContext.require();
        people.requireChild(parentUserId, studentId);
        return views(passes.ofStudents(List.of(studentId)), Instant.now());
    }

    /* ------------------------------------------------------------------ requesting */

    /** A parent asks for an early pickup of their child. */
    public GatePassView request(UUID parentUserId, UUID studentId, PickupRequest form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        if (!pupil.active()) {
            throw new ApiException(HttpStatus.CONFLICT, "Not an active student",
                    pupil.fullName() + " is no longer an active student.");
        }
        return create(pupil, PassSource.PARENT_APP, form.pickupAt(), form.reason(), form.collectorKind(),
                form.collectorName(), form.collectorPhone(), actor);
    }

    /** The office raises a pass on a parent's phone call. */
    public GatePassView raise(OfficePassForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireActivePupil(form.studentId(), "studentId");
        return create(pupil, PassSource.OFFICE, form.pickupAt(), form.reason(), form.collectorKind(),
                form.collectorName(), form.collectorPhone(), actor);
    }

    private GatePassView create(Pupil pupil, PassSource source, Instant pickupAt, String reason, CollectorKind kind,
            String collectorName, String collectorPhone, Actor actor) {
        Instant now = Instant.now();
        if (pickupAt.isBefore(now.minusSeconds(15 * 60))) {
            throw ApiException.badRequest("The pickup time cannot be in the past.", "pickupAt");
        }
        if (!FrontOfficeText.dayOf(pickupAt).equals(FrontOfficeText.today())) {
            throw ApiException.badRequest("An early pickup is for today only.", "pickupAt");
        }
        String name = FrontOfficeText.clean(collectorName);
        String phone = VisitorService.phone(collectorPhone, "collectorPhone");
        if (kind == CollectorKind.OTHER && name == null) {
            throw ApiException.badRequest("Name the person who will collect the child.", "collectorName");
        }
        if (kind == CollectorKind.OTHER && phone == null) {
            throw ApiException.badRequest("Give that person's mobile number.", "collectorPhone");
        }
        if (!passes.openOfStudent(pupil.id()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Pass already open",
                    "There is already an open early pickup pass for " + pupil.fullName() + " today.");
        }
        if (people.primaryGuardians(List.of(pupil.id())).get(pupil.id()) == null) {
            throw new ApiException(HttpStatus.CONFLICT, "No primary guardian",
                    pupil.fullName() + " has no primary parent or guardian to send the OTP to.");
        }
        GatePass pass = passes.save(new GatePass(pupil.id(), source, actor, pickupAt, reason.trim(), kind, name,
                phone, now));
        Map<String, Object> details = auditDetails(pass, pupil);
        details.put("source", source.name());
        audit.record(actor, "gate_pass.requested", "gate_pass", pass.getId(), details);
        passes.flush();
        return views(List.of(pass), now).get(0);
    }

    /* ------------------------------------------------------------------ the office decides */

    public GatePassView approve(UUID id, DecisionForm form, Actor actor) {
        TenantContext.require();
        GatePass pass = passes.lock(id).orElseThrow(() -> ApiException.notFound("Gate pass"));
        if (pass.getStatus() != PassStatus.REQUESTED) {
            throw new ApiException(HttpStatus.CONFLICT, "Already decided",
                    "This pass is " + pass.getStatus().name().toLowerCase() + " and cannot be approved.");
        }
        Pupil pupil = people.requirePupil(pass.getStudentId());
        GuardianContact guardian = people.primaryGuardians(List.of(pupil.id())).get(pupil.id());
        if (guardian == null || guardian.phone() == null || !guardian.phone().trim().matches(PHONE)) {
            throw new ApiException(HttpStatus.CONFLICT, "No primary guardian",
                    pupil.fullName() + " has no primary parent or guardian with a mobile number for the OTP.");
        }
        Instant now = Instant.now();
        String otp = Otp.generate();
        Instant expiresAt = Otp.expiry(now);
        pass.approve(Otp.hash(pass.getId(), otp), expiresAt, FrontOfficeText.clean(form == null ? null
                : form.note()), actor, now);
        sendOtp(pass, pupil, guardian, otp, expiresAt, now);
        events.publishEvent(new EarlyPickupApproved(TenantContext.require(), pass.getId(), pupil.id(),
                pupil.fullName(), pupil.sectionId(), pupil.classTeacherUserId(), pass.getPickupAt(), now));
        Map<String, Object> details = auditDetails(pass, pupil);
        details.put("otpSentTo", FrontOfficeText.maskPhone(guardian.phone()));
        details.put("otpExpiresAt", expiresAt.toString());
        audit.record(actor, "gate_pass.approved", "gate_pass", id, details);
        passes.flush();
        return views(List.of(pass), now).get(0);
    }

    public GatePassView reject(UUID id, DecisionForm form, Actor actor) {
        TenantContext.require();
        GatePass pass = passes.lock(id).orElseThrow(() -> ApiException.notFound("Gate pass"));
        if (!pass.getStatus().open()) {
            throw new ApiException(HttpStatus.CONFLICT, "Already closed",
                    "This pass is " + pass.getStatus().name().toLowerCase() + " and cannot be rejected.");
        }
        String note = FrontOfficeText.clean(form == null ? null : form.note());
        if (note == null) {
            throw ApiException.badRequest("Say why the pass is refused.", "note");
        }
        Instant now = Instant.now();
        boolean wasApproved = pass.getStatus() == PassStatus.APPROVED;
        pass.reject(note, actor, now);
        if (wasApproved) {
            queue.skipRelated(RELATED, id, "Pass withdrawn");
        }
        Pupil pupil = people.requirePupil(pass.getStudentId());
        Map<String, Object> details = auditDetails(pass, pupil);
        details.put("note", note);
        audit.record(actor, "gate_pass.rejected", "gate_pass", id, details);
        passes.flush();
        return views(List.of(pass), now).get(0);
    }

    /** The parent withdraws their request (while the office has not decided, or before the gate released). */
    public GatePassView cancel(UUID parentUserId, UUID studentId, UUID id, Actor actor) {
        TenantContext.require();
        people.requireChild(parentUserId, studentId);
        GatePass pass = passes.lock(id).filter(p -> p.getStudentId().equals(studentId))
                .orElseThrow(() -> ApiException.notFound("Gate pass"));
        if (!pass.getStatus().open()) {
            throw new ApiException(HttpStatus.CONFLICT, "Already closed",
                    "This pass is " + pass.getStatus().name().toLowerCase() + " and cannot be cancelled.");
        }
        Instant now = Instant.now();
        boolean wasApproved = pass.getStatus() == PassStatus.APPROVED;
        pass.cancel(now);
        if (wasApproved) {
            queue.skipRelated(RELATED, id, "Cancelled by the parent");
        }
        Pupil pupil = people.requirePupil(studentId);
        audit.record(actor, "gate_pass.cancelled", "gate_pass", id, auditDetails(pass, pupil));
        passes.flush();
        return views(List.of(pass), now).get(0);
    }

    /* ------------------------------------------------------------------ the gate */

    /**
     * Security enters the OTP the collector reads out. Wrong: 409 with the tries left; the fifth locks the pass. The
     * wrong try is kept (and audited) even though the request fails, so the count cannot be reset by retrying.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public GatePassView release(UUID id, ReleaseForm form, Actor actor) {
        TenantContext.require();
        GatePass pass = passes.lock(id).orElseThrow(() -> ApiException.notFound("Gate pass"));
        Instant now = Instant.now();
        Pupil pupil = people.requirePupil(pass.getStudentId());
        if (pass.getStatus() == PassStatus.APPROVED && pass.otpExpired(now)) {
            pass.expire(now);
            audit.record(actor, "gate_pass.expired", "gate_pass", id, auditDetails(pass, pupil));
            passes.flush();
        }
        if (pass.getStatus() == PassStatus.LOCKED) {
            throw new ApiException(HttpStatus.CONFLICT, "Pass locked",
                    "This pass was locked after five wrong OTPs. Ask the office to raise a new one.", Map.of(),
                    LOCKED_TYPE);
        }
        if (pass.getStatus() == PassStatus.EXPIRED) {
            throw new ApiException(HttpStatus.CONFLICT, "OTP expired",
                    "The OTP expired four hours after approval. Ask the office to raise a new pass.");
        }
        if (pass.getStatus() != PassStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "Not approved",
                    "This pass is " + pass.getStatus().name().toLowerCase() + "; only an approved pass is released.");
        }
        if (!Otp.matches(pass.getId(), pass.getOtpHash(), form.otp())) {
            boolean locked = pass.wrongAttempt(now);
            Map<String, Object> details = auditDetails(pass, pupil);
            details.put("attemptsLeft", pass.attemptsLeft());
            audit.record(actor, locked ? "gate_pass.locked" : "gate_pass.wrong_otp", "gate_pass", id, details);
            passes.flush();
            if (locked) {
                throw new ApiException(HttpStatus.CONFLICT, "Pass locked",
                        "Wrong OTP. The pass is now locked after five wrong tries; ask the office to raise a new one.",
                        Map.of("otp", "Wrong OTP."), LOCKED_TYPE);
            }
            int left = pass.attemptsLeft();
            throw new ApiException(HttpStatus.CONFLICT, "Wrong OTP",
                    "Wrong OTP. " + left + (left == 1 ? " try" : " tries") + " left.", Map.of("otp", "Wrong OTP."),
                    WRONG_OTP_TYPE);
        }
        pass.release(form.collectedByName().trim(), actor, now);
        queue.skipRelated(RELATED, id, "Child released at the gate");
        Map<String, Object> details = auditDetails(pass, pupil);
        details.put("collectedBy", pass.getCollectedByName());
        audit.record(actor, "gate_pass.released", "gate_pass", id, details);
        passes.flush();
        return views(List.of(pass), now).get(0);
    }

    /** The gate's screen in one call. */
    @Transactional(readOnly = true)
    public GateScreen screen() {
        TenantContext.require();
        var day = visitors.day(null);
        return new GateScreen(day.date(), atGate(), visitors.inside(), day.expected());
    }

    /* ------------------------------------------------------------------ helpers */

    private void sendOtp(GatePass pass, Pupil pupil, GuardianContact guardian, String otp, Instant expiresAt,
            Instant now) {
        MessageSettings school = messageSettings.current();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("guardian", guardian.name());
        params.put("student", pupil.fullName());
        params.put("school", tenants.profile(TenantContext.require()).map(p -> p.name()).orElse(""));
        params.put("otp", otp);
        params.put("collector", pass.getCollectorKind() == CollectorKind.SELF ? "you" : pass.getCollectorName());
        params.put("time", FrontOfficeText.time(expiresAt));
        Recipient to = Recipient.phone(school.absenceAlertChannel(), guardian.phone().trim(), guardian.name(),
                Map.of(), "gate_pass:" + pass.getId());
        queue.enqueue(MessageTemplates.GATE_PASS_OTP, school.alertLanguage(), params,
                new Related(RELATED, pass.getId(), pupil.fullName() + (pupil.className() == null ? ""
                        : " (" + pupil.className() + ")")), List.of(to), now);
    }

    private GatePass require(UUID id) {
        return passes.findById(id).orElseThrow(() -> ApiException.notFound("Gate pass"));
    }

    private static Map<String, Object> auditDetails(GatePass p, Pupil pupil) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("student", pupil.fullName());
        details.put("admissionNo", pupil.admissionNo());
        details.put("status", p.getStatus().name());
        details.put("pickupAt", p.getPickupAt().toString());
        details.put("collector", p.getCollectorKind() == CollectorKind.SELF ? "parent" : p.getCollectorName());
        return details;
    }

    List<GatePassView> views(Collection<GatePass> rows, Instant now) {
        Set<UUID> studentIds = rows.stream().map(GatePass::getStudentId).collect(java.util.stream.Collectors.toSet());
        Map<UUID, Pupil> pupils = people.pupils(studentIds);
        Map<UUID, GuardianContact> guardians = people.primaryGuardians(studentIds);
        boolean manage = FrontOfficeAuth.canManage();
        boolean gate = FrontOfficeAuth.atGate();
        Set<UUID> myChildren = FrontOfficeAuth.permissions().contains(com.akshara.shared.Permissions.CHILD_VIEW)
                ? people.childrenOf(com.akshara.shared.CurrentUser.requireId()) : Set.of();
        List<GatePassView> result = new ArrayList<>();
        for (GatePass p : rows) {
            Pupil pupil = pupils.get(p.getStudentId());
            GuardianContact g = guardians.get(p.getStudentId());
            PassStatus status = p.getStatus() == PassStatus.APPROVED && p.otpExpired(now) ? PassStatus.EXPIRED
                    : p.getStatus();
            result.add(new GatePassView(p.getId(), p.getStudentId(), pupil == null ? null : pupil.fullName(),
                    pupil == null ? null : pupil.admissionNo(), pupil == null ? null : pupil.className(),
                    p.getSource(), p.getRequestedByName(), p.getPickupAt(), p.getReason(), p.getCollectorKind(),
                    p.getCollectorName(), FrontOfficeText.maskPhone(p.getCollectorPhone()), status,
                    p.getOtpExpiresAt(), p.attemptsLeft(), g == null ? null : g.name(),
                    g == null ? null : FrontOfficeText.maskPhone(g.phone()), p.getApprovedByName(),
                    p.getApprovedAt(), p.getDecisionNote(), p.getReleasedByName(), p.getReleasedAt(),
                    p.getCollectedByName(), p.getCreatedAt(),
                    status.open() && myChildren.contains(p.getStudentId()),
                    manage && status.open(),
                    gate && status == PassStatus.APPROVED));
        }
        return result;
    }
}
