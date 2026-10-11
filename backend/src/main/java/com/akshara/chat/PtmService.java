package com.akshara.chat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.chat.ChatPeople.Person;
import com.akshara.chat.ChatPeople.StudentInfo;
import com.akshara.chat.PtmForms.BreakForm;
import com.akshara.chat.PtmForms.EventForm;
import com.akshara.chat.PtmForms.HoursForm;
import com.akshara.chat.PtmSlots.Range;
import com.akshara.chat.PtmViews.BookingView;
import com.akshara.chat.PtmViews.BreakView;
import com.akshara.chat.PtmViews.EventDetail;
import com.akshara.chat.PtmViews.EventSection;
import com.akshara.chat.PtmViews.EventSummary;
import com.akshara.chat.PtmViews.MyEvent;
import com.akshara.chat.PtmViews.PtmSettings;
import com.akshara.chat.PtmViews.SectionRef;
import com.akshara.chat.PtmViews.SlotView;
import com.akshara.chat.PtmViews.StudentRef;
import com.akshara.chat.PtmViews.TeacherSchedule;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Parent-teacher meetings as the school sets them up (ptm.manage): the meeting day for some sections, its hours, slot
 * length and breaks, in person or by video; the class teachers (and, if chosen, subject teachers) of those sections
 * with slots made from their hours; blocking slots; cancelling; and the reminder setting. Teachers see and shape their
 * own schedule. Slot times never change: when hours change, slots that no longer fit go (unless booked: 409) and new
 * ones are made.
 */
@Service
@Transactional
class PtmService {

    private final PtmEventRepository events;
    private final PtmSectionRepository sections;
    private final PtmTeacherRepository teachers;
    private final PtmSlotRepository slots;
    private final PtmBookingRepository bookings;
    private final ChatPeople people;
    private final ChatSettingsService settings;
    private final PtmReminders reminders;
    private final AuditService audit;

    PtmService(PtmEventRepository events, PtmSectionRepository sections, PtmTeacherRepository teachers,
            PtmSlotRepository slots, PtmBookingRepository bookings, ChatPeople people, ChatSettingsService settings,
            PtmReminders reminders, AuditService audit) {
        this.events = events;
        this.sections = sections;
        this.teachers = teachers;
        this.slots = slots;
        this.bookings = bookings;
        this.people = people;
        this.settings = settings;
        this.reminders = reminders;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    List<EventSummary> list(LocalDate today) {
        TenantContext.require();
        List<PtmEvent> all = events.newestFirst();
        Loaded loaded = load(all);
        return all.stream().map(e -> loaded.summary(e, today)).toList();
    }

    @Transactional(readOnly = true)
    EventDetail detail(UUID id, LocalDate today) {
        return detail(find(id), today);
    }

    EventDetail detail(PtmEvent e, LocalDate today) {
        Loaded loaded = load(List.of(e));
        Map<UUID, SectionInfo> sectionInfo = people.sectionsById();
        Map<UUID, Long> perSection = people.activePerSection(e.getAcademicYearId());
        Map<UUID, Set<UUID>> bookedStudentsBySection = new HashMap<>();
        for (PtmBooking b : loaded.activeBookings(e.getId())) {
            StudentInfo s = loaded.students.get(b.getStudentId());
            if (s != null && s.sectionId() != null) {
                bookedStudentsBySection.computeIfAbsent(s.sectionId(), k -> new HashSet<>()).add(b.getStudentId());
            }
        }
        List<EventSection> eventSections = loaded.sectionIds(e.getId()).stream()
                .map(sectionInfo::get).filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(SectionInfo::classOrder).thenComparing(SectionInfo::name))
                .map(s -> new EventSection(s.id(), s.label(), s.classTeacherId(), s.classTeacherName(),
                        perSection.getOrDefault(s.id(), 0L).intValue(),
                        bookedStudentsBySection.getOrDefault(s.id(), Set.of()).size()))
                .toList();
        List<TeacherSchedule> schedules = loaded.teachersOf(e.getId()).stream().map(loaded::schedule).toList();
        return new EventDetail(e.getId(), e.getTitle(), e.getMeetingDate(), e.getStartsAt(), e.getEndsAt(),
                e.getSlotMinutes(), breaks(e), e.getMode(), e.getLocation(), e.getVideoLink(),
                e.isIncludeSubjectTeachers(), e.getCutoffHours(), e.getNote(), e.getStatus().name(),
                e.getCancelledByName(), e.getCancelReason(), e.getCancelledAt(), e.getCreatedByName(),
                e.getCreatedAt(), e.getMeetingDate().isBefore(today), eventSections, schedules,
                settings.ptmRemindersEnabled());
    }

