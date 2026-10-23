package com.akshara.health;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.health.HealthAlerts.AllergyBadge;
import com.akshara.health.HealthForms.CloseForm;
import com.akshara.health.HealthForms.GivenForm;
import com.akshara.health.HealthForms.MedicineForm;
import com.akshara.health.HealthForms.VisitForm;
import com.akshara.health.HealthPeople.Pupil;
import com.akshara.health.HealthPeople.Staff;
import com.akshara.health.HealthViews.GivenView;
import com.akshara.health.HealthViews.InfirmaryDay;
import com.akshara.health.HealthViews.MedicineView;
import com.akshara.health.HealthViews.PersonRef;
import com.akshara.health.HealthViews.VisitView;
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
 * The infirmary log: visits by students and staff, the medicine given from the stock, and how each visit ended. A
 * student sent home or to hospital has their primary guardian told through the notifications outbox (simulated) and
 * a {@link StudentSentHome} event published for the attendance module. Every change is audited.
 */
@Service
@Transactional
public class InfirmaryService {

    static final String RELATED = "infirmary_visit";

    private final InfirmaryVisitRepository visits;
    private final MedicineGivenRepository given;
    private final MedicineRepository medicines;
    private final HealthPeople people;
    private final HealthAlerts alerts;
    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final TenantDirectory tenants;
    private final ApplicationEventPublisher events;
    private final AuditService audit;

    InfirmaryService(InfirmaryVisitRepository visits, MedicineGivenRepository given, MedicineRepository medicines,
            HealthPeople people, HealthAlerts alerts, NotificationQueue queue,
            NotificationSettingsService messageSettings, TenantDirectory tenants, ApplicationEventPublisher events,
            AuditService audit) {
        this.visits = visits;
        this.given = given;
        this.medicines = medicines;
        this.people = people;
        this.alerts = alerts;
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.tenants = tenants;
        this.events = events;
        this.audit = audit;
    }

    /* ------------------------------------------------------------------ visits */

    @Transactional(readOnly = true)
    public InfirmaryDay day(LocalDate date) {
        TenantContext.require();
        LocalDate day = date == null ? HealthText.today() : date;
        List<InfirmaryVisit> rows = new ArrayList<>(visits.between(HealthText.startOfDay(day),
                HealthText.startOfDay(day.plusDays(1))));
        // Visits still open from an earlier day stay on today's screen until they are closed.
        if (day.equals(HealthText.today())) {
            visits.open().stream().filter(v -> rows.stream().noneMatch(r -> r.getId().equals(v.getId())))
                    .forEach(rows::add);
        }
        int low = (int) medicines.findAll().stream().filter(m -> m.getQuantity() <= m.getReorderLevel()).count();
        return new InfirmaryDay(day, views(rows), (int) rows.stream().filter(InfirmaryVisit::isOpen).count(), low);
    }

    @Transactional(readOnly = true)
    public VisitView detail(UUID id) {
        TenantContext.require();
        return views(List.of(require(id))).getFirst();
    }

    @Transactional(readOnly = true)
    public List<PersonRef> search(String q) {
        TenantContext.require();
        List<PersonRef> result = new ArrayList<>(people.searchStudents(q, 15));
        result.addAll(people.searchStaff(q, 10));
        return result;
    }

    public VisitView add(VisitForm form, Actor actor) {
        TenantContext.require();
        Instant now = Instant.now();
        InfirmaryVisit visit = new InfirmaryVisit(details(form, now), actor.name(), now);
        visits.save(visit);
        List<MedicineGiven> taken = give(visit, form.medicines(), now);
        Map<String, Object> details = auditDetails(visit);
        if (!taken.isEmpty()) {
            details.put("medicines", taken.size());
        }
        audit.record(actor, "infirmary_visit.recorded", RELATED, visit.getId(), details);
        visits.flush();
        return views(List.of(visit)).getFirst();
    }

