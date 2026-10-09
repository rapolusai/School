package com.akshara.communication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.communication.CalendarEntry.Details;
import com.akshara.communication.CommunicationTypes.CalendarAudience;
import com.akshara.communication.CommunicationTypes.EntryKind;
import com.akshara.communication.CommunicationTypes.ReminderChannel;
import com.akshara.communication.NoticeForms.EntryRequest;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;

/**
 * The school calendar: holidays, events, exams, PTMs and other entries on a day or a range of days. Staff see every
 * entry; parents and students see whole-school entries and those for their children's (or their own) classes, never
 * staff-only ones. calendar.manage adds, changes and removes entries; every change is audited.
 */
@Service
@Transactional
public class CalendarService {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    public static final int MAX_RANGE_DAYS = 400;
    public static final int MAX_SPAN_DAYS = 120;
    public static final int MAX_UPCOMING = 20;

    public record ClassRef(UUID id, String name) {
    }

    public record EntryView(UUID id, EntryKind kind, String title, String description, LocalDate startsOn,
            LocalDate endsOn, LocalTime startTime, LocalTime endTime, CalendarAudience audience,
            List<ClassRef> classes, Integer reminderDays, Set<ReminderChannel> reminderChannels,
            Instant reminderSentAt, UUID academicYearId, String createdByName, String updatedByName,
            Instant updatedAt) {
    }

    /** Entries between two dates. {@code classes} are the classes an entry can be for (for the edit form). */
    public record EntryList(LocalDate from, LocalDate to, boolean canManage, List<ClassRef> classes,
            List<EntryView> entries) {
    }

    /**
     * A holiday from the starter list. National holidays fall on fixed dates; festivals ({@code needsConfirmation})
     * must be checked against the state's list before they are added.
     */
    public record Suggestion(LocalDate date, String title, String group, boolean needsConfirmation,
            boolean alreadyAdded) {
    }

    public record Suggestions(UUID academicYearId, String academicYearName, LocalDate from, LocalDate to,
            List<Suggestion> items) {
    }

    /** Who is looking: staff see everything; families see the whole school's entries and their classes'. */
    private record Viewer(boolean everything, Set<UUID> classIds) {

        boolean sees(CalendarEntry e, Set<UUID> entryClasses) {
            if (everything) {
                return true;
            }
            return switch (e.getAudience()) {
                case SCHOOL -> true;
                case STAFF -> false;
                case CLASSES -> entryClasses.stream().anyMatch(classIds::contains);
            };
        }
    }

    private final CalendarEntryRepository entries;
    private final CalendarEntryClassRepository entryClasses;
    private final AcademicsDirectory academics;
    private final StudentRoster roster;
    private final TenantDirectory tenants;
    private final AuditService audit;

    CalendarService(CalendarEntryRepository entries, CalendarEntryClassRepository entryClasses,
            AcademicsDirectory academics, StudentRoster roster, TenantDirectory tenants, AuditService audit) {
        this.entries = entries;
        this.entryClasses = entryClasses;
        this.academics = academics;
        this.roster = roster;
        this.tenants = tenants;
        this.audit = audit;
    }

    /** Today in India. */
    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    // ------------------------------------------------------------------ reading

    /**
     * The entries the caller may see between two dates (both included). Without dates: the year given, else the
     * current academic year, else the next twelve months.
     */
    @Transactional(readOnly = true)
    public EntryList entries(NoticeCaller caller, LocalDate from, LocalDate to, UUID yearId) {
        TenantContext.require();
        LocalDate start = from;
        LocalDate end = to;
        if (start == null || end == null) {
            Optional<YearInfo> year = yearId != null ? academics.year(yearId) : academics.currentYear();
            if (yearId != null && year.isEmpty()) {
                throw ApiException.badRequest("Pick a year from the list.", "yearId");
            }
            LocalDate today = today();
            start = start != null ? start : year.map(YearInfo::startsOn).orElse(today.withDayOfMonth(1));
            end = end != null ? end : year.map(YearInfo::endsOn).orElse(start.plusMonths(12).minusDays(1));
        }
        if (end.isBefore(start)) {
            throw ApiException.badRequest("The end date must not be before the start date.", "to");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Choose at most " + MAX_RANGE_DAYS + " days.", "to");
        }
        Map<UUID, String> classNames = classNames();
        List<EntryView> views = visible(caller, entries.findOverlapping(start, end), classNames);
        List<ClassRef> classes = classNames.entrySet().stream().map(e -> new ClassRef(e.getKey(), e.getValue()))
                .toList();
        return new EntryList(start, end, caller.canManageCalendar(), classes, views);
    }

