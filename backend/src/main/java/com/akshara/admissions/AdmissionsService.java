package com.akshara.admissions;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.admissions.AdmissionForms.AdmitRequest;
import com.akshara.admissions.AdmissionForms.ApplicationRequest;
import com.akshara.admissions.AdmissionForms.FeeRequest;
import com.akshara.admissions.AdmissionForms.GuardianInput;
import com.akshara.admissions.AdmissionForms.OfferRequest;
import com.akshara.admissions.AdmissionForms.PublicEnquiry;
import com.akshara.admissions.AdmissionForms.SlotRequest;
import com.akshara.admissions.AdmissionForms.StageRequest;
import com.akshara.admissions.AdmissionTypes.AssessmentKind;
import com.akshara.admissions.AdmissionTypes.AssessmentMode;
import com.akshara.admissions.AdmissionTypes.FeeStatus;
import com.akshara.admissions.AdmissionTypes.PaymentMethod;
import com.akshara.admissions.AdmissionTypes.SlotStatus;
import com.akshara.admissions.AdmissionTypes.TimelineKind;
import com.akshara.admissions.AdmissionTypes.YearChoice;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.RoleCatalog;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.platform.Board;
import com.akshara.platform.TenantView;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;
import com.akshara.students.Gender;
import com.akshara.students.GuardianRelation;
import com.akshara.students.Phones;
import com.akshara.students.StudentForms.CreateStudent;
import com.akshara.students.StudentForms.GuardianFields;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentDetail;

import tools.jackson.databind.json.JsonMapper;

/**
 * The admissions pipeline of the current school: enquiries and applications, their timeline, entrance tests and
 * interviews, the application fee, offers, and admitting the child as a student. Every change is written to the
 * application's timeline and to the audit trail in the same transaction.
 */
@Service
@Transactional
public class AdmissionsService {

    static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Kolkata");
    public static final int MAX_PAGE_SIZE = 100;
    /** Cards shown per board column; the column header still counts every application at that stage. */
    static final int LANE_LIMIT = 50;
    static final int DEFAULT_OFFER_DAYS = 14;
    static final int MAX_GUARDIANS = 4;
    /** The consent text shown on the public enquiry form. Change it together with the text in the web app. */
    public static final String CONSENT_VERSION = "enquiry-2026-10";

    // ------------------------------------------------------------------ views

    public record StaffRef(UUID id, String name) {
    }

    /** One application in a list or on a board card. The contact's mobile number is masked. */
    public record ApplicationRow(UUID id, String childName, UUID classId, String className, UUID academicYearId,
            String academicYearName, ApplicationStage stage, Instant stageChangedAt, long daysInStage,
            LocalDate followUpOn, ApplicationSource source, String contactName, String contactPhone,
            String assignedToName, Instant nextSlotAt, Instant createdAt, List<ApplicationStage> nextStages) {
    }

    public record ApplicationPage(List<ApplicationRow> items, int page, int size, long total,
            Map<ApplicationStage, Long> stageCounts) {
    }

    public record Lane(ApplicationStage stage, long total, List<ApplicationRow> cards) {
    }

    /** The pipeline columns; rejected and withdrawn applications are only counted. */
    public record BoardView(List<Lane> lanes, long closed) {
    }

    public record GuardianView(String name, GuardianRelation relation, String phone, String email, boolean primary) {
    }

    public record FeeView(FeeStatus status, Long amountPaise, PaymentMethod method, String reference,
            LocalDate paidOn) {
    }

    public record OfferView(LocalDate offeredOn, LocalDate validUntil) {
    }

    public record SlotView(UUID id, AssessmentKind kind, Instant scheduledAt, AssessmentMode mode, String location,
            String meetingLink, StaffRef interviewer, SlotStatus status, String outcomeNotes) {
    }

    public record TimelineView(UUID id, Instant at, TimelineKind kind, String actorName, ApplicationStage fromStage,
            ApplicationStage toStage, String note, Map<String, Object> details) {
    }

    public record ApplicationDetail(UUID id, ApplicationStage stage, Instant stageChangedAt, long daysInStage,
            List<ApplicationStage> nextStages, String firstName, String lastName, String childName,
            LocalDate dateOfBirth, Gender gender, String previousSchool, UUID classId, String className,
            UUID academicYearId, String academicYearName, ApplicationSource source, StaffRef assignedTo,
            LocalDate followUpOn, String message, String consentVersion, Instant consentAt,
            List<GuardianView> guardians, FeeView fee, OfferView offer, UUID studentId, List<SlotView> slots,
            List<TimelineView> timeline, Instant createdAt) {
    }

    public record UpcomingSlot(UUID id, UUID applicationId, String childName, String className, AssessmentKind kind,
            Instant scheduledAt, AssessmentMode mode, String location, String meetingLink, StaffRef interviewer) {
    }

    /** Numbers for the dashboard card. "This year" is the current academic year (else the calendar year). */
    public record Summary(long openEnquiries, long inProgress, long offersPending, long admittedThisYear,
            long upcomingSlots) {
    }

    /** What the public enquiry page of a school shows. Nothing else about the school is revealed. */
    public record PublicSchoolInfo(String name, Board board, String city, List<String> classes,
            List<YearOption> years, String consentVersion) {
    }

    public record YearOption(YearChoice code, String name) {
    }

    /** Filters for the list and the board. Null means "any". */
    public record ApplicationQuery(UUID academicYearId, UUID classId, ApplicationStage stage, ApplicationSource source,
            String search, int page, int size) {
    }

    private record CleanGuardian(String name, GuardianRelation relation, String phone, String email,
            boolean primary) {

        String key() {
            return phone + "|" + name.toLowerCase(Locale.ROOT);
        }
    }

