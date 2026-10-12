package com.akshara.certificates;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.certificates.CertificateForms.CardBatchForm;
import com.akshara.certificates.CertificateForms.CardForm;
import com.akshara.certificates.CertificateForms.ReissueForm;
import com.akshara.certificates.CertificateSubjects.Ref;
import com.akshara.certificates.CertificateViews.CardBatchResult;
import com.akshara.certificates.CertificateViews.CardRegister;
import com.akshara.certificates.CertificateViews.IdCardView;
import com.akshara.certificates.CertificateViews.PhotoView;
import com.akshara.certificates.CertificateViews.Subject;
import com.akshara.files.FileOwner;
import com.akshara.files.FileStore;
import com.akshara.files.IncomingFile;
import com.akshara.files.StoredFileInfo;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffPayrollInputs.PayrollPerson;
import com.akshara.staff.StaffService;
import com.akshara.staff.StaffService.StaffDetail;
import com.akshara.students.StudentRoster.GuardianContact;
import com.akshara.students.StudentRoster.RosterStudent;
import com.akshara.students.StudentService.StudentDetail;

/**
 * The ID card register: one card in use per student or member of staff, issued one at a time or for a whole
 * section (or the staff) at once, reissued (lost, damaged, details changed) with the old card cancelled, and the
 * photo kept in the file store under this module. What a card shows that the other modules do not keep (blood
 * group when the student record lacks it, the emergency number, validity) lives here only.
 */
@Service
@Transactional
public class IdCardService {

    public static final String MODULE = "certificates";
    public static final String PHOTO_STUDENT = "photo_student";
    public static final String PHOTO_STAFF = "photo_staff";
    public static final int MAX_PAGE_SIZE = 500;
    private static final Logger log = LoggerFactory.getLogger(IdCardService.class);

    private final IdCardRepository cards;
    private final CertificateSetupService setup;
    private final CertificateSubjects subjects;
    private final StaffService staff;
    private final FileStore files;
    private final AuditService audit;

