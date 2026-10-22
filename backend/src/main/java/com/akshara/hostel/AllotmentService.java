package com.akshara.hostel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.hostel.HostelForms.AllotForm;
import com.akshara.hostel.HostelForms.MoveForm;
import com.akshara.hostel.HostelForms.VacateForm;
import com.akshara.hostel.HostelPeople.Pupil;
import com.akshara.hostel.HostelViews.AllotmentView;
import com.akshara.hostel.HostelViews.BoarderView;
import com.akshara.hostel.HostelViews.StudentBoarding;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster.GuardianContact;

/**
 * Allotting beds: a student into a free bed with a start date and the guardian's consent, vacating with a reason,
 * and moving to another bed (which keeps the old allotment as history). Each allotment and vacation publishes
 * {@link HostelAllotted} / {@link HostelVacated} for the fees module. Needs hostel.manage.
 */
@Service
@Transactional
public class AllotmentService {

    private final AllotmentRepository allotments;
    private final HostelRepository hostels;
    private final BlockRepository blocks;
    private final RoomRepository rooms;
    private final BedRepository beds;
    private final HostelPeople people;
    private final HostelScope scope;
    private final ApplicationEventPublisher events;
    private final AuditService audit;

    AllotmentService(AllotmentRepository allotments, HostelRepository hostels, BlockRepository blocks,
            RoomRepository rooms, BedRepository beds, HostelPeople people, HostelScope scope,
            ApplicationEventPublisher events, AuditService audit) {
        this.allotments = allotments;
        this.hostels = hostels;
        this.blocks = blocks;
        this.rooms = rooms;
        this.beds = beds;
        this.people = people;
        this.scope = scope;
        this.events = events;
        this.audit = audit;
    }

    /* ------------------------------------------------------------------ writing */

    public AllotmentView allot(AllotForm form, Actor actor) {
        TenantContext.require();
        Pupil pupil = people.requireActivePupil(form.studentId(), "studentId");
        Bed bed = beds.findById(form.bedId()).orElseThrow(() -> ApiException.badRequest("Pick a bed of this "
                + "school's hostels.", "bedId"));
        LocalDate today = HostelText.today();
        if (!HostelRules.allotmentDateOk(form.fromDate(), today)) {
            throw ApiException.badRequest("The start date must be within the last year or the next four months.",
                    "fromDate");
        }
        allotments.activeOfStudent(pupil.id()).ifPresent(a -> {
            throw new ApiException(HttpStatus.CONFLICT, "Already a boarder", pupil.fullName()
                    + " already has a bed; move or vacate it instead.");
        });
        Room room = requireFreeBed(bed);
        Hostel hostel = hostels.findById(bed.getHostelId()).orElseThrow(() -> ApiException.notFound("Hostel"));
        Instant now = Instant.now();
        Allotment a = allotments.save(new Allotment(pupil.id(), bed.getHostelId(), bed.getRoomId(), bed.getId(),
                form.fromDate(), Boolean.TRUE.equals(form.guardianConsent()), actor, now));
        events.publishEvent(new HostelAllotted(TenantContext.require(), a.getId(), null, pupil.id(), hostel.getId(),
                hostel.getName(), room.getId(), room.getNumber(), room.getMonthlyFeePaise(), a.getFromDate()));
        audit.record(actor, "hostel_allotment.allotted", "hostel_allotment", a.getId(),
                details(a, pupil, hostel, room, bed));
        allotments.flush();
        return views(List.of(a)).get(0);
    }

    public AllotmentView vacate(UUID id, VacateForm form, Actor actor) {
        TenantContext.require();
        Allotment a = allotments.lock(id).orElseThrow(() -> ApiException.notFound("Allotment"));
        if (a.getStatus() != AllotmentStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Already vacated", "This bed was already vacated.");
        }
        LocalDate toDate = form.toDate() == null ? HostelText.today() : form.toDate();
        if (toDate.isBefore(a.getFromDate())) {
            throw ApiException.badRequest("The end date cannot be before the start date.", "toDate");
        }
        a.vacate(toDate, form.reason().trim(), actor);
        Pupil pupil = people.requirePupil(a.getStudentId());
        Hostel hostel = hostels.findById(a.getHostelId()).orElseThrow(() -> ApiException.notFound("Hostel"));
        Room room = rooms.findById(a.getRoomId()).orElseThrow(() -> ApiException.notFound("Room"));
        Bed bed = beds.findById(a.getBedId()).orElseThrow(() -> ApiException.notFound("Bed"));
        events.publishEvent(new HostelVacated(TenantContext.require(), a.getId(), a.getStudentId(), a.getHostelId(),
                a.getRoomId(), toDate, a.getVacateReason()));
        Map<String, Object> d = details(a, pupil, hostel, room, bed);
        d.put("reason", a.getVacateReason());
        audit.record(actor, "hostel_allotment.vacated", "hostel_allotment", id, d);
        allotments.flush();
        return views(List.of(a)).get(0);
    }