    private record Checked(Application.Details details, List<CleanGuardian> guardians, String className,
            String yearName) {
    }

    private record Where(String jpql, Map<String, Object> params) {
    }

    private final ApplicationRepository applications;
    private final ApplicationGuardianRepository guardians;
    private final TimelineEntryRepository timeline;
    private final AssessmentSlotRepository slots;
    private final AcademicsService academicsService;
    private final AcademicsDirectory academics;
    private final UserRepository users;
    private final StudentService students;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final EntityManager entityManager;
    private final Validator validator;

    public AdmissionsService(ApplicationRepository applications, ApplicationGuardianRepository guardians,
            TimelineEntryRepository timeline, AssessmentSlotRepository slots, AcademicsService academicsService,
            AcademicsDirectory academics, UserRepository users, StudentService students, AuditService audit,
            ApplicationEventPublisher events, JsonMapper json, EntityManager entityManager, Validator validator) {
        this.applications = applications;
        this.guardians = guardians;
        this.timeline = timeline;
        this.slots = slots;
        this.academicsService = academicsService;
        this.academics = academics;
        this.users = users;
        this.students = students;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.entityManager = entityManager;
        this.validator = validator;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public Summary summary() {
        TenantContext.require();
        LocalDate today = today();
        Optional<YearInfo> current = academics.currentYear();
        LocalDate from = current.map(YearInfo::startsOn).orElse(today.withDayOfYear(1));
        LocalDate to = current.map(y -> y.endsOn().plusDays(1)).orElse(from.plusYears(1));
        return new Summary(
                applications.countByStage(ApplicationStage.ENQUIRY),
                applications.countByStage(ApplicationStage.APPLICATION)
                        + applications.countByStage(ApplicationStage.ASSESSMENT),
                applications.countByStage(ApplicationStage.OFFERED),
                applications.countByStageChangedBetween(ApplicationStage.ADMITTED, startOf(from), startOf(to)),
                slots.countByStatusFrom(SlotStatus.SCHEDULED, Instant.now()));
    }

    /** Applications matching the filters, newest first, with counts per stage (ignoring the stage filter). */
    @Transactional(readOnly = true)
    public ApplicationPage list(ApplicationQuery query) {
        TenantContext.require();
        int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
        int page = Math.max(query.page(), 0);
        Where where = where(query);
        Map<ApplicationStage, Long> counts = stageCounts(where);

        String jpql = where.jpql();
        Map<String, Object> params = new HashMap<>(where.params());
        if (query.stage() != null) {
            jpql += " and a.stage = :stage";
            params.put("stage", query.stage());
        }
        TypedQuery<Long> count = entityManager.createQuery("select count(a)" + jpql, Long.class);
        TypedQuery<Application> rows = entityManager.createQuery(
                "select a" + jpql + " order by a.createdAt desc, a.id desc", Application.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            rows.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<Application> found = rows.setFirstResult(page * size).setMaxResults(size).getResultList();
        return new ApplicationPage(rows(found), page, size, total, counts);
    }

    /**
     * The pipeline board: a column per stage from enquiry to admitted. Open columns put the most urgent first (the
     * earliest follow-up, then the longest wait); the admitted column shows the latest admissions first.
     */
    @Transactional(readOnly = true)
    public BoardView board(ApplicationQuery query) {
        TenantContext.require();
        Where where = where(query);
        Map<ApplicationStage, Long> counts = stageCounts(where);
        List<Application> cards = new ArrayList<>();
        for (ApplicationStage stage : ApplicationStage.PIPELINE) {
            String order = stage == ApplicationStage.ADMITTED ? " order by a.stageChangedAt desc, a.id desc"
                    : " order by a.followUpOn asc nulls last, a.stageChangedAt asc, a.id asc";
            TypedQuery<Application> lane = entityManager.createQuery(
                    "select a" + where.jpql() + " and a.stage = :stage" + order, Application.class);
            where.params().forEach(lane::setParameter);
            lane.setParameter("stage", stage);
            cards.addAll(lane.setMaxResults(LANE_LIMIT).getResultList());
        }
        Map<ApplicationStage, List<ApplicationRow>> byStage = rows(cards).stream()
                .collect(Collectors.groupingBy(ApplicationRow::stage, LinkedHashMap::new, Collectors.toList()));
        List<Lane> lanes = ApplicationStage.PIPELINE.stream()
                .map(stage -> new Lane(stage, counts.get(stage), byStage.getOrDefault(stage, List.of())))
                .toList();
        return new BoardView(lanes, counts.get(ApplicationStage.REJECTED) + counts.get(ApplicationStage.WITHDRAWN));
    }

    @Transactional(readOnly = true)
    public ApplicationDetail detail(UUID id) {
        TenantContext.require();
        return detail(find(id));
    }

    /** Scheduled tests and interviews in the next {@code days} days, soonest first (at most 200). */
    @Transactional(readOnly = true)
    public List<UpcomingSlot> upcomingSlots(int days) {
        TenantContext.require();
        Instant now = Instant.now();
        List<AssessmentSlot> found = slots.findByStatusBetween(SlotStatus.SCHEDULED, now,
                now.plus(Math.max(1, Math.min(days, 366)), ChronoUnit.DAYS), Limit.of(200));
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, Application> byId = applications.findAllById(found.stream().map(AssessmentSlot::getApplicationId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Application::getId, Function.identity()));
        Map<UUID, String> classNames = classNames();
        Map<UUID, String> staffNames = staffNames(found.stream().map(AssessmentSlot::getInterviewerId).toList());
        return found.stream()
                .filter(s -> byId.containsKey(s.getApplicationId()))
                .map(s -> {
                    Application a = byId.get(s.getApplicationId());
                    return new UpcomingSlot(s.getId(), a.getId(), a.childName(), classNames.get(a.getClassId()),
                            s.getKind(), s.getScheduledAt(), s.getMode(), s.getLocation(), s.getMeetingLink(),
                            staffRef(s.getInterviewerId(), staffNames));
                })
                .toList();
    }

    /** Active staff (anyone with a role other than Parent or Student), for the counsellor and interviewer pickers. */
    @Transactional(readOnly = true)
    public List<StaffRef> staff() {
        TenantContext.require();
        return users.findAllWithRoles().stream()
                .filter(UserAccount::isActive)
                .filter(AdmissionsService::isStaff)
                .map(u -> new StaffRef(u.getId(), u.getName()))
                .toList();
    }

    // ------------------------------------------------------------------ creating and editing

    /** Staff enter an enquiry or an application that reached the school by phone, in person or by referral. */
    public ApplicationDetail create(ApplicationRequest form, Actor actor) {
        TenantContext.require();
        ApplicationStage stage = form.stage() == null ? ApplicationStage.ENQUIRY : form.stage();
        if (stage != ApplicationStage.ENQUIRY && stage != ApplicationStage.APPLICATION) {
            throw ApiException.badRequest("A new record starts as an enquiry or an application.", "stage");
        }
        Checked checked = check(form, null);
        Application application = applications.save(new Application(stage, checked.details(), null, null, null));
        saveGuardians(application.getId(), checked.guardians());
        Actor who = actor(actor);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", form.source().name());
        details.put("className", checked.className());
        details.put("academicYear", checked.yearName());
        addEntry(application, TimelineKind.CREATED, who, null, stage, blankToNull(form.note()), details);
        Map<String, Object> auditDetails = new LinkedHashMap<>(details);
        auditDetails.put("stage", stage.name());
        audit.record(who, "application.created", "application", application.getId(), auditDetails);
        applications.flush();
        return detail(application);
    }

    /** Edits the child, the class and year, the contacts, the source, the counsellor and the follow-up date. */
    public ApplicationDetail update(UUID id, ApplicationRequest form, Actor actor) {
        TenantContext.require();
        Application application = find(id);
        requireOpen(application);
        Checked checked = check(form, application);
        Application.Details d = checked.details();
        List<String> changed = new ArrayList<>();
        compare(changed, "firstName", application.getFirstName(), d.firstName());
        compare(changed, "lastName", application.getLastName(), d.lastName());
        compare(changed, "dateOfBirth", application.getDateOfBirth(), d.dateOfBirth());
        compare(changed, "gender", application.getGender(), d.gender());
        compare(changed, "previousSchool", application.getPreviousSchool(), d.previousSchool());
        compare(changed, "class", application.getClassId(), d.classId());
        compare(changed, "academicYear", application.getAcademicYearId(), d.academicYearId());
        compare(changed, "source", application.getSource(), d.source());
        compare(changed, "counsellor", application.getAssignedToId(), d.assignedToId());
        compare(changed, "followUpOn", application.getFollowUpOn(), d.followUpOn());
        application.apply(d);

        List<CleanGuardian> before = guardians.findByApplicationId(id).stream()
                .map(g -> new CleanGuardian(g.getName(), g.getRelation(), g.getPhone(), g.getEmail(), g.isPrimary()))
                .toList();
        if (!before.equals(checked.guardians())) {
            guardians.deleteByApplicationId(id);
            saveGuardians(id, checked.guardians());
            changed.add("guardians");
        }
        applications.flush();
        if (!changed.isEmpty()) {
            Actor who = actor(actor);
            addEntry(application, TimelineKind.UPDATED, who, null, null, null, Map.of("changed", changed));
            audit.record(who, "application.updated", "application", id, Map.of("changed", changed));
        }
        return detail(application);
    }

    // ------------------------------------------------------------------ stages, notes, offers, fees

    /** Moves an application along the pipeline, or closes it as rejected or withdrawn. */
    public ApplicationDetail moveStage(UUID id, StageRequest form, Actor actor) {
        TenantContext.require();
        Application application = find(id);
        ApplicationStage from = application.getStage();
        ApplicationStage to = form.stage();
        if (to == from) {
            throw notAllowed("The application is already at the " + label(to) + " stage.", "stage");
        }
        if (to == ApplicationStage.ADMITTED && from == ApplicationStage.OFFERED) {
            throw notAllowed("Use \"Admit as student\" so that the student record is created.", "stage");
        }
        if (!from.canMoveTo(to)) {
            throw notAllowed(from.isFinal() ? "This application is closed and cannot move any more."
                    : "An application cannot move from " + label(from) + " to " + label(to) + ".", "stage");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (to == ApplicationStage.OFFERED) {
            LocalDate on = form.offeredOn() != null ? form.offeredOn() : today();
            LocalDate until = form.offerValidUntil() != null ? form.offerValidUntil() : on.plusDays(DEFAULT_OFFER_DAYS);
            checkOffer(on, until, "offerValidUntil");
            application.offer(on, until);
            details.put("offeredOn", on.toString());
            details.put("validUntil", until.toString());
        }
        changeStage(application, to, actor(actor), blankToNull(form.note()), details);
        return detail(application);
    }

    /** Adds a note to the timeline. Notes can be added at any stage, closed applications included. */
    public ApplicationDetail addNote(UUID id, String note, Actor actor) {
        TenantContext.require();
        Application application = find(id);
        Actor who = actor(actor);
        addEntry(application, TimelineKind.NOTE, who, null, null, note.trim(), Map.of());
        audit.record(who, "application.note_added", "application", id, Map.of("stage", application.getStage().name()));
        return detail(application);
    }

    /** Records the application fee as paid (amount and method) or waived. Recording again corrects it. */
    public ApplicationDetail recordFee(UUID id, FeeRequest form, Actor actor) {
        TenantContext.require();
        Application application = find(id);
        requireOpen(application);
        Long amount = null;
        PaymentMethod method = null;
        if (form.status() == FeeStatus.PAID) {
            if (form.amountPaise() == null) {
                throw ApiException.badRequest("Enter the amount paid.", "amountPaise");
            }
            if (form.method() == null) {
                throw ApiException.badRequest("Choose how the fee was paid.", "method");
            }
            amount = form.amountPaise();
            method = form.method();
        }
        if (form.paidOn().isAfter(today())) {
            throw ApiException.badRequest("The date cannot be in the future.", "paidOn");
        }
        String reference = blankToNull(form.reference());
        application.recordFee(form.status(), amount, method, reference, form.paidOn());
        applications.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", form.status().name());
        if (amount != null) {
            details.put("amountPaise", amount);
            details.put("method", method.name());
        }
        if (reference != null) {
            details.put("reference", reference);
        }
        details.put("paidOn", form.paidOn().toString());
        Actor who = actor(actor);
        addEntry(application, TimelineKind.FEE_RECORDED, who, null, null, blankToNull(form.note()), details);
        Map<String, Object> auditDetails = new LinkedHashMap<>(details);
        auditDetails.remove("reference");
        audit.record(who, "application.fee_recorded", "application", id, auditDetails);
        return detail(application);
    }

    /** Changes the dates on the offer letter of an offered application. */
    public ApplicationDetail updateOffer(UUID id, OfferRequest form, Actor actor) {
        TenantContext.require();
        Application application = find(id);
        if (application.getStage() != ApplicationStage.OFFERED) {
            throw notAllowed("Only an application that has been offered a place has an offer letter.", "offeredOn");
        }
        checkOffer(form.offeredOn(), form.validUntil(), "validUntil");
        application.offer(form.offeredOn(), form.validUntil());
        applications.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("offeredOn", form.offeredOn().toString());
        details.put("validUntil", form.validUntil() == null ? null : form.validUntil().toString());
        Actor who = actor(actor);
        addEntry(application, TimelineKind.OFFER_UPDATED, who, null, null, null, details);
        audit.record(who, "application.offer_updated", "application", id, details);
        return detail(application);
    }

    // ------------------------------------------------------------------ tests and interviews

    /**
     * Schedules an entrance test or interview. An application moves to the assessment stage when its first slot is
     * scheduled.
     */
    public ApplicationDetail scheduleSlot(UUID id, SlotRequest form, Actor actor) {
        TenantContext.require();
        Application application = find(id);
        if (application.getStage() != ApplicationStage.APPLICATION
                && application.getStage() != ApplicationStage.ASSESSMENT) {
            throw notAllowed(application.getStage() == ApplicationStage.ENQUIRY
                    ? "Move the enquiry to Application before scheduling a test or interview."
                    : "Tests and interviews can only be scheduled for open applications.", "scheduledAt");
        }
        AssessmentSlot slot = slots.save(new AssessmentSlot(id, plan(form)));
        slots.flush();
        Actor who = actor(actor);
        addEntry(application, TimelineKind.SLOT_SCHEDULED, who, null, null, null, slotDetails(slot));
        audit.record(who, "assessment_slot.scheduled", "assessment_slot", slot.getId(), slotAudit(slot, id));
        if (application.getStage() == ApplicationStage.APPLICATION) {
            changeStage(application, ApplicationStage.ASSESSMENT, who, null, Map.of());
        }
        return detail(application);
    }

    public ApplicationDetail rescheduleSlot(UUID id, UUID slotId, SlotRequest form, Actor actor) {
        TenantContext.require();
        Application application = find(id);
        requireOpen(application);
        AssessmentSlot slot = findSlot(id, slotId);
        if (slot.getStatus() != SlotStatus.SCHEDULED) {
            throw notAllowed("Only a scheduled test or interview can be moved.", "scheduledAt");
        }
        Instant before = slot.getScheduledAt();
        slot.reschedule(plan(form));
        slots.flush();
        Map<String, Object> details = slotDetails(slot);
        details.put("previous", before.toString());
        Actor who = actor(actor);
        addEntry(application, TimelineKind.SLOT_RESCHEDULED, who, null, null, null, details);
        audit.record(who, "assessment_slot.rescheduled", "assessment_slot", slotId, slotAudit(slot, id));
        return detail(application);
    }

    /** Records how the test or interview went. Recording again replaces the notes. */
    public ApplicationDetail recordOutcome(UUID id, UUID slotId, String notes, Actor actor) {
        TenantContext.require();
        Application application = find(id);
        AssessmentSlot slot = findSlot(id, slotId);
        if (slot.getStatus() == SlotStatus.CANCELLED) {
            throw notAllowed("This test or interview was cancelled.", "notes");
        }
        slot.recordOutcome(notes.trim());
        slots.flush();
        Actor who = actor(actor);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("slotId", slotId.toString());
        details.put("kind", slot.getKind().name());
        details.put("scheduledAt", slot.getScheduledAt().toString());
        addEntry(application, TimelineKind.SLOT_OUTCOME, who, null, null, notes.trim(), details);
        audit.record(who, "assessment_slot.outcome_recorded", "assessment_slot", slotId, slotAudit(slot, id));
        return detail(application);
    }

    public ApplicationDetail cancelSlot(UUID id, UUID slotId, Actor actor) {
        TenantContext.require();
        Application application = find(id);
        AssessmentSlot slot = findSlot(id, slotId);
        if (slot.getStatus() != SlotStatus.SCHEDULED) {
            throw notAllowed("Only a scheduled test or interview can be cancelled.", "slotId");
        }
        slot.cancel();
        slots.flush();
        Actor who = actor(actor);
        addEntry(application, TimelineKind.SLOT_CANCELLED, who, null, null, null, slotDetails(slot));
        audit.record(who, "assessment_slot.cancelled", "assessment_slot", slotId, slotAudit(slot, id));
        return detail(application);
    }

    // ------------------------------------------------------------------ admitting

    /**
     * Admits the child of an offered application as a student, in a section of the applied class and year, with the
     * application's parents as guardians. Safe to repeat: once admitted, the same student is returned and no second
     * record is created. The row is locked, so two admissions at the same moment run one after the other.
     */
    public ApplicationDetail admit(UUID id, AdmitRequest form, Actor actor) {
        TenantContext.require();
        Application application = applications.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Application"));
        if (application.getStage() == ApplicationStage.ADMITTED && application.getStudentId() != null) {
            return detail(application);
        }
        if (application.getStage() != ApplicationStage.OFFERED) {
            throw notAllowed("Only an application that has been offered a place can be admitted.", "stage");
        }
        SectionInfo section = academics.section(form.sectionId())
                .orElseThrow(() -> ApiException.badRequest("Pick a section.", "sectionId"));
        if (!section.classId().equals(application.getClassId())) {
            throw ApiException.badRequest("Pick a section of " + classNames().getOrDefault(application.getClassId(),
                    "the class applied for") + ".", "sectionId");
        }
        Gender gender = form.gender() != null ? form.gender() : application.getGender();
        if (gender == null) {
            throw ApiException.badRequest("Choose the child's gender.", "gender");
        }
        LocalDate admissionDate = form.admissionDate() != null ? form.admissionDate() : today();
        List<GuardianFields> family = guardians.findByApplicationId(id).stream()
                .map(g -> new GuardianFields(g.getName(), g.getRelation(), g.getPhone(), g.getEmail(), null,
                        g.isPrimary()))
                .toList();
        CreateStudent student = new CreateStudent(form.admissionNo().trim(), application.getFirstName(),
                application.getLastName(), application.getDateOfBirth(), gender, admissionDate, null, null,
                application.getPreviousSchool(), null, section.id(), form.rollNo(), family);
        checkStudent(student);

        Actor who = actor(actor);
        StudentDetail created = students.createInYear(student, application.getAcademicYearId(), who);
        if (form.gender() != null) {
            application.setGender(gender);
        }
        application.admitted(created.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentId", created.id().toString());
        details.put("admissionNo", created.admissionNo());
        details.put("section", section.label());
        recordStageChange(application, ApplicationStage.OFFERED, who, null, details);
        audit.record(who, "application.admitted", "application", id, details);
        return detail(application);
    }

    // ------------------------------------------------------------------ the public enquiry form

    /** What the public enquiry page shows. The caller has selected the school (see {@link PublicEnquiryService}). */
    @Transactional(readOnly = true)
    public PublicSchoolInfo publicInfo(TenantView school) {
        TenantContext.require();
        List<String> classNames = academicsService.classes().stream().map(ClassView::name).toList();
        List<YearOption> years = new ArrayList<>();
        publicYear(YearChoice.CURRENT).ifPresent(y -> years.add(new YearOption(YearChoice.CURRENT, y.name())));
        publicYear(YearChoice.NEXT).ifPresent(y -> years.add(new YearOption(YearChoice.NEXT, y.name())));
        return new PublicSchoolInfo(school.name(), school.board(), school.city(), classNames, years, CONSENT_VERSION);
    }

    /**
     * Stores an enquiry sent from the public form, with the family's consent, as a new ENQUIRY from the website.
     * The caller has selected the school and checked the rate limits.
     */
    public UUID receiveEnquiry(PublicEnquiry form) {
        UUID tenantId = TenantContext.require();
        if (form.consentVersion() != null && !CONSENT_VERSION.equals(form.consentVersion())) {
            throw ApiException.badRequest("The consent text has changed. Reload the page and try again.", "consent");
        }
        YearInfo year = publicYear(form.academicYear())
                .orElseThrow(() -> ApiException.badRequest("Pick a year from the list.", "academicYear"));
        String wanted = form.className().trim();
        ClassView schoolClass = academicsService.classes().stream()
                .filter(c -> c.name().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Pick a class from the list.", "className"));
        checkDateOfBirth(form.dateOfBirth());
        String phone = Phones.normalize(form.mobile());
        if (phone == null) {
            throw ApiException.badRequest(Phones.MESSAGE, "mobile");
        }
        Application.Details details = new Application.Details(form.childFirstName().trim(),
                blankToNull(form.childLastName()), form.dateOfBirth(), null, null, schoolClass.id(), year.id(),
                ApplicationSource.WEBSITE, null, null);
        Instant now = Instant.now();
        Application application = applications.save(new Application(ApplicationStage.ENQUIRY, details,
                blankToNull(form.message()), CONSENT_VERSION, now));
        saveGuardians(application.getId(), List.of(new CleanGuardian(form.parentName().trim(), form.relation(),
                phone, blankToNull(form.email()), true)));
        Actor website = new Actor(null, null);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("source", ApplicationSource.WEBSITE.name());
        entry.put("className", schoolClass.name());
        entry.put("academicYear", year.name());
        entry.put("publicForm", true);
        addEntry(application, TimelineKind.CREATED, website, null, ApplicationStage.ENQUIRY, null, entry);
        audit.record(website, "application.enquiry_received", "application", application.getId(),
                Map.of("className", schoolClass.name(), "academicYear", year.name()));
        applications.flush();
        events.publishEvent(new EnquiryReceived(tenantId, application.getId(), schoolClass.id(), year.id(), now));
        return application.getId();
    }

    /** The current academic year, or the one after it. */
    private Optional<YearInfo> publicYear(YearChoice choice) {
        Optional<YearInfo> current = academics.currentYear();
        if (choice == YearChoice.CURRENT || current.isEmpty()) {
            return choice == YearChoice.CURRENT ? current : Optional.empty();
        }
        return academics.years().stream()
                .filter(y -> y.startsOn().isAfter(current.get().startsOn()))
                .min(Comparator.comparing(YearInfo::startsOn));
    }

    // ------------------------------------------------------------------ helpers: checks

    private Checked check(ApplicationRequest form, Application existing) {
        LocalDate today = today();
        Map<UUID, String> classNames = classNames();
        if (!classNames.containsKey(form.classId())) {
            throw ApiException.badRequest("Pick a class from this school's list.", "classId");
        }
        YearInfo year = academics.year(form.academicYearId())
                .orElseThrow(() -> ApiException.badRequest("Pick an academic year from this school's list.",
                        "academicYearId"));
        boolean yearChanged = existing == null || !existing.getAcademicYearId().equals(year.id());
        if (yearChanged && year.endsOn().isBefore(today)) {
            throw ApiException.badRequest("Pick the current or a later academic year.", "academicYearId");
        }
        checkDateOfBirth(form.dateOfBirth());
        if (form.assignedToId() != null
                && (existing == null || !form.assignedToId().equals(existing.getAssignedToId()))) {
            requireStaff(form.assignedToId(), "assignedToId");
        }
        if (form.followUpOn() != null && (existing == null || !form.followUpOn().equals(existing.getFollowUpOn()))
                && form.followUpOn().isBefore(today)) {
            throw ApiException.badRequest("Pick today or a later date.", "followUpOn");
        }
        List<CleanGuardian> clean = cleanGuardians(form.guardians());
        Application.Details details = new Application.Details(form.firstName().trim(), blankToNull(form.lastName()),
                form.dateOfBirth(), form.gender(), blankToNull(form.previousSchool()), form.classId(), year.id(),
                form.source(), form.assignedToId(), form.followUpOn());
        return new Checked(details, clean, classNames.get(form.classId()), year.name());
    }

    private static void checkDateOfBirth(LocalDate dateOfBirth) {
        if (dateOfBirth.isBefore(today().minusYears(25))) {
            throw ApiException.badRequest("Check the date of birth.", "dateOfBirth");
        }
    }

    /** Errors name the list entry, such as {@code guardians[1].phone}. The first contact is primary by default. */
    private static List<CleanGuardian> cleanGuardians(List<GuardianInput> fields) {
        long primaries = fields.stream().filter(GuardianInput::primary).count();
        if (primaries > 1) {
            throw ApiException.badRequest("Mark only one parent or guardian as the primary contact.", "guardians");
        }
        List<CleanGuardian> clean = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            GuardianInput f = fields.get(i);
            String phone = Phones.normalize(f.phone());
            if (phone == null) {
                throw ApiException.badRequest(Phones.MESSAGE, "guardians[" + i + "].phone");
            }
            CleanGuardian g = new CleanGuardian(f.name().trim(), f.relation(), phone, blankToNull(f.email()),
                    primaries == 0 ? i == 0 : f.primary());
            if (!keys.add(g.key())) {
                throw ApiException.badRequest("This guardian is listed twice.", "guardians[" + i + "].name");
            }
            clean.add(g);
        }
        return clean;
    }

    private void saveGuardians(UUID applicationId, List<CleanGuardian> list) {
        for (int i = 0; i < list.size(); i++) {
            CleanGuardian g = list.get(i);
            guardians.save(new ApplicationGuardian(applicationId, g.name(), g.relation(), g.phone(), g.email(),
                    g.primary(), i));
        }
    }

    private AssessmentSlot.Plan plan(SlotRequest form) {
        Instant now = Instant.now();
        if (!form.scheduledAt().isAfter(now)) {
            throw ApiException.badRequest("Pick a time in the future.", "scheduledAt");
        }
        if (form.scheduledAt().isAfter(now.plus(366, ChronoUnit.DAYS))) {
            throw ApiException.badRequest("Pick a time within the next year.", "scheduledAt");
        }
        String location = blankToNull(form.location());
        String link = blankToNull(form.meetingLink());
        if (form.mode() == AssessmentMode.IN_PERSON && location == null) {
            throw ApiException.badRequest("Enter where it will take place.", "location");
        }
        if (form.mode() == AssessmentMode.ONLINE && !isMeetingLink(link)) {
            throw ApiException.badRequest("Enter the meeting link, starting with https://.", "meetingLink");
        }
        if (form.interviewerId() != null) {
            requireStaff(form.interviewerId(), "interviewerId");
        }
        return new AssessmentSlot.Plan(form.kind(), form.scheduledAt(), form.mode(), location, link,
                form.interviewerId());
    }

    static boolean isMeetingLink(String link) {
        if (link == null || !link.startsWith("https://") || link.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = URI.create(link);
            return uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void checkOffer(LocalDate on, LocalDate until, String field) {
        if (until != null && until.isBefore(on)) {
            throw ApiException.badRequest("The offer cannot end before the day it is made.", field);
        }
    }

    /** Applies the students module's own form rules (admission number, roll number, guardians) before admitting. */
    private void checkStudent(CreateStudent student) {
        Set<ConstraintViolation<CreateStudent>> violations = validator.validate(student);
        if (violations.isEmpty()) {
            return;
        }
        Map<String, String> errors = new TreeMap<>();
        violations.forEach(v -> errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        throw new ApiException(HttpStatus.BAD_REQUEST, "Check the form", "Some fields need attention.", errors);
    }

    private void requireStaff(UUID userId, String field) {
        boolean ok = users.findByIdWithRoles(userId)
                .filter(UserAccount::isActive)
                .filter(AdmissionsService::isStaff)
                .isPresent();
        if (!ok) {
            throw ApiException.badRequest("Pick a staff member from this school.", field);
        }
    }

    private static boolean isStaff(UserAccount user) {
        return user.roleCodes().stream()
                .anyMatch(code -> !RoleCatalog.PARENT.equals(code) && !RoleCatalog.STUDENT.equals(code));
    }

    private static void requireOpen(Application application) {
        if (application.getStage().isFinal()) {
            throw notAllowed("This application is closed (" + label(application.getStage()).toLowerCase(Locale.ROOT)
                    + ") and cannot be changed.", "stage");
        }
    }

    private Application find(UUID id) {
        return applications.findById(id).orElseThrow(() -> ApiException.notFound("Application"));
    }

    private AssessmentSlot findSlot(UUID applicationId, UUID slotId) {
        return slots.findByIdAndApplicationId(slotId, applicationId)
                .orElseThrow(() -> ApiException.notFound("Test or interview"));
    }

    // ------------------------------------------------------------------ helpers: recording

    private void changeStage(Application application, ApplicationStage to, Actor who, String note,
            Map<String, Object> details) {
        ApplicationStage from = application.getStage();
        application.moveTo(to);
        recordStageChange(application, from, who, note, details);
    }

    /** Writes the timeline entry, the audit event and the event for other modules for a stage change just made. */
    private void recordStageChange(Application application, ApplicationStage from, Actor who, String note,
            Map<String, Object> details) {
        applications.flush();
        ApplicationStage to = application.getStage();
        addEntry(application, TimelineKind.STAGE_CHANGED, who, from, to, note, details);
        if (to != ApplicationStage.ADMITTED) {
            audit.record(who, "application.stage_changed", "application", application.getId(),
                    Map.of("from", from.name(), "to", to.name()));
        }
        events.publishEvent(new ApplicationStageChanged(TenantContext.require(), application.getId(), from, to,
                who.id(), application.getStudentId(), application.getStageChangedAt()));
    }

    private void addEntry(Application application, TimelineKind kind, Actor who, ApplicationStage from,
            ApplicationStage to, String note, Map<String, ?> details) {
        timeline.save(new TimelineEntry(application.getId(), kind, who.id(), who.name(), from, to, note,
                json.writeValueAsString(details)));
    }

    private Map<String, Object> slotDetails(AssessmentSlot slot) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("slotId", slot.getId().toString());
        details.put("kind", slot.getKind().name());
        details.put("scheduledAt", slot.getScheduledAt().toString());
        details.put("mode", slot.getMode().name());
        if (slot.getLocation() != null) {
            details.put("location", slot.getLocation());
        }
        if (slot.getInterviewerId() != null) {
            staffNames(List.of(slot.getInterviewerId())).values().stream().findFirst()
                    .ifPresent(name -> details.put("interviewer", name));
        }
        return details;
    }

    private static Map<String, Object> slotAudit(AssessmentSlot slot, UUID applicationId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("applicationId", applicationId.toString());
        details.put("kind", slot.getKind().name());
        details.put("scheduledAt", slot.getScheduledAt().toString());
        return details;
    }

    private static Actor actor(Actor actor) {
        return actor != null ? actor : new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(null));
    }

    private static ApiException notAllowed(String detail, String field) {
        return new ApiException(HttpStatus.CONFLICT, "Not allowed", detail, Map.of(field, detail));
    }

    // ------------------------------------------------------------------ helpers: views

    private Where where(ApplicationQuery query) {
        StringBuilder where = new StringBuilder(" from Application a where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (query.academicYearId() != null) {
            where.append(" and a.academicYearId = :year");
            params.put("year", query.academicYearId());
        }
        if (query.classId() != null) {
            where.append(" and a.classId = :schoolClass");
            params.put("schoolClass", query.classId());
        }
        if (query.source() != null) {
            where.append(" and a.source = :source");
            params.put("source", query.source());
        }
        String search = query.search() == null ? "" : query.search().trim().toLowerCase(Locale.ROOT);
        if (!search.isEmpty()) {
            String digits = phoneDigits(search);
            where.append(" and (lower(concat(a.firstName, ' ', coalesce(a.lastName, ''))) like :q escape '\\'"
                    + " or exists (select g.id from ApplicationGuardian g where g.applicationId = a.id"
                    + " and (lower(g.name) like :q escape '\\'");
            if (digits != null) {
                where.append(" or g.phone like :phone");
                params.put("phone", "%" + digits + "%");
            }
            where.append(")))");
            params.put("q", "%" + escapeLike(search) + "%");
        }
        return new Where(where.toString(), params);
    }

    private Map<ApplicationStage, Long> stageCounts(Where where) {
        TypedQuery<Object[]> query = entityManager.createQuery(
                "select a.stage, count(a)" + where.jpql() + " group by a.stage", Object[].class);
        where.params().forEach(query::setParameter);
        Map<ApplicationStage, Long> counts = new LinkedHashMap<>();
        for (ApplicationStage stage : ApplicationStage.values()) {
            counts.put(stage, 0L);
        }
        for (Object[] row : query.getResultList()) {
            counts.put((ApplicationStage) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** Rows in the given order, with names looked up once for the whole list. */
    private List<ApplicationRow> rows(List<Application> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = list.stream().map(Application::getId).toList();
        Map<UUID, String> classNames = classNames();
        Map<UUID, String> yearNames = yearNames();
        Map<UUID, ApplicationGuardian> contacts = guardians.findPrimaryIn(ids).stream()
                .collect(Collectors.toMap(ApplicationGuardian::getApplicationId, Function.identity(), (a, b) -> a));
        Map<UUID, String> staffNames = staffNames(list.stream().map(Application::getAssignedToId).toList());
        Map<UUID, Instant> nextSlots = new HashMap<>();
        for (Object[] row : slots.nextSlots(ids, SlotStatus.SCHEDULED, Instant.now())) {
            nextSlots.put((UUID) row[0], (Instant) row[1]);
        }
        LocalDate today = today();
        return list.stream().map(a -> {
            ApplicationGuardian contact = contacts.get(a.getId());
            return new ApplicationRow(a.getId(), a.childName(), a.getClassId(), classNames.get(a.getClassId()),
                    a.getAcademicYearId(), yearNames.get(a.getAcademicYearId()), a.getStage(), a.getStageChangedAt(),
                    daysSince(a.getStageChangedAt(), today), a.getFollowUpOn(), a.getSource(),
                    contact == null ? null : contact.getName(), contact == null ? null : maskPhone(contact.getPhone()),
                    a.getAssignedToId() == null ? null : staffNames.get(a.getAssignedToId()),
                    nextSlots.get(a.getId()), a.getCreatedAt(), List.copyOf(a.getStage().next()));
        }).toList();
    }

    private ApplicationDetail detail(Application a) {
        UUID id = a.getId();
        List<AssessmentSlot> slotList = slots.findByApplicationId(id);
        List<UUID> staffIds = new ArrayList<>(slotList.stream().map(AssessmentSlot::getInterviewerId).toList());
        staffIds.add(a.getAssignedToId());
        Map<UUID, String> staffNames = staffNames(staffIds);
        List<GuardianView> contacts = guardians.findByApplicationId(id).stream()
                .map(g -> new GuardianView(g.getName(), g.getRelation(), g.getPhone(), g.getEmail(), g.isPrimary()))
                .toList();
        List<SlotView> slotViews = slotList.stream()
                .map(s -> new SlotView(s.getId(), s.getKind(), s.getScheduledAt(), s.getMode(), s.getLocation(),
                        s.getMeetingLink(), staffRef(s.getInterviewerId(), staffNames), s.getStatus(),
                        s.getOutcomeNotes()))
                .toList();
        List<TimelineView> entries = timeline.findByApplicationId(id).stream()
                .map(t -> new TimelineView(t.getId(), t.getAt(), t.getKind(), t.getActorName(), t.getFromStage(),
                        t.getToStage(), t.getNote(), parseDetails(t.getDetails())))
                .toList();
        FeeView fee = a.getFeeStatus() == null ? null
                : new FeeView(a.getFeeStatus(), a.getFeeAmountPaise(), a.getFeeMethod(), a.getFeeReference(),
                        a.getFeeOn());
        OfferView offer = a.getOfferedOn() == null ? null : new OfferView(a.getOfferedOn(), a.getOfferValidUntil());
        String yearName = academics.year(a.getAcademicYearId()).map(YearInfo::name).orElse(null);
        return new ApplicationDetail(id, a.getStage(), a.getStageChangedAt(), daysSince(a.getStageChangedAt(), today()),
                List.copyOf(a.getStage().next()), a.getFirstName(), a.getLastName(), a.childName(),
                a.getDateOfBirth(), a.getGender(), a.getPreviousSchool(), a.getClassId(),
                classNames().get(a.getClassId()), a.getAcademicYearId(), yearName, a.getSource(),
                staffRef(a.getAssignedToId(), staffNames), a.getFollowUpOn(), a.getMessage(), a.getConsentVersion(),
                a.getConsentAt(), contacts, fee, offer, a.getStudentId(), slotViews, entries, a.getCreatedAt());
    }

    private Map<UUID, String> classNames() {
        return academicsService.classes().stream().collect(Collectors.toMap(ClassView::id, ClassView::name));
    }

    private Map<UUID, String> yearNames() {
        return academics.years().stream().collect(Collectors.toMap(YearInfo::id, YearInfo::name));
    }

    private Map<UUID, String> staffNames(List<UUID> ids) {
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        return wanted.isEmpty() ? Map.of() : users.findAllById(wanted).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getName));
    }

    private static StaffRef staffRef(UUID id, Map<UUID, String> names) {
        return id == null || !names.containsKey(id) ? null : new StaffRef(id, names.get(id));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDetails(String details) {
        return details == null ? Map.of() : json.readValue(details, Map.class);
    }

    // ------------------------------------------------------------------ small helpers

    static LocalDate today() {
        return LocalDate.now(SCHOOL_ZONE);
    }

    static Instant startOf(LocalDate day) {
        return day.atStartOfDay(SCHOOL_ZONE).toInstant();
    }

    static long daysSince(Instant at, LocalDate today) {
        return Math.max(0, ChronoUnit.DAYS.between(at.atZone(SCHOOL_ZONE).toLocalDate(), today));
    }

    /** "9876500001" → "98•••••001": enough to recognise a number in a list without exposing it. */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) {
            return phone;
        }
        return phone.substring(0, 2) + "•".repeat(phone.length() - 5) + phone.substring(phone.length() - 3);
    }

    /** Digits to look for in mobile numbers when the search looks like a phone number, else null. */
    static String phoneDigits(String search) {
        if (!search.matches("^\\+?[0-9][0-9 -]*$")) {
            return null;
        }
        String digits = search.replaceAll("[^0-9]", "");
        if (digits.length() > 10 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits.length() >= 4 ? digits : null;
    }

    static String label(ApplicationStage stage) {
        return switch (stage) {
            case ENQUIRY -> "Enquiry";
            case APPLICATION -> "Application";
            case ASSESSMENT -> "Test or interview";
            case OFFERED -> "Offered";
            case ADMITTED -> "Admitted";
            case REJECTED -> "Rejected";
            case WITHDRAWN -> "Withdrawn";
        };
    }

    private static void compare(List<String> changed, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(field);
        }
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
