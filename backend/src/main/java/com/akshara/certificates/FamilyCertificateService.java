package com.akshara.certificates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService.Actor;
import com.akshara.certificates.CertificateForms.FamilyRequestForm;
import com.akshara.certificates.CertificateViews.CertificateDetail;
import com.akshara.certificates.CertificateViews.CertificateRow;
import com.akshara.certificates.CertificateViews.FamilyChild;
import com.akshara.certificates.CertificateViews.IdCardView;
import com.akshara.certificates.CertificateViews.MyCertificates;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService.ChildView;

/**
 * The family app's side: a parent (or a student) sees each child's certificates and ID card, asks for a bonafide
 * or fee certificate, and opens an issued one to print. Never another family's child: those answer 404.
 */
@Service
@Transactional
public class FamilyCertificateService {

    private final CertificateService certificates;
    private final IdCardService cards;
    private final CertificateSubjects subjects;

    FamilyCertificateService(CertificateService certificates, IdCardService cards, CertificateSubjects subjects) {
        this.certificates = certificates;
        this.cards = cards;
        this.subjects = subjects;
    }

    @Transactional(readOnly = true)
    public MyCertificates mine(UUID userId) {
        TenantContext.require();
        Map<UUID, ChildView> children = children(userId);
        List<CertificateRow> rows = certificates.ofStudents(children.keySet());
        List<FamilyChild> out = new ArrayList<>();
        for (ChildView c : children.values()) {
            List<CertificateRow> own = rows.stream().filter(r -> c.id().equals(r.subject().id())).toList();
            out.add(new FamilyChild(c.id(), c.fullName(), CertificateSubjects.label(c.className(), c.sectionName()),
                    own, cards.activeOfStudent(c.id()).map(this::redacted).orElse(null)));
        }
        return new MyCertificates(out, Arrays.stream(CertificateType.values())
                .filter(CertificateType::familyRequestable).toList());
    }

    public CertificateDetail request(FamilyRequestForm form, UUID userId, Actor actor) {
        TenantContext.require();
        if (!children(userId).containsKey(form.studentId())) {
            throw ApiException.badRequest("Choose one of your children.", "studentId");
        }
        return certificates.request(form.type(), form.studentId(), form.purpose(), form.from(), form.to(), actor);
    }

    /** One of the family's certificates, in full (issued ones carry the printable wording). */
    @Transactional(readOnly = true)
    public CertificateDetail detail(UUID id, UUID userId) {
        TenantContext.require();
        CertificateDetail d = certificates.detail(id);
        if (d.subject().type() != SubjectType.STUDENT || !children(userId).containsKey(d.subject().id())) {
            throw ApiException.notFound("certificate");
        }
        return d;
    }

    /** The family's children's ID cards in use, with the emergency number masked. */
    @Transactional(readOnly = true)
    public List<IdCardView> idCards(UUID userId) {
        TenantContext.require();
        return children(userId).keySet().stream().flatMap(id -> cards.activeOfStudent(id).stream())
                .map(this::redacted).toList();
    }

    private IdCardView redacted(IdCardView v) {
        return new IdCardView(v.id(), v.subject(), v.cardNo(), v.issueNo(), v.status(), v.bloodGroup(),
                Serials.maskPhone(v.emergencyPhone()), v.validFrom(), v.validUntil(), v.photoFileId(), null, null,
                null, null, null, v.createdAt(), v.cancelledAt());
    }

    private Map<UUID, ChildView> children(UUID userId) {
        Map<UUID, ChildView> children = new LinkedHashMap<>();
        subjects.childrenOf(userId).forEach(c -> children.putIfAbsent(c.id(), c));
        subjects.studentOf(userId).ifPresent(c -> children.putIfAbsent(c.id(), c));
        return children;
    }
}