    IdCardService(IdCardRepository cards, CertificateSetupService setup, CertificateSubjects subjects,
            StaffService staff, FileStore files, AuditService audit) {
        this.cards = cards;
        this.setup = setup;
        this.subjects = subjects;
        this.staff = staff;
        this.files = files;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    /** The register: cards in use (or all) of students of a section, of all students, or of the staff. */
    @Transactional(readOnly = true)
    public CardRegister register(SubjectType subjectType, UUID sectionId, boolean all, int page, int size) {
        TenantContext.require();
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        List<IdCard> found;
        if (subjectType == SubjectType.STUDENT && sectionId != null) {
            subjects.section(sectionId).orElseThrow(() -> ApiException.notFound("section"));
            List<UUID> ids = subjects.activeInSection(sectionId).stream().map(RosterStudent::id).toList();
            found = ids.isEmpty() ? List.of() : all ? cards.findByStudentIdInOrderByCreatedAtDesc(ids)
                    : cards.findByStudentIdInAndStatus(ids, CardStatus.ACTIVE);
            if (!all) {
                Map<UUID, Integer> order = new HashMap<>();
                for (int i = 0; i < ids.size(); i++) {
                    order.put(ids.get(i), i);
                }
                found = found.stream().sorted((a, b) -> Integer.compare(order.get(a.getStudentId()),
                        order.get(b.getStudentId()))).toList();
            }
        } else {
            found = all ? cards.findBySubjectTypeOrderByCreatedAtDesc(subjectType)
                    : cards.findBySubjectTypeAndStatusOrderByCreatedAtDesc(subjectType, CardStatus.ACTIVE);
        }
        List<IdCard> items = found.stream().skip((long) page * pageSize).limit(pageSize).toList();
        return new CardRegister(setup.header(), views(items), page, pageSize, found.size());
    }

    @Transactional(readOnly = true)
    public IdCardView detail(UUID id) {
        TenantContext.require();
        return views(List.of(find(id))).getFirst();
    }

    /** The card in use of a student, for the family app. */
    @Transactional(readOnly = true)
    public Optional<IdCardView> activeOfStudent(UUID studentId) {
        TenantContext.require();
        return cards.findByStudentIdAndStatus(studentId, CardStatus.ACTIVE).map(c -> views(List.of(c)).getFirst());
    }

    /** Students of a section (or the active staff) with no card in use. */
    @Transactional(readOnly = true)
    public List<Subject> pending(SubjectType subjectType, UUID sectionId) {
        TenantContext.require();
        if (subjectType == SubjectType.STAFF) {
            Set<UUID> have = cards.findBySubjectTypeAndStatusOrderByCreatedAtDesc(SubjectType.STAFF,
                    CardStatus.ACTIVE).stream().map(IdCard::getStaffUserId).collect(Collectors.toSet());
            return subjects.activeStaff().stream().filter(p -> !have.contains(p.userId())).map(subjects::subject)
                    .toList();
        }
        if (sectionId == null) {
            return List.of();
        }
        List<RosterStudent> roster = subjects.activeInSection(sectionId);
        List<UUID> ids = roster.stream().map(RosterStudent::id).toList();
        Set<UUID> have = ids.isEmpty() ? Set.of() : cards.findByStudentIdInAndStatus(ids, CardStatus.ACTIVE)
                .stream().map(IdCard::getStudentId).collect(Collectors.toSet());
        List<Ref> refs = ids.stream().filter(id -> !have.contains(id)).map(id -> new Ref(SubjectType.STUDENT, id))
                .toList();
        return new ArrayList<>(subjects.describe(refs).values());
    }

    // ------------------------------------------------------------------ issuing

    /** Issues a card to one person who has none in use. */
    public IdCardView issue(CardForm form, Actor actor) {
        UUID id = form.subjectType() == SubjectType.STUDENT ? form.studentId() : form.staffUserId();
        String field = form.subjectType() == SubjectType.STUDENT ? "studentId" : "staffUserId";
        if (id == null) {
            throw ApiException.badRequest(form.subjectType() == SubjectType.STUDENT ? "Choose a student."
                    : "Choose a member of staff.", field);
        }
        Defaults d = defaults(form.subjectType(), id).orElseThrow(() -> ApiException.badRequest(
                form.subjectType() == SubjectType.STUDENT ? "No such student." : "No such member of staff.", field));
        if (active(form.subjectType(), id).isPresent()) {
            throw ApiException.conflict("This person already has a card in use. Reissue it instead.", field);
        }
        IdCard card = newCard(form.subjectType(), id, 1, pick(form.bloodGroup(), d.bloodGroup()),
                pick(form.emergencyPhone(), d.emergencyPhone()), form.validUntil(), null, null, null, null, actor);
        audit.record(actor, "id_card.issued", "id_card", card.getId(), details(card));
        return detail(card.getId());
    }

    /** Cards for everyone in a section, or on the staff, who has none in use. */
    public CardBatchResult issueBatch(CardBatchForm form, Actor actor) {
        boolean forStaff = Boolean.TRUE.equals(form.staff());
        if (!forStaff && form.sectionId() == null) {
            throw ApiException.badRequest("Choose a section, or the staff.", "sectionId");
        }
        if (!forStaff) {
            subjects.section(form.sectionId()).orElseThrow(() -> ApiException.badRequest("No such section.",
                    "sectionId"));
        }
        List<Subject> pending = pending(forStaff ? SubjectType.STAFF : SubjectType.STUDENT, form.sectionId());
        List<IdCard> issued = new ArrayList<>();
        int skipped = 0;
        for (Subject s : pending) {
            Optional<Defaults> d = defaults(s.type(), s.id());
            if (d.isEmpty()) {
                skipped++;
                continue;
            }
            issued.add(newCard(s.type(), s.id(), 1, d.get().bloodGroup(), d.get().emergencyPhone(),
                    form.validUntil(), null, null, null, null, actor));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subjectType", forStaff ? "STAFF" : "STUDENT");
        details.put("sectionId", form.sectionId());
        details.put("issued", issued.size());
        details.put("skipped", skipped);
        audit.record(actor, "id_card.batch_issued", "id_card", forStaff ? null : form.sectionId(), details);
        return new CardBatchResult(issued.size(), skipped, views(issued));
    }

    /** Cancels the card and issues the next one (issue number + 1) with the reason and any lost-card fee noted. */
    public IdCardView reissue(UUID id, ReissueForm form, Actor actor) {
        IdCard old = cards.lockById(id).orElseThrow(() -> ApiException.notFound("ID card"));
        if (old.getStatus() != CardStatus.ACTIVE) {
            throw ApiException.conflict("This card was already replaced.", "status");
        }
        Instant now = Instant.now();
        old.cancelled(now);
        cards.saveAndFlush(old);
        Long fee = form.lostFeePaise();
        String note = CertificateSetupService.blankToNull(form.feeNote());
        if (fee != null && fee > 0 && note == null) {
            note = "Lost card fee noted; to be collected by the fee office.";
        }
        IdCard card = newCard(old.getSubjectType(), old.subjectId(), old.getIssueNo() + 1,
                pick(form.bloodGroup(), old.getBloodGroup()), pick(form.emergencyPhone(), old.getEmergencyPhone()),
                null, old.getId(), form.reason().trim(), fee, note, actor);
        Map<String, Object> details = details(card);
        details.put("replaces", old.getCardNo());
        details.put("reason", card.getReissueReason());
        details.put("lostFeePaise", fee);
        audit.record(actor, "id_card.reissued", "id_card", card.getId(), details);
        return detail(card.getId());
    }

    // ------------------------------------------------------------------ photos

    /** The person's photo (the latest upload), if any. */
    @Transactional(readOnly = true)
    public Optional<PhotoView> photo(SubjectType subjectType, UUID personId) {
        TenantContext.require();
        List<StoredFileInfo> list = files.list(owner(subjectType, personId));
        return list.isEmpty() ? Optional.empty()
                : Optional.of(new PhotoView(list.getLast().id(), list.getLast().name()));
    }

    /** Replaces the person's photo: JPEG, PNG or WEBP. */
    public PhotoView uploadPhoto(SubjectType subjectType, UUID personId, IncomingFile file, Actor actor) {
        if (defaults(subjectType, personId).isEmpty()) {
            throw ApiException.notFound(subjectType == SubjectType.STUDENT ? "student" : "staff member");
        }
        if (!file.type().name().matches("JPEG|PNG|WEBP")) {
            throw ApiException.badRequest("Upload a JPEG, PNG or WEBP photo.", "file");
        }
        FileOwner owner = owner(subjectType, personId);
        files.deleteAll(owner);
        StoredFileInfo stored = files.put(owner, file, actor.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subjectType", subjectType.name());
        details.put("subjectId", personId);
        details.put("fileName", stored.name());
        audit.record(actor, "id_card.photo_uploaded", "id_card_photo", personId, details);
        return new PhotoView(stored.id(), stored.name());
    }

    static FileOwner owner(SubjectType subjectType, UUID personId) {
        return new FileOwner(MODULE, subjectType == SubjectType.STUDENT ? PHOTO_STUDENT : PHOTO_STAFF, personId);
    }

    // ------------------------------------------------------------------ building

    /** What the records say about a person: the blood group and the number to call. Empty for unknown people. */
    record Defaults(String bloodGroup, String emergencyPhone) {
    }

    private Optional<Defaults> defaults(SubjectType type, UUID id) {
        if (type == SubjectType.STUDENT) {
            return subjects.student(id).map(s -> new Defaults(s.bloodGroup(), guardianPhone(s)));
        }
        return subjects.staffPerson(id).map(p -> new Defaults(null, staffPhone(p)));
    }

    private String guardianPhone(StudentDetail s) {
        return s.guardians().stream().filter(g -> g.primary() && g.phone() != null).map(g -> g.phone()).findFirst()
                .or(() -> s.guardians().stream().map(g -> g.phone()).filter(p -> p != null).findFirst())
                .orElse(null);
    }

    private String staffPhone(PayrollPerson p) {
        try {
            StaffDetail d = staff.detail(p.userId());
            if (d.profile() == null) {
                return null;
            }
            return d.profile().emergencyContactMobile() != null ? d.profile().emergencyContactMobile()
                    : d.profile().mobile();
        } catch (RuntimeException e) {
            log.debug("No staff profile for {} ({})", p.userId(), e.getClass().getSimpleName());
            return null;
        }
    }

    private Optional<IdCard> active(SubjectType type, UUID id) {
        return type == SubjectType.STUDENT ? cards.findByStudentIdAndStatus(id, CardStatus.ACTIVE)
                : cards.findByStaffUserIdAndStatus(id, CardStatus.ACTIVE);
    }

    private IdCard newCard(SubjectType type, UUID id, int issueNo, String bloodGroup, String phone,
            LocalDate validUntil, UUID replacesId, String reason, Long fee, String note, Actor actor) {
        LocalDate today = Serials.today();
        LocalDate until = validUntil != null ? validUntil : today.plusMonths(setup.cardValidMonths());
        if (!until.isAfter(today)) {
            throw ApiException.badRequest("The card must be valid beyond today.", "validUntil");
        }
        IdCard card = new IdCard(type, type == SubjectType.STUDENT ? id : null,
                type == SubjectType.STAFF ? id : null, setup.nextCardNo(today), issueNo,
                CertificateSetupService.blankToNull(bloodGroup), digits(phone), today, until, replacesId, reason,
                fee, note, actor, Instant.now());
        return cards.saveAndFlush(card);
    }

    private static String pick(String given, String fallback) {
        String g = CertificateSetupService.blankToNull(given);
        return g != null ? g : fallback;
    }

    private static String digits(String phone) {
        if (phone == null) {
            return null;
        }
        String d = phone.replaceAll("[^0-9]", "");
        if (d.length() > 10 && (d.startsWith("91") || d.startsWith("0"))) {
            d = d.substring(d.length() - 10);
        }
        return d.isEmpty() ? null : d;
    }

    private IdCard find(UUID id) {
        return cards.findById(id).orElseThrow(() -> ApiException.notFound("ID card"));
    }

    private static Map<String, Object> details(IdCard c) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("cardNo", c.getCardNo());
        d.put("issueNo", c.getIssueNo());
        d.put("subjectType", c.getSubjectType().name());
        d.put("subjectId", c.subjectId());
        d.put("validUntil", c.getValidUntil().toString());
        return d;
    }

    List<IdCardView> views(List<IdCard> items) {
        List<Ref> refs = items.stream().map(c -> new Ref(c.getSubjectType(), c.subjectId())).distinct().toList();
        Map<Ref, Subject> people = subjects.describe(refs);
        Map<UUID, UUID> photos = photos(items);
        return items.stream().map(c -> new IdCardView(c.getId(),
                people.get(new Ref(c.getSubjectType(), c.subjectId())), c.getCardNo(), c.getIssueNo(),
                c.getStatus(), c.getBloodGroup(), c.getEmergencyPhone(), c.getValidFrom(), c.getValidUntil(),
                photos.get(c.subjectId()), c.getReplacesId(), c.getReissueReason(), c.getLostFeePaise(),
                c.getFeeNote(), c.getIssuedByName(), c.getCreatedAt(), c.getCancelledAt())).toList();
    }

    private Map<UUID, UUID> photos(Collection<IdCard> items) {
        Map<UUID, UUID> out = new HashMap<>();
        for (SubjectType type : SubjectType.values()) {
            List<UUID> ids = items.stream().filter(c -> c.getSubjectType() == type).map(IdCard::subjectId).distinct()
                    .toList();
            if (!ids.isEmpty()) {
                files.list(MODULE, type == SubjectType.STUDENT ? PHOTO_STUDENT : PHOTO_STAFF, ids)
                        .forEach((id, list) -> {
                            if (!list.isEmpty()) {
                                out.put(id, list.getLast().id());
                            }
                        });
            }
        }
        return out;
    }

    /** The label of a section, for reports. */
    Optional<SectionInfo> section(UUID id) {
        return subjects.section(id);
    }
}
