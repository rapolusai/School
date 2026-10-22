package com.akshara.health;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.health.HealthForms.ConsentForm;
import com.akshara.health.HealthForms.GrowthForm;
import com.akshara.health.HealthForms.ItemForm;
import com.akshara.health.HealthForms.RecordForm;
import com.akshara.health.HealthPeople.Pupil;
import com.akshara.health.HealthViews.ConsentView;
import com.akshara.health.HealthViews.EmergencyContact;
import com.akshara.health.HealthViews.GrowthView;
import com.akshara.health.HealthViews.ItemView;
import com.akshara.health.HealthViews.MedicalRecordView;
import com.akshara.health.HealthViews.PersonRef;
import com.akshara.health.HealthViews.VisitView;
import com.akshara.shared.ApiException;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;
import com.akshara.students.Phones;

/**
 * Students' medical records: the head (blood group, doctor, insurance, consent), the items (allergies, conditions,
 * medication, vaccinations) and the growth measurements. Staff with health.read see the record of every student when
 * they also hold attendance.manage (the Principal and School Admin), otherwise only of the sections they are class
 * teacher of; health.manage edits. Every read by staff is audited with the screen it came from. Parents keep their
 * child's allergies, conditions and consent from the family app.
 */
@Service
@Transactional
public class MedicalRecordService {

    static final String ENTITY = "medical_record";
    /** The kinds a parent may add, change and remove. */
    static final Set<ItemKind> PARENT_KINDS = Set.of(ItemKind.ALLERGY, ItemKind.CONDITION);

    /** Which students a staff member may read: every one, or those of the sections they lead. */
    record Scope(boolean wholeSchool, Set<UUID> sectionIds) {

        boolean covers(Pupil p) {
            return wholeSchool || (p.sectionId() != null && sectionIds.contains(p.sectionId()));
        }
    }

    private final MedicalRecordRepository records;
    private final RecordItemRepository items;
    private final GrowthMeasurementRepository growth;
    private final InfirmaryVisitRepository visits;
    private final HealthPeople people;
    private final InfirmaryService infirmary;
    private final AuditService audit;

    MedicalRecordService(MedicalRecordRepository records, RecordItemRepository items,
            GrowthMeasurementRepository growth, InfirmaryVisitRepository visits, HealthPeople people,
            InfirmaryService infirmary, AuditService audit) {
        this.records = records;
        this.items = items;
        this.growth = growth;
        this.visits = visits;
        this.people = people;
        this.infirmary = infirmary;
        this.audit = audit;
    }

    /* ------------------------------------------------------------------ staff */

    /** The signed-in staff member's scope from their permissions and class-teacher sections. */
    Scope scope(UUID userId, Set<String> permissions) {
        boolean whole = permissions.contains(Permissions.HEALTH_MANAGE)
                || permissions.contains(Permissions.ATTENDANCE_MANAGE);
        return new Scope(whole, whole ? Set.of() : people.sectionsLedBy(userId));
    }

    @Transactional(readOnly = true)
    public List<PersonRef> searchStudents(String q) {
        TenantContext.require();
        return people.searchStudents(q, 20);
    }

    /**
     * The record as staff see it, audited as a read ({@code medical_record.viewed}) with the screen it was opened
     * from. 404 for students outside the reader's scope, as for another school's.
     */
    public MedicalRecordView view(UUID studentId, String screen, Scope scope, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requirePupil(studentId);
        if (!scope.covers(pupil)) {
            throw ApiException.notFound("Student");
        }
        audit.record(actor, "medical_record.viewed", ENTITY, recordIdOf(studentId), Map.of("studentId",
                studentId.toString(), "student", pupil.fullName(), "screen", HealthText.clean(screen) == null
                        ? "api" : screen.trim()));
        return assemble(pupil);
    }

    public MedicalRecordView saveHead(UUID studentId, RecordForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requirePupil(studentId);
        MedicalRecord record = recordOrNew(studentId);
        record.apply(new MedicalRecord.Details(HealthText.clean(form.bloodGroup()),
                HealthText.clean(form.doctorName()), phone(form.doctorPhone(), "doctorPhone"),
                HealthText.clean(form.insuranceProvider()), HealthText.clean(form.insurancePolicyNo()),
                HealthText.clean(form.notes())));
        records.save(record);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("student", pupil.fullName());
        details.put("bloodGroup", record.getBloodGroup());
        details.put("insurancePolicyNo", HealthText.maskNumber(record.getInsurancePolicyNo()));
        audit.record(actor, "medical_record.updated", ENTITY, record.getId(), details);
        records.flush();
        return assemble(pupil);
    }

    public MedicalRecordView saveConsent(UUID studentId, ConsentForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requirePupil(studentId);
        return consent(pupil, form, actor);
    }

    public ItemView addItem(UUID studentId, ItemForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requirePupil(studentId);
        return item(pupil, form, ItemSource.STAFF, actor);
    }

