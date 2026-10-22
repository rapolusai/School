package com.akshara.frontoffice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileOwner;
import com.akshara.files.FileStore;
import com.akshara.files.IncomingFile;
import com.akshara.files.StoredFileInfo;
import com.akshara.frontoffice.FrontOfficeForms.PickupPersonForm;
import com.akshara.frontoffice.FrontOfficePeople.Pupil;
import com.akshara.frontoffice.FrontOfficeViews.FamilyPickup;
import com.akshara.frontoffice.FrontOfficeViews.GatePassView;
import com.akshara.frontoffice.FrontOfficeViews.PickupPersonView;
import com.akshara.frontoffice.FrontOfficeViews.StudentPickupPersons;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The people a parent authorises to collect their child routinely. Parents keep the list (name, relation, phone,
 * optional photo); the gate and the office see it with the phone masked. Audited.
 */
@Service
@Transactional
public class PickupPersonService {

    static final String PICKUP_PHOTO = "pickup_photo";
    static final int MAX_PERSONS = 5;

    private final PickupPersonRepository persons;
    private final GatePassService gatePasses;
    private final FrontOfficePeople people;
    private final FileStore files;
    private final AuditService audit;

    PickupPersonService(PickupPersonRepository persons, GatePassService gatePasses, FrontOfficePeople people,
            FileStore files, AuditService audit) {
        this.persons = persons;
        this.gatePasses = gatePasses;
        this.people = people;
        this.files = files;
        this.audit = audit;
    }

    /** A parent's view of one child: their passes and pickup persons. */
    @Transactional(readOnly = true)
    public FamilyPickup family(UUID parentUserId, UUID studentId) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        List<GatePassView> passes = gatePasses.ofChild(parentUserId, studentId);
        boolean canRequest = pupil.active() && passes.stream().noneMatch(p -> p.status().open());
        return new FamilyPickup(studentId, pupil.fullName(), pupil.className(), canRequest, passes,
                persons.findByStudentIdOrderByNameAsc(studentId).stream().map(p -> view(p, pupil)).toList());
    }

    /** For the gate and the office: a student's authorised pickup persons. */
    @Transactional(readOnly = true)
    public StudentPickupPersons ofStudent(UUID studentId) {
        TenantContext.require();
        Pupil pupil = people.requirePupil(studentId);
        return new StudentPickupPersons(studentId, pupil.fullName(), pupil.className(),
                persons.findByStudentIdOrderByNameAsc(studentId).stream().map(p -> view(p, pupil)).toList());
    }

    public PickupPersonView add(UUID parentUserId, UUID studentId, PickupPersonForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        if (persons.countByStudentId(studentId) >= MAX_PERSONS) {
            throw new ApiException(HttpStatus.CONFLICT, "Too many", "You can authorise at most " + MAX_PERSONS
                    + " people. Remove one first.");
        }
        PickupPerson person = persons.save(new PickupPerson(studentId, form.name().trim(), form.relation().trim(),
                VisitorService.phone(form.phone(), "phone"), actor, Instant.now()));
        audit.record(actor, "pickup_person.added", "pickup_person", person.getId(), Map.of("student",
                pupil.fullName(), "name", person.getName(), "relation", person.getRelation()));
        persons.flush();
        return view(person, pupil);
    }

    public PickupPersonView update(UUID parentUserId, UUID studentId, UUID id, PickupPersonForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        PickupPerson person = require(studentId, id);
        person.update(form.name().trim(), form.relation().trim(), VisitorService.phone(form.phone(), "phone"));
        audit.record(actor, "pickup_person.updated", "pickup_person", id, Map.of("student", pupil.fullName(),
                "name", person.getName(), "relation", person.getRelation()));
        persons.flush();
        return view(person, pupil);
    }

    public void remove(UUID parentUserId, UUID studentId, UUID id, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        PickupPerson person = require(studentId, id);
        files.deleteAll(new FileOwner(VisitorService.MODULE, PICKUP_PHOTO, id));
        persons.delete(person);
        audit.record(actor, "pickup_person.removed", "pickup_person", id, Map.of("student", pupil.fullName(),
                "name", person.getName()));
        persons.flush();
    }

    public PickupPersonView addPhoto(UUID parentUserId, UUID studentId, UUID id, IncomingFile photo, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        PickupPerson person = require(studentId, id);
        if (!photo.type().mediaType().startsWith("image/")) {
            throw ApiException.badRequest("Upload a JPEG, PNG or WEBP photo.", "file");
        }
        FileOwner owner = new FileOwner(VisitorService.MODULE, PICKUP_PHOTO, id);
        // Replacing the old photo clears the persistence context, so the person is loaded again afterwards.
        files.deleteAll(owner);
        StoredFileInfo stored = files.put(owner, photo, actor.id());
        person = require(studentId, id);
        person.setPhotoFileId(stored.id());
        audit.record(actor, "pickup_person.photo_added", "pickup_person", id, Map.of("student", pupil.fullName(),
                "name", person.getName()));
        persons.flush();
        return view(person, pupil);
    }

    /** The students (of a parent's children, or any) whose pickup persons a photo belongs to; for file access. */
    @Transactional(readOnly = true)
    public boolean belongsToChildOf(UUID personId, UUID parentUserId) {
        Set<UUID> children = people.childrenOf(parentUserId);
        return persons.findById(personId).map(p -> children.contains(p.getStudentId())).orElse(false);
    }

    private PickupPerson require(UUID studentId, UUID id) {
        return persons.findById(id).filter(p -> p.getStudentId().equals(studentId))
                .orElseThrow(() -> ApiException.notFound("Pickup person"));
    }

    static PickupPersonView view(PickupPerson p, Pupil pupil) {
        return new PickupPersonView(p.getId(), p.getStudentId(), pupil == null ? null : pupil.fullName(),
                p.getName(), p.getRelation(), FrontOfficeText.maskPhone(p.getPhone()), p.getPhotoFileId(),
                p.getAddedByName(), p.getCreatedAt());
    }

    static List<PickupPersonView> views(List<PickupPerson> rows, Map<UUID, Pupil> pupils) {
        return rows.stream().map(p -> view(p, pupils.get(p.getStudentId()))).toList();
    }
}