    /** The meetings a teacher has slots at, upcoming first, then past ones (newest first). */
    @Transactional(readOnly = true)
    List<MyEvent> mine(UUID teacherUserId, LocalDate today) {
        TenantContext.require();
        Map<UUID, PtmTeacher> mineByEvent = teachers.findByTeacherUserId(teacherUserId).stream()
                .collect(Collectors.toMap(PtmTeacher::getEventId, t -> t, (a, b) -> a));
        if (mineByEvent.isEmpty()) {
            return List.of();
        }
        List<PtmEvent> list = new ArrayList<>(events.byIds(mineByEvent.keySet()));
        list.sort(Comparator.comparing((PtmEvent e) -> e.getMeetingDate().isBefore(today))
                .thenComparing((a, b) -> a.getMeetingDate().isBefore(today)
                        ? b.getMeetingDate().compareTo(a.getMeetingDate())
                        : a.getMeetingDate().compareTo(b.getMeetingDate())));
        Loaded loaded = load(list);
        return list.stream().map(e -> myEvent(e, mineByEvent.get(e.getId()), loaded, today)).toList();
    }

    @Transactional(readOnly = true)
    MyEvent myEvent(UUID eventId, UUID teacherUserId, LocalDate today) {
        PtmEvent e = find(eventId);
        PtmTeacher mine = teachers.findByEventIdAndTeacherUserId(eventId, teacherUserId)
                .orElseThrow(() -> ApiException.notFound("Meeting"));
        return myEvent(e, mine, load(List.of(e)), today);
    }

    private MyEvent myEvent(PtmEvent e, PtmTeacher mine, Loaded loaded, LocalDate today) {
        return new MyEvent(loaded.summary(e, today), e.getVideoLink(), e.getNote(), e.getCancelReason(),
                e.getCutoffHours(), loaded.schedule(mine));
    }

    // ------------------------------------------------------------------ setting up

    EventDetail create(EventForm form, Actor actor, Instant now, LocalDate today) {
        TenantContext.require();
        YearInfo year = people.currentYear()
                .orElseThrow(() -> ApiException.conflict("Set up the current academic year first.", "date"));
        PtmEvent.Details details = details(form, today, null);
        List<UUID> sectionIds = sectionIds(form.sectionIds());
        PtmEvent e = events.saveAndFlush(new PtmEvent(year.id(), details, actor, now));
        for (UUID sectionId : sectionIds) {
            sections.save(new PtmSection(e.getId(), sectionId, now));
        }
        sections.flush();
        int teacherCount = syncTeachers(e, sectionIds, now, "sectionIds");
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("title", e.getTitle());
        audited.put("date", e.getMeetingDate().toString());
        audited.put("sections", sectionIds.size());
        audited.put("teachers", teacherCount);
        audit.record(actor, "ptm_event.created", "ptm_event", e.getId(), audited);
        return detail(e, today);
    }

