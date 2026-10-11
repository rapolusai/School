package com.akshara.chat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.chat.ChatAccess.Me;
import com.akshara.chat.ChatPeople.ChildTeacher;
import com.akshara.chat.ChatPeople.Person;
import com.akshara.chat.ChatPeople.StudentInfo;
import com.akshara.chat.PtmForms.MarkForm;
import com.akshara.chat.PtmService.Loaded;
import com.akshara.chat.PtmViews.BookingView;
import com.akshara.chat.PtmViews.ChildPtm;
import com.akshara.chat.PtmViews.FamilyBooking;
import com.akshara.chat.PtmViews.FamilyEvent;
import com.akshara.chat.PtmViews.FamilyPtm;
import com.akshara.chat.PtmViews.FamilySlot;
import com.akshara.chat.PtmViews.FamilyTeacher;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Booking PTM slots: parents for their own children (one slot per child per teacher, until the cut-off), the school
 * for any family of the invited classes, and teachers marking whether the family came with their notes. A slot holds
 * one booking (409 when it was just taken), and a child or a parent never has two meetings at the same time.
 */
@Service
@Transactional
class PtmBookingService {

    /** How long past meetings stay on a parent's page (for the teacher's note). */
    static final int PAST_DAYS_SHOWN = 30;

    private final PtmEventRepository events;
    private final PtmSectionRepository sections;
    private final PtmSlotRepository slots;
    private final PtmBookingRepository bookings;
    private final PtmService ptm;
    private final ChatPeople people;
    private final ChatSettingsService settings;
    private final PtmReminders reminders;
    private final AuditService audit;

