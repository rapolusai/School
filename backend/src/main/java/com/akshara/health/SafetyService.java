package com.akshara.health;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.health.HealthForms.AuditForm;
import com.akshara.health.HealthForms.CloseAuditForm;
import com.akshara.health.HealthForms.DrillForm;
import com.akshara.health.HealthForms.IncidentForm;
import com.akshara.health.HealthForms.KitCheckForm;
import com.akshara.health.HealthForms.KitForm;
import com.akshara.health.HealthPeople.Pupil;
import com.akshara.health.HealthText.Due;
import com.akshara.health.HealthViews.AuditView;
import com.akshara.health.HealthViews.DrillView;
import com.akshara.health.HealthViews.IncidentView;
import com.akshara.health.HealthViews.KitView;
import com.akshara.health.HealthViews.SafetyOverview;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The safety registers: fire and evacuation drills (with when the next is due), the safety audit checklist with
 * findings and closure, the incident register, and first-aid kit checks by location. Every change is audited.
 */
@Service
@Transactional
public class SafetyService {

    /** A drill is due again this many days after the last one when the register gives no date. */
    static final int DRILL_INTERVAL_DAYS = 90;
    /** A first-aid kit is checked at least this often. */
    static final int KIT_CHECK_DAYS = 90;

    private final FireDrillRepository drills;
    private final SafetyAuditRepository audits;
    private final IncidentRepository incidents;
    private final FirstAidKitRepository kits;
    private final HealthPeople people;
    private final AuditService audit;

    SafetyService(FireDrillRepository drills, SafetyAuditRepository audits, IncidentRepository incidents,
            FirstAidKitRepository kits, HealthPeople people, AuditService audit) {
        this.drills = drills;
        this.audits = audits;
        this.incidents = incidents;
        this.kits = kits;
        this.people = people;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public SafetyOverview overview() {
        TenantContext.require();
        LocalDate today = HealthText.today();
        return new SafetyOverview(today, drillViews(today), audits.listed().stream().map(a -> view(a, today))
                .toList(), incidentViews(), kits.findAllByOrderByLocationAsc().stream().map(k -> view(k, today))
                .toList());
    }

    /* ------------------------------------------------------------------ drills */

    @Transactional(readOnly = true)
    public List<DrillView> drills() {
        TenantContext.require();
        return drillViews(HealthText.today());
    }

    public DrillView addDrill(DrillForm form, Actor actor) {
        TenantContext.require();
        FireDrill drill = drills.save(new FireDrill(drillDetails(form), actor.name(), Instant.now()));
        audit.record(actor, "fire_drill.recorded", "fire_drill", drill.getId(), drillDetails(drill));
        drills.flush();
        return view(drill, HealthText.today());
    }

    public DrillView updateDrill(UUID id, DrillForm form, Actor actor) {
        TenantContext.require();
        FireDrill drill = drills.findById(id).orElseThrow(() -> ApiException.notFound("Drill"));
        drill.apply(drillDetails(form));
        audit.record(actor, "fire_drill.updated", "fire_drill", id, drillDetails(drill));
        drills.flush();
        return view(drill, HealthText.today());
    }

    public void removeDrill(UUID id, Actor actor) {
        TenantContext.require();
        FireDrill drill = drills.findById(id).orElseThrow(() -> ApiException.notFound("Drill"));
        drills.delete(drill);
        audit.record(actor, "fire_drill.removed", "fire_drill", id, drillDetails(drill));
        drills.flush();
    }

    private static FireDrill.Details drillDetails(DrillForm form) {
        if (form.drillDate().isAfter(HealthText.today())) {
            throw ApiException.badRequest("The drill date cannot be in the future.", "drillDate");
        }
        LocalDate nextDue = form.nextDue() == null ? form.drillDate().plusDays(DRILL_INTERVAL_DAYS) : form.nextDue();
        if (!nextDue.isAfter(form.drillDate())) {
            throw ApiException.badRequest("The next drill is due after this one.", "nextDue");
        }
        return new FireDrill.Details(form.drillDate(), form.kind(), form.block().trim(), form.timeToClearSeconds(),
                form.attendance(), HealthText.clean(form.issues()), nextDue);
    }

    private static Map<String, Object> drillDetails(FireDrill d) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("date", d.getDrillDate().toString());
        details.put("kind", d.getKind().name());
        details.put("block", d.getBlock());
        details.put("timeToClearSeconds", d.getTimeToClearSeconds());
        details.put("nextDue", d.getNextDue().toString());
        return details;
    }

