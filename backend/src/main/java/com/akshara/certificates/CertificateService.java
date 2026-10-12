package com.akshara.certificates;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.certificates.CertificateForms.EditForm;
import com.akshara.certificates.CertificateForms.PrepareForm;
import com.akshara.certificates.CertificateSubjects.Ref;
import com.akshara.certificates.CertificateViews.CertificateDetail;
import com.akshara.certificates.CertificateViews.CertificatePage;
import com.akshara.certificates.CertificateViews.CertificateRow;
import com.akshara.certificates.CertificateViews.Subject;
import com.akshara.certificates.CertificateViews.Verification;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffPayrollInputs.PayrollPerson;
import com.akshara.students.StudentForms.Leave;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentDetail;
import com.akshara.students.StudentStatus;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The issue flow: a certificate is requested (by the office, or by a parent through the family app), prepared with
 * prefilled, editable placeholder values, signed by a signatory (which gives it its serial number, freezes its
 * wording and makes it verifiable), printed from the browser, and corrected by cancelling it and making the next
 * version. Signing a transfer certificate marks the student as left through the students module.
 */
@Service
@Transactional
public class CertificateService {

    public static final int MAX_PAGE_SIZE = 200;
    static final String VERIFY_PATH = "/verify/";
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {
    };

    private final CertificateRepository certificates;
    private final CertificateSetupService setup;
    private final CertificateSubjects subjects;
    private final StudentService students;
    private final AuditService audit;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;