    /** The next entries the caller may see that have not ended, soonest first, for the dashboard. */
    @Transactional(readOnly = true)
    public List<EntryView> upcoming(NoticeCaller caller, LocalDate today, int limit) {
        TenantContext.require();
        int n = Math.max(1, Math.min(limit, MAX_UPCOMING));
        return visible(caller, entries.findFrom(today, Limit.of(300)), classNames()).stream().limit(n).toList();
    }

    @Transactional(readOnly = true)
    public EntryView entry(NoticeCaller caller, UUID id) {
        TenantContext.require();
        CalendarEntry e = entries.findById(id).orElseThrow(() -> ApiException.notFound("Calendar entry"));
        List<EntryView> views = visible(caller, List.of(e), classNames());
        if (views.isEmpty()) {
            throw ApiException.notFound("Calendar entry");
        }
        return views.getFirst();
    }

    /** The holiday starter list for an academic year (the current one by default), for the admin to review. */
    @Transactional(readOnly = true)
    public Suggestions suggestions(UUID yearId) {
        TenantContext.require();
        Optional<YearInfo> year = yearId != null ? academics.year(yearId) : academics.currentYear();
        if (year.isEmpty()) {
            throw ApiException.badRequest(yearId != null ? "Pick a year from the list."
                    : "Set up the current academic year in School setup first.", "yearId");
        }
        YearInfo y = year.get();
        Set<LocalDate> holidays = new HashSet<>(entries.startDatesOf(EntryKind.HOLIDAY, y.startsOn(), y.endsOn()));
        List<Suggestion> items = HolidayStarterList.between(y.startsOn(), y.endsOn()).stream()
                .map(h -> new Suggestion(h.date(), h.title(), h.group().name(), h.needsConfirmation(),
                        holidays.contains(h.date())))
                .toList();
        return new Suggestions(y.id(), y.name(), y.startsOn(), y.endsOn(), items);
    }

    /** The caller's calendar as an iCalendar file: entries from two months back to a year ahead. */
    @Transactional(readOnly = true)
    public String ics(NoticeCaller caller, LocalDate today, Instant now) {
        TenantContext.require();
        List<EntryView> views = visible(caller, entries.findOverlapping(today.minusMonths(2), today.plusYears(1)),
                classNames());
        String school = tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("School");
        List<IcsWriter.Event> events = views.stream().map(v -> new IcsWriter.Event(v.id(), v.title(),
                v.description(), v.kind().name(), v.startsOn(), v.endsOn(), v.startTime(), v.endTime(),
                v.updatedAt())).toList();
        return IcsWriter.write(school + " calendar", INDIA, events, now);
    }

    // ------------------------------------------------------------------ writing

    public EntryView create(NoticeCaller caller, EntryRequest request) {
        TenantContext.require();
        requireManage(caller);
        return add(caller, request, classNames(), false);
    }

    /** Adds several entries, for example holidays from the starter list. All are added or, on any error, none. */
    public List<EntryView> createAll(NoticeCaller caller, List<EntryRequest> requests) {
        TenantContext.require();
        requireManage(caller);
        if (requests == null || requests.isEmpty() || requests.size() > NoticeForms.MAX_BULK) {
            throw ApiException.badRequest("Add between 1 and " + NoticeForms.MAX_BULK + " entries at a time.",
                    "entries");
        }
        Map<UUID, String> classNames = classNames();
        List<EntryView> added = new ArrayList<>();
        for (EntryRequest r : requests) {
            added.add(add(caller, r, classNames, true));
        }
        return added;
    }