    private List<DrillView> drillViews(LocalDate today) {
        return drills.findAllByOrderByDrillDateDesc().stream().map(d -> view(d, today)).toList();
    }

    static DrillView view(FireDrill d, LocalDate today) {
        return new DrillView(d.getId(), d.getDrillDate(), d.getKind(), d.getBlock(), d.getTimeToClearSeconds(),
                d.getAttendance(), d.getIssues(), d.getNextDue(), HealthText.dueState(d.getNextDue(), today),
                d.getConductedByName());
    }

    /** When the next drill is due: the latest next-due date in the register, or null when there was no drill. */
    static LocalDate nextDrillDue(List<DrillView> rows) {
        return rows.stream().map(DrillView::nextDue).max(LocalDate::compareTo).orElse(null);
    }

    /* ------------------------------------------------------------------ safety audits */

    public AuditView addAudit(AuditForm form, Actor actor) {
        TenantContext.require();
        SafetyAudit a = audits.save(new SafetyAudit(auditDetails(form), actor.name(), Instant.now()));
        audit.record(actor, "safety_audit.recorded", "safety_audit", a.getId(), auditDetails(a));
        audits.flush();
        return view(a, HealthText.today());
    }

    public AuditView updateAudit(UUID id, AuditForm form, Actor actor) {
        TenantContext.require();
        SafetyAudit a = audits.findById(id).orElseThrow(() -> ApiException.notFound("Finding"));
        a.apply(auditDetails(form));
        audit.record(actor, "safety_audit.updated", "safety_audit", id, auditDetails(a));
        audits.flush();
        return view(a, HealthText.today());
    }