    /** Moves the boarder to another bed: the old allotment ends today (reason ROOM_CHANGE), a new one starts. */
    public AllotmentView move(UUID id, MoveForm form, Actor actor) {
        TenantContext.require();
        Allotment old = allotments.lock(id).orElseThrow(() -> ApiException.notFound("Allotment"));
        if (old.getStatus() != AllotmentStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Already vacated", "This bed was already vacated.");
        }
        if (old.getBedId().equals(form.bedId())) {
            throw ApiException.badRequest("The boarder is already in this bed.", "bedId");
        }
        Bed bed = beds.findById(form.bedId()).orElseThrow(() -> ApiException.badRequest("Pick a bed of this "
                + "school's hostels.", "bedId"));
        LocalDate from = form.fromDate() == null ? HostelText.today() : form.fromDate();
        if (from.isBefore(old.getFromDate()) || !HostelRules.allotmentDateOk(from, HostelText.today())) {
            throw ApiException.badRequest("The move date must be after the current allotment started and within "
                    + "the next four months.", "fromDate");
        }
        Room room = requireFreeBed(bed);
        Hostel hostel = hostels.findById(bed.getHostelId()).orElseThrow(() -> ApiException.notFound("Hostel"));
        Pupil pupil = people.requirePupil(old.getStudentId());
        Instant now = Instant.now();
        old.vacate(from, Allotment.ROOM_CHANGE, actor);
        allotments.flush();
        Allotment a = allotments.save(new Allotment(old.getStudentId(), bed.getHostelId(), bed.getRoomId(),
                bed.getId(), from, old.isGuardianConsent(), actor, now));
        UUID tenantId = TenantContext.require();
        events.publishEvent(new HostelVacated(tenantId, old.getId(), old.getStudentId(), old.getHostelId(),
                old.getRoomId(), from, Allotment.ROOM_CHANGE));
        events.publishEvent(new HostelAllotted(tenantId, a.getId(), old.getId(), pupil.id(), hostel.getId(),
                hostel.getName(), room.getId(), room.getNumber(), room.getMonthlyFeePaise(), from));
        Map<String, Object> d = details(a, pupil, hostel, room, bed);
        Room oldRoom = rooms.findById(old.getRoomId()).orElse(null);
        d.put("fromRoom", oldRoom == null ? "" : oldRoom.getNumber());
        audit.record(actor, "hostel_allotment.moved", "hostel_allotment", a.getId(), d);
        allotments.flush();
        return views(List.of(a)).get(0);
    }

    private Room requireFreeBed(Bed bed) {
        Room room = rooms.findById(bed.getRoomId()).orElseThrow(() -> ApiException.notFound("Room"));
        if (!room.isActive()) {
            throw ApiException.badRequest("Room " + room.getNumber() + " is not in use.", "bedId");
        }
        allotments.activeOfBed(bed.getId()).ifPresent(x -> {
            throw new ApiException(HttpStatus.CONFLICT, "Bed taken", "Bed " + bed.getLabel() + " in room "
                    + room.getNumber() + " already has a boarder.");
        });
        return room;
    }

    /* ------------------------------------------------------------------ reading */

    /** Boarders of the hostels in scope (one hostel when given), by class then name. */
    @Transactional(readOnly = true)
    public List<BoarderView> boarders(UUID hostelId) {
        TenantContext.require();
        List<Allotment> rows;
        if (hostelId != null) {
            scope.require(hostelId);
            rows = allotments.activeInHostel(hostelId);
        } else {
            Set<UUID> visible = scope.visibleIds();
            rows = allotments.active().stream().filter(a -> visible.contains(a.getHostelId())).toList();
        }
        return boarderViews(rows);
    }