    /** Edits an open visit's details (whom, the complaint, the temperature and the treatment). */
    public VisitView update(UUID id, VisitForm form, Actor actor) {
        TenantContext.require();
        InfirmaryVisit visit = require(id);
        if (!visit.isOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "Visit closed", "This visit is already closed.");
        }
        visit.apply(details(form, visit.getTimeIn()));
        List<MedicineGiven> taken = give(visit, form.medicines(), Instant.now());
        Map<String, Object> details = auditDetails(visit);
        if (!taken.isEmpty()) {
            details.put("medicines", taken.size());
        }
        audit.record(actor, "infirmary_visit.updated", RELATED, id, details);
        visits.flush();
        return views(List.of(visit)).getFirst();
    }

    /** Closes a visit with its outcome. Sent home or to hospital: the parent is told and the event published. */
    public VisitView close(UUID id, CloseForm form, Actor actor) {
        TenantContext.require();
        InfirmaryVisit visit = require(id);
        if (!visit.isOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "Visit closed", "This visit is already closed.");
        }
        Instant now = Instant.now();
        Instant out = form.timeOut() == null ? now : form.timeOut();
        if (out.isAfter(now.plusSeconds(300))) {
            throw ApiException.badRequest("The time out cannot be in the future.", "timeOut");
        }
        boolean leaving = form.outcome() != VisitOutcome.BACK_TO_CLASS;
        visit.close(form.outcome(), out, HealthText.clean(form.treatment()), Boolean.TRUE.equals(form.parentInformed()));
        Map<String, Object> details = auditDetails(visit);
        if (leaving && visit.getPersonKind() == PersonKind.STUDENT) {
            Optional<GuardianContact> guardian = people.primaryGuardian(visit.getStudentId());
            guardian.ifPresent(g -> {
                tellParent(visit, g, now);
                visit.markParentInformed();
                details.put("parentInformed", HealthText.maskPhone(g.phone()));
            });
            events.publishEvent(new StudentSentHome(TenantContext.require(), visit.getId(), visit.getStudentId(),
                    visit.getPersonName(), visit.getSectionId(), visit.getOutcome(), out));
        }
        audit.record(actor, "infirmary_visit.closed", RELATED, id, details);
        visits.flush();
        return views(List.of(visit)).getFirst();
    }

    /* ------------------------------------------------------------------ medicines */

    @Transactional(readOnly = true)
    public List<MedicineView> medicines() {
        TenantContext.require();
        return medicines.findAllByOrderByNameAsc().stream().map(InfirmaryService::view).toList();
    }

    public MedicineView addMedicine(MedicineForm form, Actor actor) {
        TenantContext.require();
        String name = form.name().trim();
        if (medicines.findAll().stream().anyMatch(m -> m.getName().equalsIgnoreCase(name))) {
            throw ApiException.conflict("A medicine with this name is already in the stock.", "name");
        }
        Medicine m = medicines.save(new Medicine(name, form.unit().trim(), form.quantity(),
                form.reorderLevel() == null ? 0 : form.reorderLevel(), form.expiresOn(), Instant.now()));
        audit.record(actor, "medicine.added", "medicine", m.getId(), Map.of("name", m.getName(), "quantity",
                m.getQuantity()));
        medicines.flush();
        return view(m);
    }

    public MedicineView updateMedicine(UUID id, MedicineForm form, Actor actor) {
        TenantContext.require();
        Medicine m = medicines.findById(id).orElseThrow(() -> ApiException.notFound("Medicine"));
        String name = form.name().trim();
        if (medicines.findAll().stream().anyMatch(o -> !o.getId().equals(id) && o.getName().equalsIgnoreCase(name))) {
            throw ApiException.conflict("A medicine with this name is already in the stock.", "name");
        }
        m.apply(name, form.unit().trim(), form.quantity(), form.reorderLevel() == null ? 0 : form.reorderLevel(),
                form.expiresOn());
        audit.record(actor, "medicine.updated", "medicine", id, Map.of("name", m.getName(), "quantity",
                m.getQuantity()));
        medicines.flush();
        return view(m);
    }

    public void removeMedicine(UUID id, Actor actor) {
        TenantContext.require();
        Medicine m = medicines.findById(id).orElseThrow(() -> ApiException.notFound("Medicine"));
        if (!given.findAll().stream().filter(g -> g.getMedicineId().equals(id)).toList().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "In use",
                    "This medicine was given during visits; set its quantity to zero instead.");
        }
        medicines.delete(m);
        audit.record(actor, "medicine.removed", "medicine", id, Map.of("name", m.getName()));
        medicines.flush();
    }

    /* ------------------------------------------------------------------ helpers */

    private InfirmaryVisit.Details details(VisitForm form, Instant defaultTimeIn) {
        Instant timeIn = form.timeIn() == null ? defaultTimeIn : form.timeIn();
        if (timeIn.isAfter(Instant.now().plusSeconds(300))) {
            throw ApiException.badRequest("The time in cannot be in the future.", "timeIn");
        }
        BigDecimal temperature = form.temperatureC() == null ? null
                : form.temperatureC().setScale(1, RoundingMode.HALF_UP);
        if (form.personKind() == PersonKind.STUDENT) {
            if (form.studentId() == null) {
                throw ApiException.badRequest("Pick the student.", "studentId");
            }
            Pupil pupil = people.requireActivePupil(form.studentId(), "studentId");
            return new InfirmaryVisit.Details(PersonKind.STUDENT, pupil.id(), null, pupil.fullName(),
                    pupil.className(), pupil.sectionId(), timeIn, form.complaint().trim(), temperature,
                    HealthText.clean(form.treatment()));
        }
        if (form.staffUserId() == null) {
            throw ApiException.badRequest("Pick the member of staff.", "staffUserId");
        }
        Staff staff = people.requireStaff(form.staffUserId(), "staffUserId");
        return new InfirmaryVisit.Details(PersonKind.STAFF, null, staff.userId(), staff.name(), staff.designation(),
                null, timeIn, form.complaint().trim(), temperature, HealthText.clean(form.treatment()));
    }

    /** Takes the medicines from the stock and records them against the visit. 400 when the stock is short. */
    private List<MedicineGiven> give(InfirmaryVisit visit, List<GivenForm> forms, Instant now) {
        if (forms == null || forms.isEmpty()) {
            return List.of();
        }
        // Lock the medicines in one order (by id) so two visits taking the same ones cannot deadlock.
        forms.stream().map(GivenForm::medicineId).filter(id -> id != null).distinct().sorted()
                .forEach(medicines::lock);
        List<MedicineGiven> result = new ArrayList<>();
        for (GivenForm g : forms) {
            Medicine m = medicines.lock(g.medicineId())
                    .orElseThrow(() -> ApiException.badRequest("Pick a medicine from the stock.", "medicines"));
            if (!m.take(g.quantity())) {
                throw ApiException.badRequest("Only " + m.getQuantity() + " " + m.getUnit() + " of " + m.getName()
                        + " left in stock.", "medicines");
            }
            result.add(given.save(new MedicineGiven(visit.getId(), m.getId(), g.quantity(), now)));
        }
        medicines.flush();
        return result;
    }

    private void tellParent(InfirmaryVisit visit, GuardianContact guardian, Instant now) {
        MessageSettings school = messageSettings.current();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("guardian", guardian.name());
        params.put("student", visit.getPersonName());
        params.put("class", visit.getClassName() == null ? "" : visit.getClassName());
        params.put("school", tenants.profile(TenantContext.require()).map(p -> p.name()).orElse(""));
        params.put("time", HealthText.time(visit.getTimeIn()));
        params.put("complaint", visit.getComplaint());
        String template = visit.getOutcome() == VisitOutcome.HOSPITAL ? MessageTemplates.INFIRMARY_HOSPITAL
                : MessageTemplates.INFIRMARY_SENT_HOME;
        Recipient to = Recipient.phone(school.absenceAlertChannel(), guardian.phone().trim(), guardian.name(),
                Map.of(), "infirmary:" + visit.getId());
        queue.enqueue(template, school.alertLanguage(), params, new Related(RELATED, visit.getId(),
                visit.getPersonName() + (visit.getClassName() == null ? "" : " (" + visit.getClassName() + ")")),
                List.of(to), now);
    }

    private InfirmaryVisit require(UUID id) {
        return visits.findById(id).orElseThrow(() -> ApiException.notFound("Visit"));
    }

    private static Map<String, Object> auditDetails(InfirmaryVisit v) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("person", v.getPersonName());
        details.put("kind", v.getPersonKind().name());
        details.put("complaint", v.getComplaint());
        if (v.getOutcome() != null) {
            details.put("outcome", v.getOutcome().name());
        }
        return details;
    }

    /** Views of visits with their medicines and the students' allergy badges, in the order given. */
    List<VisitView> views(List<InfirmaryVisit> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Medicine> byId = medicines.findAll().stream().collect(Collectors.toMap(Medicine::getId,
                Function.identity()));
        Map<UUID, List<GivenView>> givenByVisit = new HashMap<>();
        for (MedicineGiven g : given.findByVisitIdIn(rows.stream().map(InfirmaryVisit::getId).toList())) {
            Medicine m = byId.get(g.getMedicineId());
            givenByVisit.computeIfAbsent(g.getVisitId(), k -> new ArrayList<>()).add(new GivenView(g.getMedicineId(),
                    m == null ? "?" : m.getName(), m == null ? "" : m.getUnit(), g.getQuantity()));
        }
        Map<UUID, List<AllergyBadge>> badges = alerts.forStudents(rows.stream().map(InfirmaryVisit::getStudentId)
                .filter(id -> id != null).toList());
        return rows.stream().map(v -> new VisitView(v.getId(), v.getPersonKind(), v.getStudentId(),
                v.getStaffUserId(), v.getPersonName(), v.getClassName(), v.getSectionId(), v.getTimeIn(),
                v.getTimeOut(), v.getComplaint(), v.getTemperatureC(), v.getTreatment(), v.getOutcome(),
                v.isParentInformed(), givenByVisit.getOrDefault(v.getId(), List.of()),
                v.getStudentId() == null ? List.of() : badges.getOrDefault(v.getStudentId(), List.of()),
                v.getRecordedByName())).toList();
    }

    private static MedicineView view(Medicine m) {
        return new MedicineView(m.getId(), m.getName(), m.getUnit(), m.getQuantity(), m.getReorderLevel(),
                m.getExpiresOn(), m.getQuantity() <= m.getReorderLevel(),
                m.getExpiresOn() != null && m.getExpiresOn().isBefore(HealthText.today()));
    }
}
