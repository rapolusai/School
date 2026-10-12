package com.akshara.onboarding;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.audit.AuditService.Actor;
import com.akshara.certificates.CertificateForms.CardBatchForm;
import com.akshara.certificates.CertificateForms.PrepareForm;
import com.akshara.certificates.CertificateForms.SettingsForm;
import com.akshara.certificates.CertificateForms.TemplateForm;
import com.akshara.certificates.CertificateService;
import com.akshara.certificates.CertificateSetupService;
import com.akshara.certificates.CertificateType;
import com.akshara.certificates.CertificateViews.CertificateDetail;
import com.akshara.certificates.CertificateViews.TemplateView;
import com.akshara.certificates.IdCardService;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The demo school's certificates: the printed header and the principal as signatory, the bonafide template saved
 * with the school's own wording, two bonafide certificates issued (Arjun Sharma for a bank account, Sneha Iyengar
 * for a scholarship), a transfer certificate for Laila Ahmed, who left (signing it marks her as transferred), and
 * ID cards for everyone in Class 5 A. The front office prepares; the principal signs.
 */
@Component
class DemoCertificatesData {

    private static final Logger log = LoggerFactory.getLogger(DemoCertificatesData.class);

    /** The student whose transfer certificate the demo issues; she is marked as transferred by it. */
    static final String LEFT_STUDENT = "Laila Ahmed";
    static final String CARDS_SECTION = "Class 5 A";

    private final CertificateSetupService setup;
    private final CertificateService certificates;
    private final IdCardService cards;
    private final StudentService students;
    private final AcademicsDirectory academics;
    private final TransactionTemplate tx;

    DemoCertificatesData(CertificateSetupService setup, CertificateService certificates, IdCardService cards,
            StudentService students, AcademicsDirectory academics, TransactionTemplate tx) {
        this.setup = setup;
        this.certificates = certificates;
        this.cards = cards;
        this.students = students;
        this.academics = academics;
        this.tx = tx;
    }

    /** Returns how many certificates were issued. {@code userIds} maps the demo people's emails to their ids. */
    int seed(UUID tenantId, Map<String, UUID> userIds) {
        Actor office = new Actor(userIds.get("frontoffice" + DemoDataSeeder.DEMO_DOMAIN), "Suresh Rao");
        Actor principal = new Actor(userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN), "Lakshmi Iyer");
        step(tenantId, "settings", () -> setup.updateSettings(new SettingsForm("Akshara Demo School",
                "12 MG Road, Hyderabad 500001", "CBSE 3630xxx", "ADS", "Lakshmi Iyer", "Principal", true, 12,
                5000L), office));
        step(tenantId, "the bonafide wording", () -> {
            TemplateView t = setup.template(CertificateType.BONAFIDE);
            return setup.updateTemplate(CertificateType.BONAFIDE, new TemplateForm(t.title(),
                    t.body() + "\n\nThis certificate is issued at the request of the parent for {purpose}.",
                    t.serialPrefix()), office);
        });
        Map<String, UUID> pupils = TenantContext.runAs(tenantId, () -> tx.execute(status -> pupils()));
        int issued = 0;
        issued += issue(tenantId, CertificateType.BONAFIDE, pupils.get("Arjun Sharma"), "opening a bank account",
                office, principal);
        issued += issue(tenantId, CertificateType.BONAFIDE, pupils.get("Sneha Iyengar"), "a scholarship application",
                office, principal);
        issued += issue(tenantId, CertificateType.TRANSFER, pupils.get(LEFT_STUDENT),
                "Parent transferred to Bengaluru", office, principal);
        step(tenantId, "ID cards for " + CARDS_SECTION, () -> {
            SectionInfo section = academics.sections().stream().filter(s -> s.label().equals(CARDS_SECTION))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No " + CARDS_SECTION));
            return cards.issueBatch(new CardBatchForm(section.id(), false, LocalDate.of(2027, 3, 31)), office);
        });
        return issued;
    }

    private int issue(UUID tenantId, CertificateType type, UUID studentId, String purpose, Actor office,
            Actor principal) {
        if (studentId == null) {
            return 0;
        }
        CertificateDetail prepared = step(tenantId, type + " for " + studentId, () -> certificates.prepare(
                new PrepareForm(type, studentId, null, purpose, null, null,
                        type == CertificateType.TRANSFER ? Map.of("reason", purpose) : null), office));
        if (prepared == null) {
            return 0;
        }
        return step(tenantId, "signing " + type, () -> certificates.sign(prepared.id(), principal)) == null ? 0 : 1;
    }

    private Map<String, UUID> pupils() {
        Map<String, UUID> ids = new HashMap<>();
        for (String name : List.of("Arjun Sharma", "Sneha Iyengar", LEFT_STUDENT)) {
            List<StudentRow> found = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, name, 0,
                    5)).items();
            found.stream().filter(r -> r.fullName().equals(name)).findFirst().ifPresent(r -> ids.put(name, r.id()));
        }
        return ids;
    }

    private <T> T step(UUID tenantId, String what, Supplier<T> work) {
        try {
            return TenantContext.runAs(tenantId, () -> tx.execute(status -> work.get()));
        } catch (RuntimeException e) {
            log.info("Skipped demo certificates step '{}' ({})", what, e.getMessage());
            return null;
        }
    }
}