    CertificateService(CertificateRepository certificates, CertificateSetupService setup,
            CertificateSubjects subjects, StudentService students, AuditService audit, JsonMapper json,
            ApplicationEventPublisher events, EntityManager entityManager) {
        this.certificates = certificates;
        this.setup = setup;
        this.subjects = subjects;
        this.students = students;
        this.audit = audit;
        this.json = json;
        this.events = events;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ reading

    /** The register, newest request first. Every filter is optional. */
    @Transactional(readOnly = true)
    public CertificatePage list(CertificateStatus status, CertificateType type, UUID studentId, UUID staffUserId,
            LocalDate issuedFrom, LocalDate issuedTo, int page, int size) {
        TenantContext.require();
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (status != null) {
            where.append(" and c.status = :status");
            params.put("status", status);
        }
        if (type != null) {
            where.append(" and c.type = :type");
            params.put("type", type);
        }
        if (studentId != null) {
            where.append(" and c.studentId = :studentId");
            params.put("studentId", studentId);
        }
        if (staffUserId != null) {
            where.append(" and c.staffUserId = :staffUserId");
            params.put("staffUserId", staffUserId);
        }
        if (issuedFrom != null) {
            where.append(" and c.issuedOn >= :issuedFrom");
            params.put("issuedFrom", issuedFrom);
        }
        if (issuedTo != null) {
            where.append(" and c.issuedOn <= :issuedTo");
            params.put("issuedTo", issuedTo);
        }
        TypedQuery<Long> count = entityManager.createQuery("select count(c) from Certificate c" + where, Long.class);
        TypedQuery<Certificate> query = entityManager.createQuery("select c from Certificate c" + where
                + " order by c.requestedAt desc, c.id", Certificate.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            query.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<Certificate> items = query.setFirstResult(page * pageSize).setMaxResults(pageSize).getResultList();
        return new CertificatePage(rows(items), page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public CertificateDetail detail(UUID id) {
        TenantContext.require();
        return detail(find(id));
    }

    /** All the certificates of some students, newest first (for the family app and reports). */
    @Transactional(readOnly = true)
    public List<CertificateRow> ofStudents(java.util.Collection<UUID> studentIds) {
        TenantContext.require();
        return studentIds.isEmpty() ? List.of() : rows(certificates.findByStudentIdInOrderByRequestedAtDesc(
                studentIds));
    }

    /** What the public verification page shows for a token: nothing, or the limited view. */
    @Transactional(readOnly = true)
    public Optional<Verification> verification(String token) {
        TenantContext.require();
        return certificates.findByVerifyToken(token).map(c -> new Verification(setup.header().name(), c.getType(),
                c.getTitle(), c.getSerialNo(), subjects.subject(c.getSubjectType(), c.subjectId()).firstName(),
                c.getIssuedOn(), c.getStatus() == CertificateStatus.ISSUED, c.getStatus()));
    }

    // ------------------------------------------------------------------ the flow

    /** The office prepares a certificate: prefilled from the records, then edited and signed. */
    public CertificateDetail prepare(PrepareForm form, Actor actor) {
        Certificate c = create(form.type(), form.studentId(), form.staffUserId(), form.purpose(), form.from(),
                form.to(), form.fields(), CertificateStatus.PREPARED, RequestedVia.OFFICE, actor, 1, null);
        audit.record(actor, "certificate.prepared", "certificate", c.getId(), details(c));
        return detail(c);
    }

    /** A parent's request from the family app: prefilled and waiting for the office. */
    CertificateDetail request(CertificateType type, UUID studentId, String purpose, LocalDate from, LocalDate to,
            Actor actor) {
        if (!type.familyRequestable()) {
            throw ApiException.badRequest("Parents can request a bonafide or fee certificate only.", "type");
        }
        Certificate c = create(type, studentId, null, purpose, from, to, null, CertificateStatus.REQUESTED,
                RequestedVia.FAMILY, actor, 1, null);
        audit.record(actor, "certificate.requested", "certificate", c.getId(), details(c));
        return detail(c);
    }

    /** Edits an unsigned certificate; a request becomes prepared. */
    public CertificateDetail edit(UUID id, EditForm form, Actor actor) {
        Certificate c = lock(id);
        if (!c.isEditable()) {
            throw ApiException.conflict("Only a certificate that is not yet signed can be edited.", "status");
        }
        Map<String, String> fields = clean(form.fields());
        c.edit(form.title().trim(), CertificateSetupService.blankToNull(form.purpose()), write(fields),
                dues(c.getType(), c.getStudentId()));
        if (c.getStatus() == CertificateStatus.REQUESTED) {
            c.prepared(actor, Instant.now());
        }
        certificates.saveAndFlush(c);
        audit.record(actor, "certificate.updated", "certificate", c.getId(), details(c));
        return detail(c);
    }

    /** The signatory signs: the certificate gets its serial number, frozen wording and verification token. */
    public CertificateDetail sign(UUID id, Actor actor) {
        Certificate c = lock(id);
        if (c.getStatus() != CertificateStatus.PREPARED) {
            throw ApiException.conflict(c.getStatus() == CertificateStatus.REQUESTED
                    ? "Prepare the certificate before signing it."
                    : "This certificate is already " + c.getStatus().name().toLowerCase() + ".", "status");
        }
        LocalDate today = Serials.today();
        Instant now = Instant.now();
        String serial = setup.nextSerial(c.getType(), today);
        Map<String, String> fields = new LinkedHashMap<>(read(c.getFields()));
        fields.put("school", setup.header().name());
        fields.put("date", today.toString());
        fields.put("serialNo", serial);
        String body = Placeholders.render(setup.wording(c.getType()).body(), fields);
        c.signed(serial, setup.yearKey(today), body, Serials.newToken(), actor, now, today);
        certificates.saveAndFlush(c);
        Map<String, Object> details = details(c);
        if (c.getType() == CertificateType.TRANSFER && c.getStudentId() != null) {
            LocalDate leftOn = parseDate(fields.get("dateOfLeaving")).orElse(today);
            String reason = fields.getOrDefault("reason", null);
            StudentDetail s = subjects.student(c.getStudentId()).orElse(null);
            if (s != null && s.status() == StudentStatus.ACTIVE) {
                students.leave(c.getStudentId(), new Leave(StudentStatus.TRANSFERRED, leftOn,
                        reason == null || reason.isBlank() ? "Transfer certificate " + serial : reason));
                details.put("studentMarkedLeft", true);
            }
            events.publishEvent(new TransferCertificateIssued(TenantContext.require(), c.getId(), c.getStudentId(),
                    serial, today, leftOn));
        }
        audit.record(actor, "certificate.signed", "certificate", c.getId(), details);
        return detail(c);
    }

    /** Cancels a certificate (a request, a prepared one or an issued one) with a reason. */
    public CertificateDetail cancel(UUID id, String reason, Actor actor) {
        Certificate c = lock(id);
        if (c.getStatus() == CertificateStatus.CANCELLED) {
            throw ApiException.conflict("This certificate is already cancelled.", "status");
        }
        c.cancelled(reason.trim(), actor, Instant.now());
        certificates.saveAndFlush(c);
        Map<String, Object> details = details(c);
        details.put("reason", c.getCancelReason());
        audit.record(actor, "certificate.cancelled", "certificate", c.getId(), details);
        return detail(c);
    }

    /** Corrects an issued certificate: cancels it and makes the next version, prepared with the same values. */
    public CertificateDetail correct(UUID id, String reason, Actor actor) {
        Certificate old = lock(id);
        if (old.getStatus() != CertificateStatus.ISSUED) {
            throw ApiException.conflict("Only an issued certificate can be corrected.", "status");
        }
        if (!certificates.findBySupersedesId(old.getId()).isEmpty()) {
            throw ApiException.conflict("This certificate was already corrected.", "status");
        }
        Instant now = Instant.now();
        old.cancelled("Corrected: " + reason.trim(), actor, now);
        certificates.saveAndFlush(old);
        Certificate next = new Certificate(old.getType(), CertificateStatus.PREPARED, old.getSubjectType(),
                old.getStudentId(), old.getStaffUserId(), old.getTitle(), old.getPurpose(), old.getFields(),
                old.getRequestedVia(), actor, old.getVersionNo() + 1, old.getId(), now);
        next.edit(old.getTitle(), old.getPurpose(), old.getFields(), old.getDuesPaise());
        certificates.saveAndFlush(next);
        Map<String, Object> details = details(next);
        details.put("reason", reason.trim());
        details.put("supersedes", old.getSerialNo());
        audit.record(actor, "certificate.corrected", "certificate", next.getId(), details);
        return detail(next);
    }

    // ------------------------------------------------------------------ building

    private Certificate create(CertificateType type, UUID studentId, UUID staffUserId, String purpose,
            LocalDate from, LocalDate to, Map<String, String> overrides, CertificateStatus status,
            RequestedVia via, Actor actor, int versionNo, UUID supersedesId) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("The period must end after it starts.", "to");
        }
        LocalDate today = Serials.today();
        String cleanPurpose = CertificateSetupService.blankToNull(purpose);
        Map<String, String> fields;
        UUID sid = null;
        UUID uid = null;
        Long dues = null;
        if (type.isStaff()) {
            if (staffUserId == null) {
                throw ApiException.badRequest("Choose a member of staff.", "staffUserId");
            }
            PayrollPerson p = subjects.staffPerson(staffUserId)
                    .orElseThrow(() -> ApiException.badRequest("No such member of staff.", "staffUserId"));
            fields = subjects.prefill(p, type, cleanPurpose, today);
            uid = p.userId();
        } else {
            if (studentId == null) {
                throw ApiException.badRequest("Choose a student.", "studentId");
            }
            StudentDetail s = subjects.student(studentId)
                    .orElseThrow(() -> ApiException.badRequest("No such student.", "studentId"));
            fields = subjects.prefill(s, type, cleanPurpose, from, to, today);
            sid = s.id();
            dues = dues(type, sid);
        }
        if (overrides != null) {
            clean(overrides).forEach((k, v) -> {
                if (v != null) {
                    fields.put(k, v);
                }
            });
        }
        Certificate c = new Certificate(type, status, type.isStaff() ? SubjectType.STAFF : SubjectType.STUDENT,
                sid, uid, setup.wording(type).title(), cleanPurpose, write(fields), via, actor, versionNo,
                supersedesId, Instant.now());
        c.edit(c.getTitle(), c.getPurpose(), c.getFields(), dues);
        return certificates.saveAndFlush(c);
    }

    /** Fees still due, noted on a transfer certificate when the school checks them (a warning, never a block). */
    private Long dues(CertificateType type, UUID studentId) {
        if (type != CertificateType.TRANSFER || studentId == null || !setup.tcDuesCheck()) {
            return null;
        }
        return subjects.duesPaise(studentId).orElse(null);
    }

    private Certificate find(UUID id) {
        return certificates.findById(id).orElseThrow(() -> ApiException.notFound("certificate"));
    }

    private Certificate lock(UUID id) {
        return certificates.lockById(id).orElseThrow(() -> ApiException.notFound("certificate"));
    }

    private static Map<String, String> clean(Map<String, String> fields) {
        Map<String, String> out = new LinkedHashMap<>();
        if (fields == null) {
            return out;
        }
        fields.forEach((k, v) -> {
            String key = k == null ? "" : k.trim();
            if (!key.matches("^[A-Za-z][A-Za-z0-9]{0,39}$")) {
                throw ApiException.badRequest("Placeholder names are letters and digits only.", "fields");
            }
            out.put(key, v == null ? null : v.strip());
        });
        return out;
    }

    private String write(Map<String, String> fields) {
        return json.writeValueAsString(fields);
    }

    Map<String, String> read(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        return json.readValue(fieldsJson, FIELDS);
    }

    static Optional<LocalDate> parseDate(String iso) {
        try {
            return iso == null || iso.isBlank() ? Optional.empty() : Optional.of(LocalDate.parse(iso.trim()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ views

    List<CertificateRow> rows(List<Certificate> items) {
        List<Ref> refs = items.stream().map(c -> new Ref(c.getSubjectType(), c.subjectId())).distinct().toList();
        Map<Ref, Subject> people = subjects.describe(refs);
        return items.stream().map(c -> row(c, people.get(new Ref(c.getSubjectType(), c.subjectId())))).toList();
    }

    static CertificateRow row(Certificate c, Subject subject) {
        return new CertificateRow(c.getId(), c.getType(), c.getTitle(), c.getStatus(), subject, c.getSerialNo(),
                c.getVersionNo(), c.getPurpose(), c.getRequestedVia(), c.getRequestedByName(), c.getRequestedAt(),
                c.getIssuedOn(), c.getSignedByName());
    }

    CertificateDetail detail(Certificate c) {
        Map<String, String> fields = read(c.getFields());
        String body = c.getBody();
        if (body == null) {
            Map<String, String> preview = new LinkedHashMap<>(fields);
            preview.put("school", setup.header().name());
            preview.put("date", Serials.today().toString());
            preview.put("serialNo", setup.template(c.getType()).nextSerial());
            body = Placeholders.render(setup.wording(c.getType()).body(), preview);
        }
        UUID supersededBy = certificates.findBySupersedesId(c.getId()).stream().map(Certificate::getId).findFirst()
                .orElse(null);
        List<String> placeholders = new ArrayList<>(c.getType().placeholders());
        for (String name : Placeholders.namesIn(setup.wording(c.getType()).body())) {
            if (!placeholders.contains(name)) {
                placeholders.add(name);
            }
        }
        return new CertificateDetail(c.getId(), c.getType(), c.getTitle(), c.getStatus(),
                subjects.subject(c.getSubjectType(), c.subjectId()), c.getSerialNo(), c.getAcademicYear(),
                c.getVersionNo(), c.getSupersedesId(), supersededBy, c.getPurpose(), fields, placeholders, body,
                setup.header(), setup.signatory(), c.getVerifyToken(),
                c.getVerifyToken() == null ? null : VERIFY_PATH + c.getVerifyToken(), c.getDuesPaise(),
                c.getRequestedVia(), c.getRequestedByName(), c.getRequestedAt(), c.getPreparedByName(),
                c.getPreparedAt(), c.getSignedByName(), c.getSignedAt(), c.getIssuedOn(), c.getCancelledByName(),
                c.getCancelledAt(), c.getCancelReason(), c.isEditable());
    }

    private static Map<String, Object> details(Certificate c) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", c.getType().name());
        d.put("status", c.getStatus().name());
        d.put("subjectType", c.getSubjectType().name());
        d.put("subjectId", c.subjectId());
        d.put("title", c.getTitle());
        d.put("versionNo", c.getVersionNo());
        if (c.getSerialNo() != null) {
            d.put("serialNo", c.getSerialNo());
        }
        return d;
    }

    /** The ids of certificates grouped by status, for the pending report's counts. */
    @Transactional(readOnly = true)
    public Map<CertificateStatus, Long> countByStatus() {
        TenantContext.require();
        return entityManager.createQuery("select c.status, count(c) from Certificate c group by c.status",
                Object[].class).getResultList().stream()
                .collect(Collectors.toMap(r -> (CertificateStatus) r[0], r -> (Long) r[1]));
    }
}