    public EntryView update(NoticeCaller caller, UUID id, EntryRequest request) {
        TenantContext.require();
        requireManage(caller);
        CalendarEntry e = entries.findById(id).orElseThrow(() -> ApiException.notFound("Calendar entry"));
        Map<UUID, String> classNames = classNames();
        Details details = check(request, classNames);
        e.update(details, caller.actor());
        entries.saveAndFlush(e);
        saveClasses(e, request);
        audit.record(caller.actor(), "calendar_entry.updated", "calendar_entry", e.getId(), describe(e));
        return view(e, classIdsOf(e), classNames);
    }

    public void delete(NoticeCaller caller, UUID id) {
        TenantContext.require();
        requireManage(caller);
        CalendarEntry e = entries.findById(id).orElseThrow(() -> ApiException.notFound("Calendar entry"));
        Map<String, Object> details = describe(e);
        entries.delete(e);
        entries.flush();
        audit.record(caller.actor(), "calendar_entry.deleted", "calendar_entry", id, details);
    }

    // ------------------------------------------------------------------ helpers

    private EntryView add(NoticeCaller caller, EntryRequest request, Map<UUID, String> classNames, boolean bulk) {
        Details details = check(request, classNames);
        CalendarEntry e = entries.saveAndFlush(new CalendarEntry(details, caller.actor()));
        saveClasses(e, request);
        Map<String, Object> audited = describe(e);
        if (bulk) {
            audited.put("bulk", true);
        }
        audit.record(caller.actor(), "calendar_entry.created", "calendar_entry", e.getId(), audited);
        return view(e, classIdsOf(e), classNames);
    }

    private void saveClasses(CalendarEntry e, EntryRequest request) {
        entryClasses.deleteByEntry(e.getId());
        if (request.audience() == CalendarAudience.CLASSES) {
            entryClasses.saveAll(new LinkedHashSet<>(request.classIds()).stream()
                    .map(classId -> new CalendarEntryClass(e.getId(), classId)).toList());
            entryClasses.flush();
        }
    }

    /** Checks the request (400 with the field) and works out the academic year the entry starts in. */
    private Details check(EntryRequest r, Map<UUID, String> classNames) {
        String title = CircularService.cleanTitle(r.title());
        if (title.isEmpty()) {
            throw ApiException.badRequest("Give the entry a title.", "title");
        }
        String description = r.description() == null ? null : CircularService.cleanBody(r.description());
        if (description != null && description.isEmpty()) {
            description = null;
        }
        if (description != null && description.length() > 2000) {
            throw ApiException.badRequest("Keep the description to 2,000 characters.", "description");
        }
        if (r.kind() == null) {
            throw ApiException.badRequest("Choose what kind of entry this is.", "kind");
        }
        LocalDate startsOn = r.startsOn();
        if (startsOn == null) {
            throw ApiException.badRequest("Give the date.", "startsOn");
        }
        if (startsOn.getYear() < 2000 || startsOn.getYear() > 2100) {
            throw ApiException.badRequest("Pick a date between 2000 and 2100.", "startsOn");
        }
        LocalDate endsOn = r.endsOn() != null ? r.endsOn() : startsOn;
        if (endsOn.isBefore(startsOn)) {
            throw ApiException.badRequest("The end date must not be before the start date.", "endsOn");
        }
        if (ChronoUnit.DAYS.between(startsOn, endsOn) > MAX_SPAN_DAYS) {
            throw ApiException.badRequest("An entry can span at most " + (MAX_SPAN_DAYS + 1) + " days.", "endsOn");
        }
        if (r.endTime() != null && r.startTime() == null) {
            throw ApiException.badRequest("Give a start time too, or leave both times empty.", "startTime");
        }
        if (r.endTime() != null && endsOn.equals(startsOn) && !r.endTime().isAfter(r.startTime())) {
            throw ApiException.badRequest("The end time must be after the start time.", "endTime");
        }
        if (r.audience() == null) {
            throw ApiException.badRequest("Choose who the entry is for.", "audience");
        }
        if (r.audience() == CalendarAudience.CLASSES) {
            if (r.classIds().isEmpty()) {
                throw ApiException.badRequest("Choose at least one class.", "classIds");
            }
            if (!classNames.keySet().containsAll(r.classIds())) {
                throw ApiException.badRequest("Pick classes from the list.", "classIds");
            }
        }
        Integer reminderDays = r.reminderDays();
        if (reminderDays != null && (reminderDays < 1 || reminderDays > 30)) {
            throw ApiException.badRequest("Remind between 1 and 30 days before.", "reminderDays");
        }
        Set<ReminderChannel> channels = reminderDays == null ? Set.of() : r.reminderChannels();
        UUID yearId = academics.years().stream()
                .filter(y -> !startsOn.isBefore(y.startsOn()) && !startsOn.isAfter(y.endsOn()))
                .map(YearInfo::id).findFirst().orElse(null);
        return new Details(r.kind(), title, description, startsOn, endsOn, r.startTime(),
                r.startTime() == null ? null : r.endTime(), r.audience(), reminderDays, channels, yearId);
    }