    PtmBookingService(PtmEventRepository events, PtmSectionRepository sections, PtmSlotRepository slots, PtmBookingRepository bookings, PtmService ptm, ChatPeople people,
            ChatSettingsService settings, PtmReminders reminders, AuditService audit) {
        this.events = events;
        this.sections = sections;
        this.slots = slots;
        this.bookings = bookings;
        this.ptm = ptm;
        this.people = people;
        this.settings = settings;
        this.reminders = reminders;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ parents

    /** The meetings of each of the parent's children: the teachers they can book, free slots and their bookings. */
    @Transactional(readOnly = true)
    FamilyPtm family(Me me, Instant now, LocalDate today) {
        TenantContext.require();
        Map<UUID, StudentInfo> children = people.students(people.childrenOf(me.id()));
        List<StudentInfo> active = children.values().stream().filter(c -> c.active() && c.sectionId() != null)
                .sorted(Comparator.comparing(StudentInfo::name, String.CASE_INSENSITIVE_ORDER)).toList();
        Set<UUID> sectionIds = active.stream().map(StudentInfo::sectionId).collect(Collectors.toSet());
        Map<UUID, List<UUID>> eventsBySection = new HashMap<>();
        if (!sectionIds.isEmpty()) {
            sections.findBySectionIdIn(sectionIds).forEach(s -> eventsBySection
                    .computeIfAbsent(s.getSectionId(), k -> new ArrayList<>()).add(s.getEventId()));
        }
        List<PtmEvent> shown = events.byIds(eventsBySection.values().stream().flatMap(List::stream)
                .collect(Collectors.toSet())).stream()
                .filter(e -> !e.getMeetingDate().isBefore(today.minusDays(PAST_DAYS_SHOWN)))
                .toList();
        Loaded loaded = ptm.load(shown);
        List<PtmBooking> childBookings = shown.isEmpty() || active.isEmpty() ? List.of()
                : bookings.ofEventsAndStudents(shown.stream().map(PtmEvent::getId).toList(),
                        active.stream().map(StudentInfo::id).toList());
        List<ChildPtm> result = new ArrayList<>();
        Map<List<Object>, Set<UUID>> teaching = new HashMap<>();
        for (StudentInfo child : active) {
            List<UUID> eventIds = eventsBySection.getOrDefault(child.sectionId(), List.of());
            List<ChildTeacher> childTeachers = people.teachersOf(child.id());
            Map<UUID, ChildTeacher> teacherById = childTeachers.stream()
                    .collect(Collectors.toMap(ChildTeacher::teacherId, t -> t, (a, b) -> a));
            List<FamilyEvent> childEvents = new ArrayList<>();
            for (PtmEvent e : shown) {
                if (!eventIds.contains(e.getId())) {
                    continue;
                }
                Set<UUID> teachingIds = teaching.computeIfAbsent(List.of(child.sectionId(), e.getAcademicYearId(),
                        e.isIncludeSubjectTeachers()), k -> people.ptmTeachersOf(child.sectionId(),
                                e.getAcademicYearId(), e.isIncludeSubjectTeachers()));
                List<FamilyTeacher> eventTeachers = new ArrayList<>();
                for (PtmTeacher t : loaded.teachersOf(e.getId())) {
                    if (!teachingIds.contains(t.getTeacherUserId())) {
                        continue;
                    }
                    PtmBooking mine = childBookings.stream().filter(b -> b.isBooked()
                            && b.getEventId().equals(e.getId()) && b.getStudentId().equals(child.id())
                            && b.getTeacherUserId().equals(t.getTeacherUserId())).findFirst().orElse(null);
                    List<FamilySlot> slotViews = new ArrayList<>();
                    for (PtmSlot s : loaded.slotsOf(t)) {
                        if (s.isBlocked() && (mine == null || !mine.getSlotId().equals(s.getId()))) {
                            continue;
                        }
                        Instant until = e.cutoffFor(s.getStartsAt());
                        boolean free = loaded.activeOf(s) == null && !s.isBlocked() && !e.isCancelled()
                                && now.isBefore(until);
                        slotViews.add(new FamilySlot(s.getId(), s.getStartsAt(), s.getEndsAt(), free, until));
                    }
                    ChildTeacher ct = teacherById.get(t.getTeacherUserId());
                    String detail = ct != null ? ct.detail() : t.getKind() == PtmTeacher.Kind.CLASS_TEACHER
                            ? "Class teacher" : t.getSubjects();
                    eventTeachers.add(new FamilyTeacher(t.getTeacherUserId(), loaded.nameOf(t.getTeacherUserId()),
                            detail, slotViews, mine == null ? null : familyBooking(e, mine, loaded, now, today)));
                }
                // The child's class teacher first, then the others as the child's teachers are listed.
                List<UUID> order = childTeachers.stream().map(ChildTeacher::teacherId).toList();
                eventTeachers.sort(Comparator.comparingInt((FamilyTeacher ft) -> order.contains(ft.teacherId())
                        ? order.indexOf(ft.teacherId()) : Integer.MAX_VALUE)
                        .thenComparing(FamilyTeacher::name, String.CASE_INSENSITIVE_ORDER));
                childEvents.add(new FamilyEvent(e.getId(), e.getTitle(), e.getMeetingDate(), e.getStartsAt(),
                        e.getEndsAt(), e.getSlotMinutes(), e.getMode(), e.getLocation(), e.getVideoLink(),
                        e.getNote(), e.getStatus().name(), e.getCancelReason(), e.getCutoffHours(),
                        e.getMeetingDate().isBefore(today), eventTeachers));
            }
            childEvents.sort(Comparator.comparing((FamilyEvent e) -> e.past())
                    .thenComparing(FamilyEvent::date));
            result.add(new ChildPtm(child.id(), child.name(), child.classLabel(), childEvents));
        }
        return new FamilyPtm(result, settings.ptmRemindersEnabled(), now);
    }

    /** A parent books a slot for their child. */
    FamilyBooking bookForChild(Me me, UUID slotId, UUID studentId, Instant now, LocalDate today) {
        TenantContext.require();
        if (!people.isParentOf(me.id(), studentId)) {
            throw ApiException.badRequest("Choose one of your children.", "studentId");
        }
        PtmSlot slot = slots.lock(slotId).orElseThrow(() -> ApiException.badRequest("Choose an open slot.",
                "slotId"));
        PtmEvent e = ptm.find(slot.getEventId());
        StudentInfo child = checkChild(e, slot, studentId);
        if (!now.isBefore(e.cutoffFor(slot.getStartsAt()))) {
            throw ApiException.conflict("Booking for this slot closed " + e.getCutoffHours()
                    + " hours before it. Contact the school to book.", "slotId");
        }
        PtmBooking b = book(e, slot, child, me.id(), PtmBooking.BookedBy.PARENT, nameOf(me), null);
        reminders.schedule(e, b, slot, now);
        auditBooking(me.actor(), "ptm_booking.booked", b, slot);
        return familyBooking(e, b, ptm.load(List.of(e)), now, today);
    }

    /** A parent cancels their child's booking, until the cut-off. */
    void cancelForChild(Me me, UUID bookingId, Instant now) {
        PtmBooking b = ownBooking(me, bookingId);
        PtmEvent e = ptm.find(b.getEventId());
        PtmSlot slot = slots.findById(b.getSlotId()).orElseThrow(() -> ApiException.notFound("Booking"));
        checkChangeable(e, slot, b, now, "bookingId");
        b.cancel(nameOf(me), now);
        bookings.saveAndFlush(b);
        reminders.unschedule(b, "Booking cancelled");
        auditBooking(me.actor(), "ptm_booking.cancelled", b, slot);
    }

    /** A parent moves their child's booking to another free slot with the same teacher, until the cut-off. */
    FamilyBooking moveForChild(Me me, UUID bookingId, UUID slotId, Instant now, LocalDate today) {
        PtmBooking b = ownBooking(me, bookingId);
        PtmEvent e = ptm.find(b.getEventId());
        PtmSlot from = slots.findById(b.getSlotId()).orElseThrow(() -> ApiException.notFound("Booking"));
        checkChangeable(e, from, b, now, "slotId");
        PtmSlot to = slots.lock(slotId).filter(s -> s.getEventId().equals(e.getId())
                && s.getTeacherUserId().equals(b.getTeacherUserId()))
                .orElseThrow(() -> ApiException.badRequest("Choose another slot with the same teacher.", "slotId"));
        if (to.getId().equals(from.getId())) {
            throw ApiException.badRequest("Choose a different slot.", "slotId");
        }
        if (!now.isBefore(e.cutoffFor(to.getStartsAt()))) {
            throw ApiException.conflict("Booking for this slot has closed. Choose a later one.", "slotId");
        }
        StudentInfo child = people.student(b.getStudentId())
                .orElseThrow(() -> ApiException.notFound("Booking"));
        b.cancel(nameOf(me), now);
        bookings.saveAndFlush(b);
        reminders.unschedule(b, "Booking moved");
        PtmBooking moved = book(e, to, child, me.id(), PtmBooking.BookedBy.PARENT, nameOf(me), b.getId());
        reminders.schedule(e, moved, to, now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventId", e.getId().toString());
        details.put("studentId", b.getStudentId().toString());
        details.put("from", from.getStartsAt().toString());
        details.put("to", to.getStartsAt().toString());
        details.put("previousBookingId", b.getId().toString());
        audit.record(me.actor(), "ptm_booking.moved", "ptm_booking", moved.getId(), details);
        return familyBooking(e, moved, ptm.load(List.of(e)), now, today);
    }

    // ------------------------------------------------------------------ the school

    /** The school books a slot for a family of an invited class (any time, even after the parents' cut-off). */
    BookingView bookForFamily(UUID eventId, UUID slotId, UUID studentId, Actor actor, Instant now,
            LocalDate today) {
        PtmEvent e = ptm.find(eventId);
        PtmSlot slot = slots.lock(slotId).filter(s -> s.getEventId().equals(e.getId()))
                .orElseThrow(() -> ApiException.badRequest("Choose a slot of this meeting.", "slotId"));
        if (e.getMeetingDate().isBefore(today)) {
            throw ApiException.conflict("This meeting is over.", "slotId");
        }
        StudentInfo child = people.student(studentId)
                .orElseThrow(() -> ApiException.badRequest("Choose a student of the invited classes.", "studentId"));
        checkChild(e, slot, child.id());
        PtmBooking b = book(e, slot, child, null, PtmBooking.BookedBy.SCHOOL,
                actor.name() == null ? "School" : actor.name(), null);
        reminders.schedule(e, b, slot, now);
        auditBooking(actor, "ptm_booking.booked", b, slot);
        return ptm.load(List.of(e)).bookingView(b, slot);
    }

    /** The school cancels any booking. */
    void cancelBySchool(UUID bookingId, Actor actor, Instant now) {
        TenantContext.require();
        PtmBooking b = bookings.lock(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        if (!b.isBooked()) {
            throw ApiException.conflict("This booking is already cancelled.", "bookingId");
        }
        PtmSlot slot = slots.findById(b.getSlotId()).orElseThrow(() -> ApiException.notFound("Booking"));
        b.cancel(actor.name() == null ? "School" : actor.name(), now);
        bookings.saveAndFlush(b);
        reminders.unschedule(b, "Booking cancelled");
        auditBooking(actor, "ptm_booking.cancelled", b, slot);
    }

    // ------------------------------------------------------------------ teachers

    /**
     * The teacher marks a booking: attended or no-show (on or after the meeting day) and the notes. {@code onlyTeacher}
     * limits it to that teacher's own bookings (others answer 404); null for leadership.
     */
    BookingView mark(UUID bookingId, MarkForm form, UUID onlyTeacher, Actor actor, Instant now, LocalDate today) {
        TenantContext.require();
        PtmBooking b = bookings.lock(bookingId)
                .filter(x -> onlyTeacher == null || onlyTeacher.equals(x.getTeacherUserId()))
                .orElseThrow(() -> ApiException.notFound("Booking"));
        if (!b.isBooked()) {
            throw ApiException.conflict("This booking was cancelled.", "attendance");
        }
        PtmEvent e = ptm.find(b.getEventId());
        if (e.isCancelled()) {
            throw ApiException.conflict("This meeting was cancelled.", "attendance");
        }
        if (form.attendance() != null && e.getMeetingDate().isAfter(today)) {
            throw ApiException.badRequest("Mark whether the family came on or after the meeting day.", "attendance");
        }
        String byName = people.person(actor.id()).map(Person::name).orElse(actor.name());
        b.mark(form.attendance(), blankToNull(form.privateNote()), blankToNull(form.parentNote()), byName, now);
        bookings.saveAndFlush(b);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventId", e.getId().toString());
        details.put("attendance", form.attendance() == null ? null : form.attendance().name());
        details.put("parentNote", form.parentNote() != null && !form.parentNote().isBlank());
        audit.record(actor, "ptm_booking.marked", "ptm_booking", b.getId(), details);
        PtmSlot slot = slots.findById(b.getSlotId()).orElseThrow(() -> ApiException.notFound("Booking"));
        return ptm.load(List.of(e)).bookingView(b, slot);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Checks that the child can meet the slot's teacher at this meeting: the meeting is on, the child's class is
     * invited and the teacher teaches the child. Returns the child.
     */
    private StudentInfo checkChild(PtmEvent e, PtmSlot slot, UUID studentId) {
        if (e.isCancelled()) {
            throw ApiException.conflict("This meeting was cancelled.", "slotId");
        }
        StudentInfo child = people.student(studentId)
                .orElseThrow(() -> ApiException.badRequest("Choose a student of the invited classes.", "studentId"));
        if (!child.active() || child.sectionId() == null) {
            throw ApiException.badRequest(child.name() + " is not in a class this year.", "studentId");
        }
        Set<UUID> invited = sections.findByEventId(e.getId()).stream().map(PtmSection::getSectionId)
                .collect(Collectors.toSet());
        if (!invited.contains(child.sectionId())) {
            throw ApiException.badRequest("This meeting is not for " + child.classLabel() + ".", "studentId");
        }
        if (!people.ptmTeachersOf(child.sectionId(), e.getAcademicYearId(), e.isIncludeSubjectTeachers())
                .contains(slot.getTeacherUserId())) {
            throw ApiException.badRequest("Choose a slot with one of " + child.name() + "'s teachers.", "slotId");
        }
        if (slot.isBlocked()) {
            throw ApiException.conflict("This slot is not available. Choose another.", "slotId");
        }
        return child;
    }

    /** Books the slot after the checks every booking shares: free slot, one per child and teacher, no overlap. */
    private PtmBooking book(PtmEvent e, PtmSlot slot, StudentInfo child, UUID parentUserId,
            PtmBooking.BookedBy by, String byName, UUID replacing) {
        if (bookings.activeOfSlot(slot.getId()).isPresent()) {
            throw ApiException.conflict("This slot was just booked by another family. Choose another.", "slotId");
        }
        Optional<PtmBooking> already = bookings.activeOf(e.getId(), slot.getTeacherUserId(), child.id());
        if (already.isPresent()) {
            throw ApiException.conflict(child.name() + " already has a meeting with this teacher. Change or cancel "
                    + "that one.", "slotId");
        }
        Map<UUID, PtmSlot> slotById = slots.ofEvent(e.getId()).stream()
                .collect(Collectors.toMap(PtmSlot::getId, s -> s));
        for (PtmBooking other : bookings.activeOfEvent(e.getId())) {
            boolean sameFamily = other.getStudentId().equals(child.id())
                    || (parentUserId != null && parentUserId.equals(other.getParentUserId()));
            PtmSlot otherSlot = slotById.get(other.getSlotId());
            if (sameFamily && !Objects.equals(other.getId(), replacing) && otherSlot != null
                    && otherSlot.getStartsAt().isBefore(slot.getEndsAt())
                    && otherSlot.getEndsAt().isAfter(slot.getStartsAt())) {
                throw ApiException.conflict("There is already a meeting at " + otherSlot.getStartsAt()
                        + " for this family. Choose a time that does not overlap.", "slotId");
            }
        }
        try {
            return bookings.saveAndFlush(new PtmBooking(slot, child.id(), parentUserId, by, byName, Instant.now()));
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("This slot was just booked by another family. Choose another.", "slotId");
        }
    }

    /** A booking of one of the parent's children; anything else answers 404. */
    private PtmBooking ownBooking(Me me, UUID bookingId) {
        TenantContext.require();
        return bookings.lock(bookingId).filter(b -> people.isParentOf(me.id(), b.getStudentId()))
                .orElseThrow(() -> ApiException.notFound("Booking"));
    }

    private static void checkChangeable(PtmEvent e, PtmSlot slot, PtmBooking b, Instant now, String field) {
        if (!b.isBooked()) {
            throw ApiException.conflict("This booking is already cancelled.", field);
        }
        if (e.isCancelled()) {
            throw ApiException.conflict("This meeting was cancelled.", field);
        }
        if (!now.isBefore(e.cutoffFor(slot.getStartsAt()))) {
            throw ApiException.conflict("Bookings can be changed until " + e.getCutoffHours()
                    + " hours before the slot. Contact the school.", field);
        }
    }

    private FamilyBooking familyBooking(PtmEvent e, PtmBooking b, Loaded loaded, Instant now, LocalDate today) {
        PtmSlot slot = slots.findById(b.getSlotId()).orElse(null);
        Instant until = slot == null ? null : e.cutoffFor(slot.getStartsAt());
        boolean canChange = b.isBooked() && !e.isCancelled() && until != null && now.isBefore(until);
        boolean held = !e.getMeetingDate().isAfter(today);
        return new FamilyBooking(b.getId(), b.getSlotId(), slot == null ? null : slot.getStartsAt(),
                slot == null ? null : slot.getEndsAt(), b.getBookedBy().name(), canChange, until,
                held ? b.getAttendance() : null, b.getParentNote());
    }

    private void auditBooking(Actor actor, String action, PtmBooking b, PtmSlot slot) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventId", b.getEventId().toString());
        details.put("studentId", b.getStudentId().toString());
        details.put("teacherId", b.getTeacherUserId().toString());
        details.put("startsAt", slot.getStartsAt().toString());
        details.put("bookedBy", b.getBookedBy().name());
        audit.record(actor, action, "ptm_booking", b.getId(), details);
    }

    private String nameOf(Me me) {
        return people.person(me.id()).map(Person::name).orElse(me.name());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
