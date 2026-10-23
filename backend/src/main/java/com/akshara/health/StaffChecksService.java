package com.akshara.health;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.health.HealthForms.TrainingForm;
import com.akshara.health.HealthForms.VerificationForm;
import com.akshara.health.HealthPeople.Staff;
import com.akshara.health.HealthText.Due;
import com.akshara.health.HealthViews.StaffChecks;
import com.akshara.health.HealthViews.TrainingView;
import com.akshara.health.HealthViews.VerificationView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Staff police verification and background checks (one row per person, reference number masked) and training
 * records (POCSO, first aid) with their expiry, and the list of who is due or missing. Every change is audited.
 */
@Service
@Transactional
public class StaffChecksService {

    private final StaffVerificationRepository verifications;
    private final StaffTrainingRepository trainings;
    private final HealthPeople people;
    private final AuditService audit;

    StaffChecksService(StaffVerificationRepository verifications, StaffTrainingRepository trainings,
            HealthPeople people, AuditService audit) {
        this.verifications = verifications;
        this.trainings = trainings;
        this.people = people;
        this.audit = audit;
    }

    /** Every active staff member with their verification and trainings, those due first. */
    @Transactional(readOnly = true)
    public StaffChecks list() {
        TenantContext.require();
        LocalDate today = HealthText.today();
        List<VerificationView> rows = rows(today);
        int due = (int) rows.stream().filter(r -> r.due() == Due.OVERDUE || r.due() == Due.DUE_SOON).count();
        int missing = (int) rows.stream().filter(r -> r.due() == Due.MISSING).count();
        int trainingsDue = (int) rows.stream().filter(r -> r.pocsoTrainingDue() || r.firstAidTrainingDue()).count();
        return new StaffChecks(today, rows, due, missing, trainingsDue);
    }

    /** Only the rows that need attention: a verification due, missing or not cleared, or a training to renew. */
    @Transactional(readOnly = true)
    public List<VerificationView> due() {
        return list().rows().stream().filter(r -> r.due() != Due.OK || r.pocsoTrainingDue()
                || r.firstAidTrainingDue()).toList();
    }

    public VerificationView saveVerification(UUID userId, VerificationForm form, Actor actor) {
        TenantContext.require();
        Staff staff = people.requireStaff(userId);
        if (form.status() == VerificationStatus.CLEARED && form.clearedOn() == null) {
            throw ApiException.badRequest("Give the date it was cleared.", "clearedOn");
        }
        LocalDate today = HealthText.today();
        if (form.requestedOn() != null && (form.requestedOn().isAfter(today)
                || form.requestedOn().isBefore(HealthText.EARLIEST))) {
            throw ApiException.badRequest("Check the request date; it cannot be in the future.", "requestedOn");
        }
        if (form.clearedOn() != null && (form.clearedOn().isAfter(today)
                || form.clearedOn().isBefore(HealthText.EARLIEST))) {
            throw ApiException.badRequest("Check the clearance date; it cannot be in the future.", "clearedOn");
        }
        if (form.requestedOn() != null && form.clearedOn() != null && form.clearedOn().isBefore(form.requestedOn())) {
            throw ApiException.badRequest("Cleared before it was requested?", "clearedOn");
        }
        if (form.expiresOn() != null && form.clearedOn() != null && !form.expiresOn().isAfter(form.clearedOn())) {
            throw ApiException.badRequest("The expiry is after the clearance.", "expiresOn");
        }
        StaffVerification v = verifications.findByUserId(userId)
                .orElseGet(() -> verifications.save(new StaffVerification(userId, Instant.now())));
        v.apply(new StaffVerification.Details(form.status(), form.requestedOn(), form.clearedOn(),
                HealthText.clean(form.referenceNo()), form.expiresOn(), HealthText.clean(form.note())), actor.name());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("staff", staff.name());
        details.put("status", v.getStatus().name());
        details.put("referenceNo", HealthText.maskNumber(v.getReferenceNo()));
        audit.record(actor, "staff_verification.updated", "staff_verification", v.getId(), details);
        verifications.flush();
        return rows(HealthText.today()).stream().filter(r -> r.userId().equals(userId)).findFirst().orElseThrow();
    }