    public AuditView closeAudit(UUID id, CloseAuditForm form, Actor actor) {
        TenantContext.require();
        SafetyAudit a = audits.findById(id).orElseThrow(() -> ApiException.notFound("Finding"));
        if (a.getStatus() == AuditStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "Already closed", "This finding is already closed.");
        }
        LocalDate on = form.closedOn() == null ? HealthText.today() : form.closedOn();
        if (on.isBefore(a.getAuditDate())) {
            throw ApiException.badRequest("The closure cannot be before the audit.", "closedOn");
        }
        a.close(on, HealthText.clean(form.closureNote()));
        audit.record(actor, "safety_audit.closed", "safety_audit", id, auditDetails(a));
        audits.flush();
        return view(a, HealthText.today());
    }

    public AuditView reopenAudit(UUID id, Actor actor) {
        TenantContext.require();
        SafetyAudit a = audits.findById(id).orElseThrow(() -> ApiException.notFound("Finding"));
        if (a.getStatus() == AuditStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "Already open", "This finding is open.");
        }
        a.reopen();
        audit.record(actor, "safety_audit.reopened", "safety_audit", id, auditDetails(a));
        audits.flush();
        return view(a, HealthText.today());
    }

    private static SafetyAudit.Details auditDetails(AuditForm form) {
        if (form.dueOn() != null && form.dueOn().isBefore(form.auditDate())) {
            throw ApiException.badRequest("The due date is on or after the audit.", "dueOn");
        }
        return new SafetyAudit.Details(form.area(), form.auditDate(), form.finding().trim(), form.severity(),
                form.dueOn());
    }

    private static Map<String, Object> auditDetails(SafetyAudit a) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("area", a.getArea().name());
        details.put("severity", a.getSeverity().name());
        details.put("status", a.getStatus().name());
        details.put("finding", a.getFinding());
        return details;
    }

    static AuditView view(SafetyAudit a, LocalDate today) {
        boolean overdue = a.getStatus() == AuditStatus.OPEN && a.getDueOn() != null && a.getDueOn().isBefore(today);
        return new AuditView(a.getId(), a.getArea(), a.getAuditDate(), a.getFinding(), a.getSeverity(), a.getStatus(),
                a.getDueOn(), overdue, a.getClosedOn(), a.getClosureNote(), a.getAuditedByName());
    }

    /* ------------------------------------------------------------------ incidents */

    public IncidentView addIncident(IncidentForm form, Actor actor) {
        TenantContext.require();
        Incident i = incidents.save(new Incident(incidentDetails(form), actor.name(), Instant.now()));
        audit.record(actor, "incident.recorded", "incident", i.getId(), incidentDetails(i));
        incidents.flush();
        return incidentViews().stream().filter(v -> v.id().equals(i.getId())).findFirst().orElseThrow();
    }

    public IncidentView updateIncident(UUID id, IncidentForm form, Actor actor) {
        TenantContext.require();
        Incident i = incidents.findById(id).orElseThrow(() -> ApiException.notFound("Incident"));
        i.apply(incidentDetails(form));
        audit.record(actor, "incident.updated", "incident", id, incidentDetails(i));
        incidents.flush();
        return incidentViews().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    private Incident.Details incidentDetails(IncidentForm form) {
        Instant at = form.incidentAt() == null ? Instant.now() : form.incidentAt();
        if (at.isAfter(Instant.now().plusSeconds(300))) {
            throw ApiException.badRequest("The incident cannot be in the future.", "incidentAt");
        }
        String personName = HealthText.clean(form.personName());
        if (form.studentId() != null) {
            personName = people.requireActivePupil(form.studentId(), "studentId").fullName();
        }
        return new Incident.Details(at, form.kind(), form.severity(), form.studentId(), personName,
                HealthText.clean(form.place()), form.description().trim(), HealthText.clean(form.actions()),
                Boolean.TRUE.equals(form.parentInformed()));
    }

    private static Map<String, Object> incidentDetails(Incident i) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", i.getKind().name());
        details.put("severity", i.getSeverity().name());
        if (i.getPersonName() != null) {
            details.put("person", i.getPersonName());
        }
        details.put("parentInformed", i.isParentInformed());
        return details;
    }

    private List<IncidentView> incidentViews() {
        List<Incident> rows = incidents.findAllByOrderByIncidentAtDesc();
        Map<UUID, Pupil> pupils = people.pupils(rows.stream().map(Incident::getStudentId).filter(id -> id != null)
                .toList());
        return rows.stream().map(i -> {
            Pupil p = i.getStudentId() == null ? null : pupils.get(i.getStudentId());
            return new IncidentView(i.getId(), i.getIncidentAt(), i.getKind(), i.getSeverity(), i.getStudentId(),
                    i.getPersonName(), p == null ? null : p.className(), i.getPlace(), i.getDescription(),
                    i.getActions(), i.isParentInformed(), i.getReportedByName());
        }).toList();
    }

    /* ------------------------------------------------------------------ first-aid kits */

    public KitView addKit(KitForm form, Actor actor) {
        TenantContext.require();
        String location = form.location().trim();
        if (kits.findAll().stream().anyMatch(k -> k.getLocation().equalsIgnoreCase(location))) {
            throw ApiException.conflict("There is already a kit at this location.", "location");
        }
        FirstAidKit kit = kits.save(new FirstAidKit(location, Instant.now()));
        audit.record(actor, "first_aid_kit.added", "first_aid_kit", kit.getId(), Map.of("location", location));
        kits.flush();
        return view(kit, HealthText.today());
    }

    public KitView checkKit(UUID id, KitCheckForm form, Actor actor) {
        TenantContext.require();
        FirstAidKit kit = kits.findById(id).orElseThrow(() -> ApiException.notFound("First-aid kit"));
        LocalDate on = form.checkedOn() == null ? HealthText.today() : form.checkedOn();
        if (on.isAfter(HealthText.today())) {
            throw ApiException.badRequest("The check cannot be in the future.", "checkedOn");
        }
        kit.check(on, form.complete(), form.earliestExpiry(), HealthText.clean(form.missingItems()), actor.name());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("location", kit.getLocation());
        details.put("checkedOn", on.toString());
        details.put("complete", form.complete());
        audit.record(actor, "first_aid_kit.checked", "first_aid_kit", id, details);
        kits.flush();
        return view(kit, HealthText.today());
    }

    public void removeKit(UUID id, Actor actor) {
        TenantContext.require();
        FirstAidKit kit = kits.findById(id).orElseThrow(() -> ApiException.notFound("First-aid kit"));
        kits.delete(kit);
        audit.record(actor, "first_aid_kit.removed", "first_aid_kit", id, Map.of("location", kit.getLocation()));
        kits.flush();
    }

    static KitView view(FirstAidKit k, LocalDate today) {
        Due expiry = k.getEarliestExpiry() == null ? Due.OK : HealthText.dueState(k.getEarliestExpiry(), today);
        boolean checkDue = k.getCheckedOn() == null || !k.getCheckedOn().plusDays(KIT_CHECK_DAYS).isAfter(today);
        return new KitView(k.getId(), k.getLocation(), k.getCheckedOn(), k.isComplete(), k.getEarliestExpiry(),
                k.getMissingItems(), k.getCheckedByName(), expiry, checkDue);
    }
}