    /**
     * Changes a meeting. The day cannot change once families have booked, and no booked slot may fall outside the
     * new hours or classes (409): cancel or move those bookings first.
     */
    EventDetail update(UUID id, EventForm form, Actor actor, Instant now, LocalDate today) {
        PtmEvent e = events.lock(find(id).getId()).orElseThrow(() -> ApiException.notFound("Meeting"));
        if (e.isCancelled()) {
            throw ApiException.conflict("This meeting was cancelled.", "title");
        }
        PtmEvent.Details details = details(form, today, e.getMeetingDate());
        List<UUID> sectionIds = sectionIds(form.sectionIds());
        List<PtmBooking> active = bookings.activeOfEvent(e.getId());
        if (!details.date().equals(e.getMeetingDate()) && !active.isEmpty()) {
            throw ApiException.conflict("Families have booked slots on " + e.getMeetingDate()
                    + ". Cancel the meeting and set up a new one to change the day.", "date");
        }
        Map<UUID, StudentInfo> students = people.students(active.stream().map(PtmBooking::getStudentId).toList());
        for (PtmBooking b : active) {
            StudentInfo s = students.get(b.getStudentId());
            if (s != null && s.sectionId() != null && !sectionIds.contains(s.sectionId())) {
                throw ApiException.conflict(s.name() + " (" + s.classLabel() + ") has a booked slot. Cancel it "
                        + "before taking that class out of the meeting.", "sectionIds");
            }
        }
        e.apply(details);
        events.saveAndFlush(e);
        Set<UUID> wanted = new LinkedHashSet<>(sectionIds);
        for (PtmSection s : sections.findByEventId(e.getId())) {
            if (!wanted.remove(s.getSectionId())) {
                sections.delete(s);
            }
        }
        for (UUID sectionId : wanted) {
            sections.save(new PtmSection(e.getId(), sectionId, now));
        }
        sections.flush();
        syncTeachers(e, sectionIds, now, "startsAt");
        // The place or the time may have changed: queue the reminders again with the new text.
        Map<UUID, PtmSlot> slotById = slots.ofEvent(e.getId()).stream()
                .collect(Collectors.toMap(PtmSlot::getId, s -> s));
        for (PtmBooking b : bookings.activeOfEvent(e.getId())) {
            reminders.unschedule(b, "Meeting changed");
            PtmSlot slot = slotById.get(b.getSlotId());
            if (slot != null) {
                reminders.schedule(e, b, slot, now);
            }
        }
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("title", e.getTitle());
        audited.put("date", e.getMeetingDate().toString());
        audited.put("sections", sectionIds.size());
        audit.record(actor, "ptm_event.updated", "ptm_event", e.getId(), audited);
        return detail(e, today);
    }

    /** Cancels the meeting and every booking at it; reminders still waiting are dropped. */
    EventDetail cancel(UUID id, String reason, Actor actor, Instant now, LocalDate today) {
        PtmEvent e = events.lock(find(id).getId()).orElseThrow(() -> ApiException.notFound("Meeting"));
        if (e.isCancelled()) {
            throw ApiException.conflict("This meeting is already cancelled.", "reason");
        }
        e.cancel(actor.name(), reason.strip(), now);
        events.saveAndFlush(e);
        int cancelled = 0;
        for (PtmBooking b : bookings.activeOfEvent(e.getId())) {
            b.cancel(actor.name(), now);
            reminders.unschedule(b, "Meeting cancelled");
            cancelled++;
        }
        bookings.flush();
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("reason", reason.strip());
        audited.put("bookingsCancelled", cancelled);
        audit.record(actor, "ptm_event.cancelled", "ptm_event", e.getId(), audited);
        return detail(e, today);
    }