    private static void requireManage(NoticeCaller caller) {
        if (!caller.canManageCalendar()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed", "You do not have permission to do this.");
        }
    }

    /** The classes that have sections, in class order, by id. */
    private Map<UUID, String> classNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (SectionInfo s : academics.sections()) {
            names.putIfAbsent(s.classId(), s.className());
        }
        return names;
    }

    private Viewer viewer(NoticeCaller caller) {
        if (caller.staff() || caller.canManageCalendar()) {
            return new Viewer(true, Set.of());
        }
        Set<UUID> sectionIds = academics.currentYear().map(y -> roster.sectionIdsOf(caller.userId(), y.id()))
                .orElse(Set.of());
        if (sectionIds.isEmpty()) {
            return new Viewer(false, Set.of());
        }
        Set<UUID> classIds = academics.sections().stream().filter(s -> sectionIds.contains(s.id()))
                .map(SectionInfo::classId).collect(Collectors.toSet());
        return new Viewer(false, classIds);
    }

    private List<EntryView> visible(NoticeCaller caller, List<CalendarEntry> found, Map<UUID, String> classNames) {
        if (found.isEmpty()) {
            return List.of();
        }
        Viewer viewer = viewer(caller);
        Map<UUID, Set<UUID>> classesByEntry = new LinkedHashMap<>();
        entryClasses.findByEntryIdIn(found.stream().map(CalendarEntry::getId).toList())
                .forEach(c -> classesByEntry.computeIfAbsent(c.getEntryId(), k -> new LinkedHashSet<>())
                        .add(c.getClassId()));
        return found.stream()
                .filter(e -> viewer.sees(e, classesByEntry.getOrDefault(e.getId(), Set.of())))
                .map(e -> view(e, classesByEntry.getOrDefault(e.getId(), Set.of()), classNames))
                .toList();
    }

    private Set<UUID> classIdsOf(CalendarEntry e) {
        return entryClasses.findByEntryIdIn(List.of(e.getId())).stream().map(CalendarEntryClass::getClassId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static EntryView view(CalendarEntry e, Set<UUID> classIds, Map<UUID, String> classNames) {
        List<ClassRef> classes = classIds.stream().map(id -> new ClassRef(id, classNames.getOrDefault(id, "")))
                .toList();
        return new EntryView(e.getId(), e.getKind(), e.getTitle(), e.getDescription(), e.getStartsOn(),
                e.getEndsOn(), e.getStartTime(), e.getEndTime(), e.getAudience(), classes, e.getReminderDays(),
                e.reminderChannels(), e.getReminderSentAt(), e.getAcademicYearId(), e.getCreatedByName(),
                e.getUpdatedByName(), e.getUpdatedAt());
    }

    private static Map<String, Object> describe(CalendarEntry e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", e.getTitle());
        details.put("kind", e.getKind().name());
        details.put("startsOn", e.getStartsOn().toString());
        details.put("endsOn", e.getEndsOn().toString());
        details.put("audience", e.getAudience().name());
        return details;
    }
}
