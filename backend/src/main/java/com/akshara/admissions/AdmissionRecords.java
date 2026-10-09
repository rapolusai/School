package com.akshara.admissions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.admissions.AdmissionsService.ApplicationDetail;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.TenantContext;

/**
 * The admissions record behind a student, for data protection requests: read it for a parent's data export, and
 * anonymise it when the student's data is erased. Narrow on purpose; the privacy module calls it after its own checks.
 */
@Service
@Transactional
public class AdmissionRecords {

    private final ApplicationRepository applications;
    private final ApplicationGuardianRepository guardians;
    private final AdmissionsService admissions;
    private final AuditService audit;
    private final EntityManager entityManager;

    AdmissionRecords(ApplicationRepository applications, ApplicationGuardianRepository guardians,
            AdmissionsService admissions, AuditService audit, EntityManager entityManager) {
        this.applications = applications;
        this.guardians = guardians;
        this.admissions = admissions;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    /** The application the student was admitted from, if they came through admissions. */
    @Transactional(readOnly = true)
    public Optional<ApplicationDetail> forStudent(UUID studentId) {
        TenantContext.require();
        return applications.findByStudentId(studentId).map(a -> admissions.detail(a.getId()));
    }

    /**
     * Anonymises the application the student was admitted from: the child's name and date of birth like the student
     * record, the previous school and message cleared, the family's contacts deleted, and the free-text notes of tests,
     * interviews and the timeline cleared. Stages, dates, the timeline's structured details (stages, the application
     * fee and its payment reference, offer dates), the fee record and the consent stamp stay. Returns false when the
     * student did not come through admissions.
     */
    public boolean anonymiseForStudent(UUID studentId, String placeholderName, Actor actor) {
        TenantContext.require();
        Application application = applications.findByStudentId(studentId).orElse(null);
        if (application == null) {
            return false;
        }
        UUID id = application.getId();
        application.anonymise(placeholderName);
        applications.flush();
        guardians.deleteByApplicationId(id);
        int slots = entityManager.createNativeQuery("update admissions.assessment_slot set outcome_notes = null, "
                + "updated_at = now() where application_id = :id and outcome_notes is not null")
                .setParameter("id", id)
                .executeUpdate();
        // Timeline entries are a record and never change in normal use; erasure clears only their free text. The
        // details stay: they hold stages, dates and the application fee, which the school keeps as a financial record.
        int entries = entityManager.createNativeQuery("update admissions.timeline_entry set note = null "
                + "where application_id = :id and note is not null")
                .setParameter("id", id)
                .executeUpdate();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentId", studentId.toString());
        details.put("slotNotesCleared", slots);
        details.put("timelineEntriesCleared", entries);
        audit.record(actor, "application.anonymised", "application", id, details);
        return true;
    }
}