    /**
     * A teacher's hours at the meeting (by the teacher, or by leadership for them), inside the meeting's hours.
     * Free slots outside them go and new ones are made; a booked slot outside them is a 409.
     */
    TeacherSchedule setHours(UUID eventId, UUID teacherUserId, HoursForm form, Actor actor, Instant now,
            LocalDate today) {
        PtmEvent e = events.lock(find(eventId).getId()).orElseThrow(() -> ApiException.notFound("Meeting"));
        PtmTeacher t = teachers.findByEventIdAndTeacherUserId(e.getId(), teacherUserId)
                .orElseThrow(() -> ApiException.notFound("Teacher"));
        checkOpen(e, today, "availableFrom");
        if (!form.availableTo().isAfter(form.availableFrom())) {
            throw ApiException.badRequest("The end must be after the start.", "availableTo");
        }
        if (form.availableFrom().isBefore(e.getStartsAt()) || form.availableTo().isAfter(e.getEndsAt())) {
            throw ApiException.badRequest("Choose hours between " + e.getStartsAt() + " and " + e.getEndsAt() + ".",
                    "availableFrom");
        }
        t.hours(form.availableFrom(), form.availableTo());
        teachers.saveAndFlush(t);
        int made = syncSlots(e, t, now, "availableFrom");
        if (made == 0 && slots.ofTeacher(t.getId()).isEmpty()) {
            throw ApiException.badRequest("No slot of " + e.getSlotMinutes() + " minutes fits in these hours.",
                    "availableTo");
        }
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("eventId", e.getId().toString());
        audited.put("availableFrom", form.availableFrom().toString());
        audited.put("availableTo", form.availableTo().toString());
        audit.record(actor, "ptm_teacher.hours_changed", "ptm_teacher", t.getId(), audited);
        return load(List.of(e)).schedule(t);
    }

    /**
     * Blocks or frees a slot. {@code onlyTeacher} limits it to that teacher's own slots (others answer 404); null for
     * leadership. A booked slot cannot be blocked (409).
     */
    SlotView block(UUID slotId, boolean blocked, UUID onlyTeacher, Actor actor, LocalDate today) {
        TenantContext.require();
        PtmSlot slot = slots.lock(slotId).filter(s -> onlyTeacher == null || onlyTeacher.equals(s.getTeacherUserId()))
                .orElseThrow(() -> ApiException.notFound("Slot"));
        PtmEvent e = find(slot.getEventId());
        checkOpen(e, today, "blocked");
        if (blocked && bookings.activeOfSlot(slot.getId()).isPresent()) {
            throw ApiException.conflict("This slot is booked. Cancel or move the booking first.", "blocked");
        }
        if (slot.isBlocked() != blocked) {
            slot.block(blocked);
            slots.saveAndFlush(slot);
            audit.record(actor, blocked ? "ptm_slot.blocked" : "ptm_slot.unblocked", "ptm_slot", slot.getId(),
                    Map.of("eventId", e.getId().toString(), "startsAt", slot.getStartsAt().toString()));
        }
        Loaded loaded = load(List.of(e));
        return loaded.slotView(slot);
    }

    @Transactional(readOnly = true)
    PtmSettings ptmSettings() {
        return new PtmSettings(settings.ptmRemindersEnabled());
    }

    /** Switches PTM reminders on or off; reminders of upcoming bookings are queued or dropped to match. */
    PtmSettings updatePtmSettings(boolean enabled, Actor actor, Instant now, LocalDate today) {
        TenantContext.require();
        boolean was = settings.ptmRemindersEnabled();
        UUID rowId = settings.ptmReminders(enabled, now);
        if (was != enabled) {
            for (PtmEvent e : events.newestFirst()) {
                if (e.isCancelled() || e.getMeetingDate().isBefore(today)) {
                    continue;
                }
                Map<UUID, PtmSlot> slotById = slots.ofEvent(e.getId()).stream()
                        .collect(Collectors.toMap(PtmSlot::getId, s -> s));
                for (PtmBooking b : bookings.activeOfEvent(e.getId())) {
                    if (enabled) {
                        PtmSlot slot = slotById.get(b.getSlotId());
                        if (slot != null) {
                            reminders.schedule(e, b, slot, now);
                        }
                    } else {
                        reminders.unschedule(b, "PTM reminders switched off");
                    }
                }
            }
        }
        audit.record(actor, "ptm_settings.updated", "ptm_settings", rowId, Map.of("remindersEnabled", enabled));
        return new PtmSettings(enabled);
    }