    public ItemView updateItem(UUID studentId, UUID itemId, ItemForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requirePupil(studentId);
        RecordItem item = requireItem(studentId, itemId);
        apply(item, form, ItemSource.STAFF, actor);
        audit.record(actor, "medical_record_item.updated", ENTITY, recordIdOf(studentId),
                itemDetails(pupil, item));
        items.flush();
        return view(item);
    }

    public void removeItem(UUID studentId, UUID itemId, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requirePupil(studentId);
        RecordItem item = requireItem(studentId, itemId);
        items.delete(item);
        audit.record(actor, "medical_record_item.removed", ENTITY, recordIdOf(studentId), itemDetails(pupil, item));
        items.flush();
    }

    /** Adds a measurement for a day, replacing one already taken that day. */
    public GrowthView addGrowth(UUID studentId, GrowthForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requirePupil(studentId);
        if (form.heightCm() == null && form.weightKg() == null) {
            throw ApiException.badRequest("Give the height or the weight.", "heightCm");
        }
        if (form.measuredOn().isAfter(HealthText.today())) {
            throw ApiException.badRequest("The date cannot be in the future.", "measuredOn");
        }
        recordOrNew(studentId);
        growth.findByStudentIdAndMeasuredOn(studentId, form.measuredOn()).ifPresent(growth::delete);
        growth.flush();
        GrowthMeasurement m = growth.save(new GrowthMeasurement(studentId, form.measuredOn(), scale(form.heightCm()),
                scale(form.weightKg()), actor.name(), Instant.now()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("student", pupil.fullName());
        details.put("measuredOn", form.measuredOn().toString());
        details.put("heightCm", m.getHeightCm());
        details.put("weightKg", m.getWeightKg());
        audit.record(actor, "growth_measurement.added", ENTITY, recordIdOf(studentId), details);
        growth.flush();
        return view(m);
    }

    public void removeGrowth(UUID studentId, UUID measurementId, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requirePupil(studentId);
        GrowthMeasurement m = growth.findById(measurementId).filter(g -> g.getStudentId().equals(studentId))
                .orElseThrow(() -> ApiException.notFound("Measurement"));
        growth.delete(m);
        audit.record(actor, "growth_measurement.removed", ENTITY, recordIdOf(studentId), Map.of("student",
                pupil.fullName(), "measuredOn", m.getMeasuredOn().toString()));
        growth.flush();
    }

    /* ------------------------------------------------------------------ parents */

    /** A parent's view of their child's record (not audited: it is their own child's data). */
    @Transactional(readOnly = true)
    public MedicalRecordView childView(UUID parentUserId, UUID studentId) {
        TenantContext.require();
        return assemble(people.requireChild(parentUserId, studentId));
    }

    public MedicalRecordView childConsent(UUID parentUserId, UUID studentId, ConsentForm form, Actor actor) {
        TenantContext.require();
        return consent(people.requireChild(parentUserId, studentId), form, actor);
    }

    public ItemView childAddItem(UUID parentUserId, UUID studentId, ItemForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        parentKind(form.kind());
        return item(pupil, form, ItemSource.PARENT, actor);
    }

    public ItemView childUpdateItem(UUID parentUserId, UUID studentId, UUID itemId, ItemForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        RecordItem item = requireItem(studentId, itemId);
        parentKind(item.getKind());
        parentKind(form.kind());
        apply(item, form, ItemSource.PARENT, actor);
        audit.record(actor, "medical_record_item.updated", ENTITY, recordIdOf(studentId), itemDetails(pupil, item));
        items.flush();
        return view(item);
    }

    public void childRemoveItem(UUID parentUserId, UUID studentId, UUID itemId, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        RecordItem item = requireItem(studentId, itemId);
        parentKind(item.getKind());
        items.delete(item);
        audit.record(actor, "medical_record_item.removed", ENTITY, recordIdOf(studentId), itemDetails(pupil, item));
        items.flush();
    }

    /* ------------------------------------------------------------------ helpers */

    private MedicalRecordView consent(Pupil pupil, ConsentForm form, Actor actor) {
        MedicalRecord record = recordOrNew(pupil.id());
        record.consent(form.firstAid(), form.hospital(), actor.name(), Instant.now());
        records.save(record);
        audit.record(actor, "medical_record.consent_updated", ENTITY, record.getId(), Map.of("student",
                pupil.fullName(), "firstAid", form.firstAid(), "hospital", form.hospital()));
        records.flush();
        return assemble(pupil);
    }

    private ItemView item(Pupil pupil, ItemForm form, ItemSource source, Actor actor) {
        recordOrNew(pupil.id());
        RecordItem item = new RecordItem(pupil.id(), form.kind(), source, actor.name(), Instant.now());
        apply(item, form, source, actor);
        items.save(item);
        audit.record(actor, "medical_record_item.added", ENTITY, recordIdOf(pupil.id()), itemDetails(pupil, item));
        items.flush();
        return view(item);
    }

    private void apply(RecordItem item, ItemForm form, ItemSource source, Actor actor) {
        if (form.kind() != item.getKind()) {
            throw ApiException.badRequest("The kind of an entry cannot change.", "kind");
        }
        if (item.getKind() == ItemKind.ALLERGY && form.severity() == null) {
            throw ApiException.badRequest("Say how severe the allergy is.", "severity");
        }
        if (item.getKind() == ItemKind.VACCINATION && form.itemDate() == null) {
            throw ApiException.badRequest("Give the date of the vaccination.", "itemDate");
        }
        item.apply(form.name().trim(), HealthText.clean(form.detail()), form.severity(), form.itemDate(), source,
                actor.name());
    }

    private static void parentKind(ItemKind kind) {
        if (!PARENT_KINDS.contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Check the form",
                    "Parents keep allergies and conditions; the school keeps medication and vaccinations.",
                    Map.of("kind", "Parents keep allergies and conditions."));
        }
    }

    private RecordItem requireItem(UUID studentId, UUID itemId) {
        return items.findById(itemId).filter(i -> i.getStudentId().equals(studentId))
                .orElseThrow(() -> ApiException.notFound("Entry"));
    }

    private MedicalRecord recordOrNew(UUID studentId) {
        return records.findByStudentId(studentId)
                .orElseGet(() -> records.saveAndFlush(new MedicalRecord(studentId, Instant.now())));
    }

    private UUID recordIdOf(UUID studentId) {
        return records.findByStudentId(studentId).map(MedicalRecord::getId).orElse(null);
    }

    static String phone(String raw, String field) {
        String cleaned = HealthText.clean(raw);
        if (cleaned == null) {
            return null;
        }
        String normalized = Phones.normalize(cleaned);
        if (normalized == null) {
            throw ApiException.badRequest(Phones.MESSAGE, field);
        }
        return normalized;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(1, java.math.RoundingMode.HALF_UP);
    }

    private static Map<String, Object> itemDetails(Pupil pupil, RecordItem item) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("student", pupil.fullName());
        details.put("kind", item.getKind().name());
        details.put("name", item.getName());
        if (item.getSeverity() != null) {
            details.put("severity", item.getSeverity().name());
        }
        details.put("source", item.getSource().name());
        return details;
    }