    @Transactional(readOnly = true)
    public StudentBoarding ofStudent(UUID studentId) {
        TenantContext.require();
        people.requirePupil(studentId);
        List<Allotment> history = allotments.historyOfStudent(studentId);
        if (!scope.seesAll()) {
            Set<UUID> visible = scope.visibleIds();
            history = history.stream().filter(a -> visible.contains(a.getHostelId())).toList();
        }
        List<AllotmentView> views = views(history);
        AllotmentView current = views.stream().filter(v -> v.status() == AllotmentStatus.ACTIVE).findFirst()
                .orElse(null);
        return new StudentBoarding(studentId, current != null, current, views);
    }

    List<AllotmentView> views(Collection<Allotment> rows) {
        Set<UUID> studentIds = rows.stream().map(Allotment::getStudentId).collect(Collectors.toSet());
        Map<UUID, Pupil> pupils = people.pupils(studentIds);
        Map<UUID, Hostel> hostelById = hostels.findAllById(rows.stream().map(Allotment::getHostelId).toList())
                .stream().collect(Collectors.toMap(Hostel::getId, h -> h));
        Map<UUID, Room> roomById = rooms.findAllById(rows.stream().map(Allotment::getRoomId).toList()).stream()
                .collect(Collectors.toMap(Room::getId, r -> r));
        Map<UUID, Bed> bedById = beds.findAllById(rows.stream().map(Allotment::getBedId).toList()).stream()
                .collect(Collectors.toMap(Bed::getId, b -> b));
        Map<UUID, Block> blockById = blocks.findAllById(roomById.values().stream().map(Room::getBlockId).toList())
                .stream().collect(Collectors.toMap(Block::getId, b -> b));
        List<AllotmentView> result = new ArrayList<>();
        for (Allotment a : rows) {
            Pupil p = pupils.get(a.getStudentId());
            Hostel h = hostelById.get(a.getHostelId());
            Room r = roomById.get(a.getRoomId());
            Bed b = bedById.get(a.getBedId());
            Block block = r == null ? null : blockById.get(r.getBlockId());
            result.add(new AllotmentView(a.getId(), a.getStudentId(), p == null ? null : p.fullName(),
                    p == null ? null : p.admissionNo(), p == null ? null : p.className(), a.getHostelId(),
                    h == null ? null : h.getName(), a.getRoomId(), r == null ? null : r.getNumber(),
                    block == null ? null : block.getName(), a.getBedId(), b == null ? null : b.getLabel(),
                    a.getFromDate(), a.getToDate(), a.isGuardianConsent(), a.getStatus(), a.getVacateReason(),
                    a.getAllottedByName(), a.getVacatedByName(), r == null ? 0 : r.getMonthlyFeePaise()));
        }
        return result;
    }

    List<BoarderView> boarderViews(Collection<Allotment> rows) {
        Set<UUID> studentIds = rows.stream().map(Allotment::getStudentId).collect(Collectors.toSet());
        Map<UUID, GuardianContact> guardians = people.primaryGuardians(studentIds);
        List<BoarderView> result = new ArrayList<>();
        for (AllotmentView v : views(rows)) {
            GuardianContact g = guardians.get(v.studentId());
            result.add(new BoarderView(v.studentId(), v.studentName(), v.admissionNo(), v.className(), v.id(),
                    v.hostelId(), v.hostelName(), v.blockName(), v.roomNumber(), v.bedLabel(), v.fromDate(),
                    v.guardianConsent(), g == null ? null : g.name(),
                    g == null ? null : HostelText.maskPhone(g.phone())));
        }
        result.sort(Comparator.comparing((BoarderView b) -> b.className() == null ? "" : b.className())
                .thenComparing(b -> b.studentName() == null ? "" : b.studentName()));
        return result;
    }

    private static Map<String, Object> details(Allotment a, Pupil pupil, Hostel hostel, Room room, Bed bed) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("student", pupil.fullName());
        d.put("admissionNo", pupil.admissionNo());
        d.put("hostel", hostel.getName());
        d.put("room", room.getNumber());
        d.put("bed", bed.getLabel());
        d.put("fromDate", a.getFromDate().toString());
        d.put("guardianConsent", a.isGuardianConsent());
        d.put("status", a.getStatus().name());
        return d;
    }
}