    // ------------------------------------------------------------------ shared with bookings and reports

    PtmEvent find(UUID id) {
        TenantContext.require();
        return events.findById(id).orElseThrow(() -> ApiException.notFound("Meeting"));
    }

    static List<BreakView> breaks(PtmEvent e) {
        return PtmSlots.parseBreaks(e.getBreaks()).stream().map(r -> new BreakView(r.from(), r.to())).toList();
    }

    /** Everything about some meetings that their views need, read in a few queries. */
    Loaded load(Collection<PtmEvent> list) {
        List<UUID> ids = list.stream().map(PtmEvent::getId).toList();
        if (ids.isEmpty()) {
            return new Loaded(List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of());
        }
        List<PtmSection> eventSections = sections.findByEventIdIn(ids);
        List<PtmTeacher> eventTeachers = teachers.findByEventIdIn(ids);
        List<PtmSlot> eventSlots = slots.ofEvents(ids);
        List<PtmBooking> eventBookings = bookings.ofEvents(ids);
        Set<UUID> userIds = new HashSet<>();
        eventTeachers.forEach(t -> userIds.add(t.getTeacherUserId()));
        eventBookings.stream().filter(PtmBooking::isBooked).map(PtmBooking::getParentUserId)
                .filter(Objects::nonNull).forEach(userIds::add);
        Map<UUID, Person> persons = people.people(userIds);
        Map<UUID, StudentInfo> students = people.students(eventBookings.stream().filter(PtmBooking::isBooked)
                .map(PtmBooking::getStudentId).collect(Collectors.toCollection(LinkedHashSet::new)));
        Map<UUID, SectionInfo> sectionInfo = people.sectionsById();
        return new Loaded(eventSections, eventTeachers, eventSlots, eventBookings, persons, students, sectionInfo);
    }

    /** The loaded rows of some meetings with view builders. */
    final class Loaded {

        final Map<UUID, StudentInfo> students;
        final Map<UUID, Person> persons;
        private final List<PtmSection> eventSections;
        private final List<PtmTeacher> eventTeachers;
        private final List<PtmSlot> eventSlots;
        private final Map<UUID, PtmBooking> activeBySlot = new HashMap<>();
        private final List<PtmBooking> eventBookings;
        private final Map<UUID, SectionInfo> sectionInfo;

        Loaded(List<PtmSection> eventSections, List<PtmTeacher> eventTeachers, List<PtmSlot> eventSlots,
                List<PtmBooking> eventBookings, Map<UUID, Person> persons, Map<UUID, StudentInfo> students,
                Map<UUID, SectionInfo> sectionInfo) {
            this.eventSections = eventSections;
            this.eventTeachers = eventTeachers;
            this.eventSlots = eventSlots;
            this.eventBookings = eventBookings;
            this.persons = persons;
            this.students = students;
            this.sectionInfo = sectionInfo;
            eventBookings.stream().filter(PtmBooking::isBooked).forEach(b -> activeBySlot.put(b.getSlotId(), b));
        }

        List<UUID> sectionIds(UUID eventId) {
            return eventSections.stream().filter(s -> s.getEventId().equals(eventId)).map(PtmSection::getSectionId)
                    .toList();
        }