    public TrainingView addTraining(TrainingForm form, Actor actor) {
        TenantContext.require();
        Staff staff = people.requireStaff(form.userId(), "userId");
        if (form.trainedOn().isAfter(HealthText.today())) {
            throw ApiException.badRequest("The training cannot be in the future.", "trainedOn");
        }
        if (form.expiresOn() != null && !form.expiresOn().isAfter(form.trainedOn())) {
            throw ApiException.badRequest("The expiry is after the training.", "expiresOn");
        }
        StaffTraining t = trainings.save(new StaffTraining(staff.userId(), form.kind(), form.trainedOn(),
                form.expiresOn(), HealthText.clean(form.provider()), Instant.now()));
        audit.record(actor, "staff_training.recorded", "staff_training", t.getId(), Map.of("staff", staff.name(),
                "kind", form.kind().name(), "trainedOn", form.trainedOn().toString()));
        trainings.flush();
        return view(t, staff.name(), HealthText.today());
    }

    public void removeTraining(UUID id, Actor actor) {
        TenantContext.require();
        StaffTraining t = trainings.findById(id).orElseThrow(() -> ApiException.notFound("Training"));
        trainings.delete(t);
        audit.record(actor, "staff_training.removed", "staff_training", id, Map.of("kind", t.getKind().name()));
        trainings.flush();
    }

    /* ------------------------------------------------------------------ helpers */

    private List<VerificationView> rows(LocalDate today) {
        Map<UUID, StaffVerification> byUser = verifications.findAll().stream()
                .collect(Collectors.toMap(StaffVerification::getUserId, Function.identity()));
        Map<UUID, List<StaffTraining>> trainingByUser = new HashMap<>();
        trainings.findAllByOrderByTrainedOnDesc()
                .forEach(t -> trainingByUser.computeIfAbsent(t.getUserId(), k -> new ArrayList<>()).add(t));
        List<VerificationView> rows = new ArrayList<>();
        for (Staff s : people.activeStaff()) {
            StaffVerification v = byUser.get(s.userId());
            List<TrainingView> tv = trainingByUser.getOrDefault(s.userId(), List.of()).stream()
                    .map(t -> view(t, s.name(), today)).toList();
            Due due = HealthText.verificationState(v == null ? null : v.getStatus(),
                    v == null ? null : v.getExpiresOn(), today);
            rows.add(new VerificationView(s.userId(), s.name(), s.designation(), s.active(),
                    v == null ? null : v.getStatus(), v == null ? null : v.getRequestedOn(),
                    v == null ? null : v.getClearedOn(), v == null ? null : HealthText.maskNumber(v.getReferenceNo()),
                    v == null ? null : v.getExpiresOn(), v == null ? null : v.getNote(),
                    v == null ? null : v.getUpdatedByName(), due, tv, trainingDue(tv, TrainingKind.POCSO, today),
                    trainingDue(tv, TrainingKind.FIRST_AID, today)));
        }
        rows.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        return rows;
    }

    /** Missing and overdue first, then due soon, then fine. */
    private static int rank(VerificationView r) {
        return switch (r.due()) {
            case MISSING -> 0;
            case OVERDUE -> 1;
            case DUE_SOON -> 2;
            case OK -> r.pocsoTrainingDue() || r.firstAidTrainingDue() ? 3 : 4;
        };
    }

    /** A training of the kind is due when there is none, or the latest one needs renewal. */
    static boolean trainingDue(List<TrainingView> rows, TrainingKind kind, LocalDate today) {
        return rows.stream().filter(t -> t.kind() == kind).findFirst()
                .map(t -> HealthText.trainingNeedsRenewal(t.expiresOn(), today)).orElse(true);
    }

    private static TrainingView view(StaffTraining t, String name, LocalDate today) {
        return new TrainingView(t.getId(), t.getUserId(), name, t.getKind(), t.getTrainedOn(), t.getExpiresOn(),
                t.getProvider(), HealthText.trainingNeedsRenewal(t.getExpiresOn(), today));
    }
}
