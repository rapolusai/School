package com.akshara.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.privacy.PrivacyForms.AcceptForm;
import com.akshara.privacy.PrivacyForms.ConsentChoice;
import com.akshara.privacy.PrivacyForms.PaperConsentForm;
import com.akshara.privacy.PrivacyViews.ChildConsent;
import com.akshara.privacy.PrivacyViews.ConsentEntry;
import com.akshara.privacy.PrivacyViews.ConsentPage;
import com.akshara.privacy.PrivacyViews.ConsentRow;
import com.akshara.privacy.PrivacyViews.ParentPrivacy;
import com.akshara.privacy.PrivacyViews.PurposeState;
import com.akshara.privacy.PrivacyViews.StudentConsent;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;
import com.akshara.students.StudentService.StudentDetail;
import com.akshara.students.StudentService.StudentPage;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * Parents' consent per child and purpose, against a notice version: given online in the parent app (on first sign-in
 * after a new version, and later from the privacy page), or entered by staff from a signed paper form. Every decision
 * is a new row, so the full history is kept, and each one publishes {@link ConsentChanged}.
 */
@Service
@Transactional
public class ConsentService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String NONE = "NONE";

    private final ConsentRecordRepository records;
    private final PrivacyNoticeService notices;
    private final StudentService students;
    private final StudentRoster roster;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    ConsentService(ConsentRecordRepository records, PrivacyNoticeService notices, StudentService students,
            StudentRoster roster, AuditService audit, ApplicationEventPublisher events) {
        this.records = records;
        this.notices = notices;
        this.students = students;
        this.roster = roster;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ parents

    /** The parent's children with their consent, and whether the current notice still has to be accepted. */
    @Transactional(readOnly = true)
    public ParentPrivacy forParent(UUID userId) {
        TenantContext.require();
        List<ChildView> children = students.childrenOf(userId);
        Optional<PrivacyNotice> notice = notices.current();
        Integer version = notice.map(PrivacyNotice::getNumber).orElse(null);
        Map<UUID, List<ConsentRecord>> history = byStudent(records.historyOf(
                children.stream().map(ChildView::id).toList()));
        List<ChildConsent> views = children.stream().map(c -> {
            List<ConsentRecord> h = history.getOrDefault(c.id(), List.of());
            boolean needs = version != null && c.status() == StudentStatus.ACTIVE && !essentialAgreed(h, version);
            return new ChildConsent(c.id(), c.fullName(), c.className(), c.sectionName(), c.status(), needs,
                    states(h, version), h.stream().map(ConsentService::entry).toList());
        }).toList();
        return new ParentPrivacy(notices.currentView().orElse(null),
                views.stream().anyMatch(ChildConsent::needsConsent), views);
    }

    /**
     * The parent accepts the current notice: essential processing and their optional choices for each listed child,
     * all recorded against that version. 409 when a newer version was published in the meantime.
     */
    public ParentPrivacy accept(UUID userId, AcceptForm form, Actor actor) {
        TenantContext.require();
        PrivacyNotice notice = notices.requireCurrent();
        if (form.noticeVersion() != notice.getNumber()) {
            String detail = "A newer privacy notice has been published. Read it and accept it again.";
            throw new ApiException(HttpStatus.CONFLICT, "Notice changed", detail, Map.of("noticeVersion", detail));
        }
        Set<UUID> own = roster.childIdsOf(userId);
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < form.choices().size(); i++) {
            UUID studentId = form.choices().get(i).studentId();
            if (!own.contains(studentId) || !seen.add(studentId)) {
                throw ApiException.badRequest("Pick one of your children.", "choices[" + i + "].studentId");
            }
        }
        Instant now = Instant.now();
        for (ConsentChoice choice : form.choices()) {
            UUID studentId = choice.studentId();
            save(ConsentRecord.online(studentId, PrivacyPurpose.ESSENTIAL, ConsentAction.GIVEN, notice, actor.id(),
                    actor.name(), now));
            save(ConsentRecord.online(studentId, PrivacyPurpose.PHOTOS, decision(choice.photos()), notice,
                    actor.id(), actor.name(), now));
            save(ConsentRecord.online(studentId, PrivacyPurpose.WHATSAPP, decision(choice.whatsapp()), notice,
                    actor.id(), actor.name(), now));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("noticeVersion", notice.getNumber());
            details.put("method", ConsentMethod.ONLINE.name());
            details.put("photos", choice.photos());
            details.put("whatsapp", choice.whatsapp());
            audit.record(actor, "consent.given", "student", studentId, details);
        }
        records.flush();
        return forParent(userId);
    }

    /**
     * Gives or withdraws one optional purpose for the parent's own child (any other student is 404). Essential
     * processing cannot be switched off here: that is an erasure request. Asking for the state it already has changes
     * nothing.
     */
    public ParentPrivacy change(UUID userId, UUID studentId, PrivacyPurpose purpose, boolean given, Actor actor) {
        TenantContext.require();
        if (!roster.childIdsOf(userId).contains(studentId)) {
            throw ApiException.notFound("Student");
        }
        if (!purpose.optional()) {
            throw ApiException.badRequest("Essential school records cannot be switched off here. To have your "
                    + "child's data erased after they leave the school, raise an erasure request.", "purpose");
        }
        PrivacyNotice notice = notices.requireCurrent();
        Optional<ConsentRecord> latest = records.history(studentId).stream()
                .filter(r -> r.getPurpose() == purpose).findFirst();
        boolean currentlyGiven = latest.map(r -> r.getAction() == ConsentAction.GIVEN).orElse(false);
        if (given == currentlyGiven) {
            return forParent(userId);
        }
        ConsentAction action = given ? ConsentAction.GIVEN : ConsentAction.WITHDRAWN;
        save(ConsentRecord.online(studentId, purpose, action, notice, actor.id(), actor.name(), Instant.now()));
        records.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("purpose", purpose.name());
        details.put("noticeVersion", notice.getNumber());
        audit.record(actor, given ? "consent.given" : "consent.withdrawn", "student", studentId, details);
        return forParent(userId);
    }

    // ------------------------------------------------------------------ staff

    /** A student's consent and its full history; 404 for a student of another school. */
    @Transactional(readOnly = true)
    public StudentConsent studentConsents(UUID studentId) {
        TenantContext.require();
        StudentDetail student = students.detail(studentId);
        Integer version = notices.current().map(PrivacyNotice::getNumber).orElse(null);
        List<ConsentRecord> history = records.history(studentId);
        return new StudentConsent(student.id(), student.fullName(), student.admissionNo(), student.status(), version,
                states(history, version), history.stream().map(ConsentService::entry).toList());
    }

    /**
     * Enters a signed paper consent form (usually at admission): essential processing and the two optional choices,
     * against the current notice, in the name of the parent who signed it.
     */
    public StudentConsent recordPaper(UUID studentId, PaperConsentForm form, Actor actor) {
        TenantContext.require();
        students.detail(studentId);
        PrivacyNotice notice = notices.requireCurrent();
        if (form.signedOn().isAfter(LocalDate.now(INDIA))) {
            throw ApiException.badRequest("The form cannot be signed in the future.", "signedOn");
        }
        String signedBy = form.givenByName().trim();
        String reference = PrivacyNoticeService.blankToNull(form.paperReference());
        Instant now = Instant.now();
        save(ConsentRecord.paper(studentId, PrivacyPurpose.ESSENTIAL, ConsentAction.GIVEN, notice, signedBy,
                form.signedOn(), reference, actor.id(), actor.name(), now));
        save(ConsentRecord.paper(studentId, PrivacyPurpose.PHOTOS, decision(form.photos()), notice, signedBy,
                form.signedOn(), reference, actor.id(), actor.name(), now));
        save(ConsentRecord.paper(studentId, PrivacyPurpose.WHATSAPP, decision(form.whatsapp()), notice, signedBy,
                form.signedOn(), reference, actor.id(), actor.name(), now));
        records.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("noticeVersion", notice.getNumber());
        details.put("method", ConsentMethod.PAPER.name());
        details.put("signedOn", form.signedOn().toString());
        details.put("photos", form.photos());
        details.put("whatsapp", form.whatsapp());
        audit.record(actor, "consent.recorded", "student", studentId, details);
        return studentConsents(studentId);
    }

    /** Students of the current year (any status) with their consent, and the year's coverage of active students. */
    @Transactional(readOnly = true)
    public ConsentPage consents(String search, int page, int size) {
        TenantContext.require();
        Integer version = notices.current().map(PrivacyNotice::getNumber).orElse(null);
        StudentPage found = students.list(new StudentQuery(null, null, null, null, search, Math.max(page, 0),
                PrivacyForms.pageSize(size)));
        Map<UUID, List<ConsentRecord>> history = byStudent(records.historyOf(
                found.items().stream().map(StudentRow::id).toList()));
        List<ConsentRow> rows = found.items().stream()
                .map(s -> new ConsentRow(s.id(), s.fullName(), s.admissionNo(), s.className(), s.sectionName(),
                        s.status(), states(history.getOrDefault(s.id(), List.of()), version)))
                .toList();
        Set<UUID> active = activeStudentIds();
        long agreed = version == null ? 0
                : records.essentialGivenFor(version).stream().filter(active::contains).count();
        return new ConsentPage(rows, found.page(), found.size(), found.total(), version, active.size(), agreed);
    }

    // ------------------------------------------------------------------ helpers

    /** Consent decisions given by this sign-in (for the parent's own data export). */
    List<ConsentEntry> givenBy(UUID userId) {
        return records.givenBy(userId).stream().map(ConsentService::entry).toList();
    }

    /** A student's consent history, newest first (for the child's data export). */
    List<ConsentEntry> historyOf(UUID studentId) {
        return records.history(studentId).stream().map(ConsentService::entry).toList();
    }

    private Set<UUID> activeStudentIds() {
        Set<UUID> ids = new HashSet<>();
        int page = 0;
        while (true) {
            StudentPage result = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, null, page,
                    StudentService.MAX_PAGE_SIZE));
            result.items().forEach(r -> ids.add(r.id()));
            if (result.items().size() < StudentService.MAX_PAGE_SIZE
                    || (long) (page + 1) * StudentService.MAX_PAGE_SIZE >= result.total()) {
                return ids;
            }
            page++;
        }
    }

    private void save(ConsentRecord record) {
        records.save(record);
        events.publishEvent(new ConsentChanged(TenantContext.require(), record.getStudentId(), record.getPurpose(),
                record.getAction(), record.getMethod(), record.getNoticeVersion(), record.getAt()));
    }

    private static ConsentAction decision(boolean yes) {
        return yes ? ConsentAction.GIVEN : ConsentAction.DECLINED;
    }

    private static boolean essentialAgreed(List<ConsentRecord> history, int version) {
        return history.stream().anyMatch(r -> r.getPurpose() == PrivacyPurpose.ESSENTIAL
                && r.getAction() == ConsentAction.GIVEN && r.getNoticeVersion() == version);
    }

    /** The latest decision per purpose, in purpose order; NONE when there is none. */
    static List<PurposeState> states(List<ConsentRecord> newestFirst, Integer currentVersion) {
        List<PurposeState> result = new ArrayList<>();
        for (PrivacyPurpose purpose : PrivacyPurpose.values()) {
            Optional<ConsentRecord> latest = newestFirst.stream().filter(r -> r.getPurpose() == purpose).findFirst();
            result.add(latest.map(r -> new PurposeState(purpose, r.getAction().name(), r.getAt(), r.getNoticeVersion(),
                    r.getMethod(), currentVersion != null && r.getNoticeVersion() == currentVersion))
                    .orElse(new PurposeState(purpose, NONE, null, null, null, false)));
        }
        return result;
    }

    static ConsentEntry entry(ConsentRecord r) {
        return new ConsentEntry(r.getId(), r.getPurpose(), r.getAction(), r.getMethod(), r.getNoticeVersion(),
                r.getGivenByName(), r.getRecordedByName(), r.getPaperReference(), r.getSignedOn(), r.getAt());
    }

    private static Map<UUID, List<ConsentRecord>> byStudent(Collection<ConsentRecord> newestFirst) {
        return newestFirst.stream().collect(Collectors.groupingBy(ConsentRecord::getStudentId, LinkedHashMap::new,
                Collectors.toList()));
    }
}