        List<PtmTeacher> teachersOf(UUID eventId) {
            return eventTeachers.stream().filter(t -> t.getEventId().equals(eventId))
                    .sorted(Comparator.comparing((PtmTeacher t) -> t.getKind() != PtmTeacher.Kind.CLASS_TEACHER)
                            .thenComparing(t -> nameOf(t.getTeacherUserId()), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        List<PtmSlot> slotsOf(PtmTeacher t) {
            return eventSlots.stream().filter(s -> s.getPtmTeacherId().equals(t.getId()))
                    .sorted(Comparator.comparing(PtmSlot::getStartsAt)).toList();
        }

        List<PtmBooking> activeBookings(UUID eventId) {
            return eventBookings.stream().filter(b -> b.getEventId().equals(eventId) && b.isBooked()).toList();
        }

        PtmBooking activeOf(PtmSlot slot) {
            return activeBySlot.get(slot.getId());
        }

        String nameOf(UUID userId) {
            Person p = persons.get(userId);
            return p == null ? "" : p.name();
        }

        TeacherSchedule schedule(PtmTeacher t) {
            List<SlotView> views = slotsOf(t).stream().map(this::slotView).toList();
            int booked = (int) views.stream().filter(v -> v.booking() != null).count();
            int free = (int) views.stream().filter(v -> v.booking() == null && !v.blocked()).count();
            return new TeacherSchedule(t.getId(), t.getTeacherUserId(), nameOf(t.getTeacherUserId()),
                    t.getKind().name(), t.getSubjects(), t.getAvailableFrom(), t.getAvailableTo(), booked, free,
                    views);
        }

        SlotView slotView(PtmSlot s) {
            PtmBooking b = activeBySlot.get(s.getId());
            return new SlotView(s.getId(), s.getStartsAt(), s.getEndsAt(), s.isBlocked(),
                    b == null ? null : bookingView(b, s));
        }

        BookingView bookingView(PtmBooking b, PtmSlot s) {
            StudentInfo st = students.get(b.getStudentId());
            StudentRef student = st == null ? new StudentRef(b.getStudentId(), null, null, null)
                    : new StudentRef(st.id(), st.name(), st.classLabel(), st.sectionId());
            String parentName = b.getParentUserId() == null ? null : nameOf(b.getParentUserId());
            return new BookingView(b.getId(), s.getId(), s.getStartsAt(), s.getEndsAt(), b.getTeacherUserId(),
                    nameOf(b.getTeacherUserId()), student, parentName, b.getBookedBy().name(), b.getBookedByName(),
                    b.getAttendance(), b.getPrivateNote(), b.getParentNote(), b.getMarkedAt(), b.getMarkedByName(),
                    b.getCreatedAt());
        }

        EventSummary summary(PtmEvent e, LocalDate today) {
            List<SectionRef> refs = sectionIds(e.getId()).stream().map(sectionInfo::get).filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(SectionInfo::classOrder).thenComparing(SectionInfo::name))
                    .map(s -> new SectionRef(s.id(), s.label())).toList();
            List<PtmBooking> active = activeBookings(e.getId());
            int slotCount = (int) eventSlots.stream().filter(s -> s.getEventId().equals(e.getId()) && !s.isBlocked())
                    .count();
            int attended = (int) active.stream().filter(b -> b.getAttendance() == PtmAttendance.ATTENDED).count();
            int noShow = (int) active.stream().filter(b -> b.getAttendance() == PtmAttendance.NO_SHOW).count();
            return new EventSummary(e.getId(), e.getTitle(), e.getMeetingDate(), e.getStartsAt(), e.getEndsAt(),
                    e.getSlotMinutes(), e.getMode(), e.getLocation(), e.getStatus().name(), refs,
                    teachersOf(e.getId()).size(), slotCount, active.size(), attended, noShow,
                    e.getMeetingDate().isBefore(today));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void checkOpen(PtmEvent e, LocalDate today, String field) {
        if (e.isCancelled()) {
            throw ApiException.conflict("This meeting was cancelled.", field);
        }
        if (e.getMeetingDate().isBefore(today)) {
            throw ApiException.conflict("This meeting is over.", field);
        }
    }

    /** Checks the form and returns the meeting's details. A day in the past is allowed only if it is unchanged. */
    private static PtmEvent.Details details(EventForm form, LocalDate today, LocalDate currentDate) {
        if (form.date().isBefore(today) && !form.date().equals(currentDate)) {
            throw ApiException.badRequest("Choose today or a later day.", "date");
        }
        if (!form.endsAt().isAfter(form.startsAt())) {
            throw ApiException.badRequest("The end must be after the start.", "endsAt");
        }
        List<Range> breaks = new ArrayList<>();
        for (BreakForm b : form.breaks() == null ? List.<BreakForm>of() : form.breaks()) {
            if (!b.to().isAfter(b.from())) {
                throw ApiException.badRequest("Every break must end after it starts.", "breaks");
            }
            breaks.add(new Range(b.from(), b.to()));
        }
        String problem = PtmSlots.problem(form.startsAt(), form.endsAt(), breaks);
        if (problem != null) {
            throw ApiException.badRequest(problem, "breaks");
        }
        if (PtmSlots.generate(form.startsAt(), form.endsAt(), form.slotMinutes(), breaks).isEmpty()) {
            throw ApiException.badRequest("No slot of " + form.slotMinutes() + " minutes fits between the start and "
                    + "the end.", "slotMinutes");
        }
        String videoLink = blankToNull(form.videoLink());
        if (form.mode() == MeetingMode.VIDEO) {
            if (videoLink == null || !videoLink.startsWith("https://") || videoLink.contains(" ")) {
                throw ApiException.badRequest("Give the video link, starting with https://.", "videoLink");
            }
        } else {
            videoLink = null;
        }
        int cutoff = form.cutoffHours() == null ? 12 : form.cutoffHours();
        return new PtmEvent.Details(form.title().strip(), form.date(), form.startsAt(), form.endsAt(),
                form.slotMinutes(), PtmSlots.formatBreaks(breaks), form.mode(), blankToNull(form.location()),
                videoLink, Boolean.TRUE.equals(form.includeSubjectTeachers()), cutoff, blankToNull(form.note()));
    }

    private List<UUID> sectionIds(List<UUID> requested) {
        Map<UUID, SectionInfo> all = people.sectionsById();
        List<UUID> result = new ArrayList<>(new LinkedHashSet<>(requested));
        for (UUID id : result) {
            if (!all.containsKey(id)) {
                throw ApiException.badRequest("Choose classes of this school.", "sectionIds");
            }
        }
        return result;
    }

    /**
     * Makes the meeting's teachers match its sections: the class teacher of each, and the subject teachers if the
     * meeting includes them. A teacher who is no longer needed goes with their slots, unless a family has booked one
     * (409). Returns how many teachers the meeting has.
     */
    private int syncTeachers(PtmEvent e, List<UUID> sectionIds, Instant now, String field) {
        Map<UUID, SectionInfo> all = people.sectionsById();
        Map<UUID, String> subjectNames = people.subjectNames();
        Map<UUID, PtmTeacher.Kind> kinds = new LinkedHashMap<>();
        Map<UUID, Set<String>> subjects = new HashMap<>();
        for (UUID sectionId : sectionIds) {
            SectionInfo info = all.get(sectionId);
            Map<UUID, Set<UUID>> teaching = people.teachingInSection(sectionId, e.getAcademicYearId());
            teaching.forEach((teacherId, subjectIds) -> {
                boolean classTeacher = info != null && teacherId.equals(info.classTeacherId());
                if (classTeacher) {
                    kinds.put(teacherId, PtmTeacher.Kind.CLASS_TEACHER);
                } else if (e.isIncludeSubjectTeachers()) {
                    kinds.putIfAbsent(teacherId, PtmTeacher.Kind.SUBJECT_TEACHER);
                }
                subjectIds.stream().map(subjectNames::get).filter(Objects::nonNull)
                        .forEach(n -> subjects.computeIfAbsent(teacherId, k -> new TreeSet<>()).add(n));
            });
        }
        Map<UUID, Person> persons = people.people(kinds.keySet());
        kinds.keySet().removeIf(id -> persons.get(id) == null || !persons.get(id).active());
        Map<UUID, PtmTeacher> existing = teachers.findByEventId(e.getId()).stream()
                .collect(Collectors.toMap(PtmTeacher::getTeacherUserId, t -> t));
        for (PtmTeacher t : existing.values()) {
            if (kinds.containsKey(t.getTeacherUserId())) {
                continue;
            }
            for (PtmSlot s : slots.ofTeacher(t.getId())) {
                if (bookings.activeOfSlot(s.getId()).isPresent()) {
                    throw ApiException.conflict(nameOf(persons, t.getTeacherUserId(), people)
                            + " has booked meetings. Cancel them before taking the teacher out.", field);
                }
                removeSlot(s);
            }
            teachers.delete(t);
        }
        teachers.flush();
        int count = 0;
        for (Map.Entry<UUID, PtmTeacher.Kind> entry : kinds.entrySet()) {
            String subjectText = truncate(String.join(", ", subjects.getOrDefault(entry.getKey(), Set.of())), 300);
            PtmTeacher t = existing.get(entry.getKey());
            if (t == null) {
                t = new PtmTeacher(e.getId(), entry.getKey(), entry.getValue(), blankToNull(subjectText),
                        e.getStartsAt(), e.getEndsAt(), now);
            } else {
                t.describe(entry.getValue(), blankToNull(subjectText));
            }
            teachers.saveAndFlush(t);
            syncSlots(e, t, now, field);
            count++;
        }
        return count;
    }

    /**
     * Makes a teacher's slots match their hours (kept inside the meeting's hours) and the meeting's slot length and
     * breaks. Slots that still fit stay, with their bookings; the others go unless booked (409). Returns how many new
     * slots were made.
     */
    private int syncSlots(PtmEvent e, PtmTeacher t, Instant now, String field) {
        LocalTime from = t.getAvailableFrom().isBefore(e.getStartsAt()) ? e.getStartsAt() : t.getAvailableFrom();
        LocalTime to = t.getAvailableTo().isAfter(e.getEndsAt()) ? e.getEndsAt() : t.getAvailableTo();
        if (!to.isAfter(from)) {
            from = e.getStartsAt();
            to = e.getEndsAt();
        }
        if (!from.equals(t.getAvailableFrom()) || !to.equals(t.getAvailableTo())) {
            t.hours(from, to);
            teachers.saveAndFlush(t);
        }
        List<Range> desired = new ArrayList<>(PtmSlots.generate(from, to, e.getSlotMinutes(),
                PtmSlots.parseBreaks(e.getBreaks())));
        for (PtmSlot s : slots.ofTeacher(t.getId())) {
            Range range = new Range(s.getStartsAt(), s.getEndsAt());
            if (desired.remove(range)) {
                continue;
            }
            if (bookings.activeOfSlot(s.getId()).isPresent()) {
                throw ApiException.conflict("A family has booked " + s.getStartsAt() + " with "
                        + nameOf(people.people(List.of(t.getTeacherUserId())), t.getTeacherUserId(), people)
                        + ", which would no longer fit. Cancel or move that booking first.", field);
            }
            removeSlot(s);
        }
        slots.flush();
        for (Range r : desired) {
            slots.save(new PtmSlot(e.getId(), t.getId(), t.getTeacherUserId(), r.from(), r.to(), now));
        }
        slots.flush();
        return desired.size();
    }

    /** Removes a slot nobody has booked, with the cancelled bookings it had. */
    private void removeSlot(PtmSlot s) {
        List<PtmBooking> history = bookings.findBySlotId(s.getId());
        if (!history.isEmpty()) {
            bookings.deleteAll(history);
            bookings.flush();
        }
        slots.delete(s);
    }

    private static String nameOf(Map<UUID, Person> persons, UUID id, ChatPeople people) {
        Person p = persons.get(id);
        return p != null ? p.name() : people.person(id).map(Person::name).orElse("A teacher");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