    MedicalRecordView assemble(Pupil pupil) {
        Optional<MedicalRecord> record = records.findByStudentId(pupil.id());
        List<ItemView> all = items.findByStudentIdOrderByKindAscNameAsc(pupil.id()).stream().map(this::view).toList();
        List<GrowthMeasurement> measurements = growth.findByStudentIdOrderByMeasuredOnAsc(pupil.id());
        LocalDate dateOfBirth = people.dateOfBirth(pupil.id());
        GrowthChart.Series chart = GrowthChart.of(dateOfBirth, measurements.stream()
                .map(m -> new GrowthChart.Point(m.getMeasuredOn(), 0, m.getHeightCm(), m.getWeightKg())).toList());
        EmergencyContact contact = people.primaryGuardian(pupil.id())
                .map(g -> new EmergencyContact(g.guardianId(), g.name(), g.phone(), HealthText.maskPhone(g.phone()),
                        g.email()))
                .orElse(null);
        List<VisitView> recent = infirmary.views(visits.ofStudent(pupil.id()));
        MedicalRecord r = record.orElse(null);
        return new MedicalRecordView(pupil.id(), pupil.fullName(), pupil.admissionNo(), pupil.className(),
                dateOfBirth, r != null, r == null ? null : r.getBloodGroup(), r == null ? null : r.getDoctorName(),
                r == null ? null : r.getDoctorPhone(), r == null ? null : r.getInsuranceProvider(),
                r == null ? null : HealthText.maskNumber(r.getInsurancePolicyNo()), r == null ? null : r.getNotes(),
                new ConsentView(r != null && r.isConsentFirstAid(), r != null && r.isConsentHospital(),
                        r == null ? null : r.getConsentByName(), r == null ? null : r.getConsentAt()),
                contact, ofKind(all, ItemKind.ALLERGY), ofKind(all, ItemKind.CONDITION),
                ofKind(all, ItemKind.MEDICATION), ofKind(all, ItemKind.VACCINATION),
                measurements.stream().map(MedicalRecordService::view).toList(), chart, recent,
                r == null ? null : r.getUpdatedAt());
    }

    private static List<ItemView> ofKind(List<ItemView> all, ItemKind kind) {
        return all.stream().filter(i -> i.kind() == kind).toList();
    }

    private ItemView view(RecordItem i) {
        return new ItemView(i.getId(), i.getKind(), i.getName(), i.getDetail(), i.getSeverity(), i.getItemDate(),
                i.getSource(), i.getAddedByName());
    }

    private static GrowthView view(GrowthMeasurement m) {
        return new GrowthView(m.getId(), m.getMeasuredOn(), m.getHeightCm(), m.getWeightKg(), m.getRecordedByName());
    }
}
