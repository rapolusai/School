package com.akshara.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Day-wise attendance: one register per section per school day with one mark per enrolled student. Saving again
 * keeps the latest marks. Every save is audited with the counts before and after, queues absence alerts for students
 * who are newly absent, skips alerts that are no longer needed, and publishes {@link AttendanceSaved}.
 */
@Service
@Transactional
public class AttendanceService {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    public static final int MAX_ENTRIES = 500;

    public record EntryInput(UUID studentId, AttendanceStatus status) {
    }

    public record YearRef(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {

        static YearRef of(YearInfo y) {
            return new YearRef(y.id(), y.name(), y.startsOn(), y.endsOn());
        }
    }

    /** A section on the day picker: whether it is marked, by whom and with what result. */
    public record SectionDay(UUID sectionId, UUID classId, String className, String sectionName, String label,
            String classTeacherName, long students, boolean marked, String markedByName, Instant markedAt,
            AttendanceCounts counts, Double presentPercent, boolean canMark) {
    }

    public record SectionsForDay(LocalDate date, LocalDate today, YearRef academicYear, boolean canMark,
            List<SectionDay> sections) {
    }

    /** One line of a register. {@code inSection} is false for a student marked earlier who has since left. */
    public record RegisterEntry(UUID studentId, String fullName, String admissionNo, Integer rollNo,
            boolean inSection, AttendanceStatus status) {
    }

    public record RegisterView(UUID sectionId, UUID classId, String className, String sectionName, String label,
            LocalDate date, String academicYearName, boolean marked, String markedByName, Instant markedAt,
            String updatedByName, Instant updatedAt, boolean canEdit, AttendanceCounts counts, int unmarked,
            Double presentPercent, List<RegisterEntry> entries) {
    }

    public record SaveResult(RegisterView register, boolean firstSave, int changed, int alertsQueued,
            int alertsCancelled) {
    }

    private final AttendanceRegisterRepository registers;
    private final AttendanceEntryRepository entries;
    private final AcademicsDirectory academics;
    private final StudentRoster roster;
    private final AbsenceAlerts alerts;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public AttendanceService(AttendanceRegisterRepository registers, AttendanceEntryRepository entries,
            AcademicsDirectory academics, StudentRoster roster, AbsenceAlerts alerts, AuditService audit,
            ApplicationEventPublisher events) {
        this.registers = registers;
        this.entries = entries;
        this.academics = academics;
        this.roster = roster;
        this.alerts = alerts;
        this.audit = audit;
        this.events = events;
    }

    /** Today in India, the school day attendance is marked for. */
    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    // ------------------------------------------------------------------ the day picker

    /** The sections the caller may see, with each one's register state for the day. */
    @Transactional(readOnly = true)
    public SectionsForDay sectionsFor(AttendanceScope scope, LocalDate date) {
        TenantContext.require();
        Optional<YearInfo> current = academics.currentYear();
        if (current.isEmpty()) {
            return new SectionsForDay(date, today(), null, scope.canMark(), List.of());
        }
        YearInfo year = yearFor(date);
        List<SectionInfo> visible = academics.sections().stream().filter(s -> scope.canRead(s.id())).toList();
        Map<UUID, AttendanceRegister> bySection = registers.findByAttendanceDate(date).stream()
                .collect(Collectors.toMap(AttendanceRegister::getSectionId, Function.identity()));
        Map<UUID, AttendanceCounts> counts = countsPerRegister(bySection.values().stream()
                .map(AttendanceRegister::getId).toList());
        Map<UUID, Long> enrolled = roster.activePerSection(year.id());
        List<SectionDay> days = visible.stream().map(s -> {
            AttendanceRegister r = bySection.get(s.id());
            AttendanceCounts c = r == null ? AttendanceCounts.NONE : counts.getOrDefault(r.getId(), AttendanceCounts.NONE);
            return new SectionDay(s.id(), s.classId(), s.className(), s.name(), s.label(), s.classTeacherName(),
                    enrolled.getOrDefault(s.id(), 0L), r != null, r == null ? null : r.getMarkedByName(),
                    r == null ? null : r.getMarkedAt(), c, c.presentPercent(), scope.canMark(s.id()));
        }).toList();
        return new SectionsForDay(date, today(), YearRef.of(year), scope.canMark(), days);
    }

    // ------------------------------------------------------------------ one register

    @Transactional(readOnly = true)
    public RegisterView register(AttendanceScope scope, UUID sectionId, LocalDate date) {
        TenantContext.require();
        SectionInfo section = section(sectionId);
        if (!scope.canRead(sectionId)) {
            throw new AccessDeniedException("Not this person's section");
        }
        YearInfo year = yearFor(date);
        AttendanceRegister register = registers.findBySectionIdAndAttendanceDate(sectionId, date).orElse(null);
        Map<UUID, AttendanceStatus> marks = register == null ? Map.of() : entries.findByRegisterId(register.getId())
                .stream().collect(Collectors.toMap(AttendanceEntry::getStudentId, AttendanceEntry::getStatus));
        return view(scope, section, date, year, register, marks, roster.activeInSection(year.id(), sectionId));
    }

    /**
     * Saves a section's register for a day: every student enrolled in the section this year must have a mark.
     * Students marked earlier who have since left may be included. {@code actor} null means the signed-in person.
     */
    public SaveResult save(AttendanceScope scope, UUID sectionId, LocalDate date, List<EntryInput> input, Actor actor,
            Instant at) {
        TenantContext.require();
        SectionInfo section = section(sectionId);
        if (!scope.canMark(sectionId)) {
            throw new AccessDeniedException("Not this person's section");
        }
        YearInfo year = yearFor(date);
        Actor by = actor != null ? actor : new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(null));

        AttendanceRegister register = registers.lockBySectionAndDate(sectionId, date).orElse(null);
        boolean first = register == null;
        Map<UUID, AttendanceEntry> existing = first ? Map.of() : entries.findByRegisterId(register.getId()).stream()
                .collect(Collectors.toMap(AttendanceEntry::getStudentId, Function.identity()));
        List<RosterStudent> rosterList = roster.activeInSection(year.id(), sectionId);
        Map<UUID, RosterStudent> rosterById = rosterList.stream()
                .collect(Collectors.toMap(RosterStudent::id, Function.identity()));
        Map<UUID, AttendanceStatus> wanted = checkInput(section, input, rosterList, rosterById, existing);

        Map<UUID, AttendanceStatus> before = new HashMap<>();
        existing.forEach((id, e) -> before.put(id, e.getStatus()));
        if (first) {
            register = registers.saveAndFlush(new AttendanceRegister(sectionId, year.id(), date, by, at));
        }
        int changed = 0;
        List<UUID> newlyAbsent = new ArrayList<>();
        Map<UUID, AttendanceStatus> noLongerAbsent = new LinkedHashMap<>();
        for (Map.Entry<UUID, AttendanceStatus> w : wanted.entrySet()) {
            AttendanceEntry entry = existing.get(w.getKey());
            AttendanceStatus old = entry == null ? null : entry.getStatus();
            if (entry == null) {
                entries.save(new AttendanceEntry(register.getId(), w.getKey(), w.getValue(), at));
                changed++;
            } else if (entry.change(w.getValue(), at)) {
                changed++;
            }
            if (w.getValue() == AttendanceStatus.ABSENT && old != AttendanceStatus.ABSENT) {
                newlyAbsent.add(w.getKey());
            }
            if (old == AttendanceStatus.ABSENT && w.getValue() != AttendanceStatus.ABSENT) {
                noLongerAbsent.put(w.getKey(), w.getValue());
            }
        }
        Map<UUID, AttendanceStatus> after = new HashMap<>(before);
        after.putAll(wanted);
        AttendanceCounts beforeCounts = AttendanceCounts.of(before.values());
        AttendanceCounts afterCounts = AttendanceCounts.of(after.values());

        AbsenceAlerts.Result alerted = new AbsenceAlerts.Result(0, 0);
        if (first || changed > 0) {
            if (!first) {
                register.edited(by, at);
            }
            registers.flush();
            entries.flush();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("section", section.label());
            details.put("date", date.toString());
            if (first) {
                details.putAll(countDetails(afterCounts));
                audit.record(by, "attendance.marked", "attendance_register", register.getId(), details);
            } else {
                details.put("changed", changed);
                details.put("before", countDetails(beforeCounts));
                details.put("after", countDetails(afterCounts));
                audit.record(by, "attendance.updated", "attendance_register", register.getId(), details);
            }
            Map<UUID, RosterStudent> absentees = new HashMap<>();
            newlyAbsent.stream().filter(rosterById::containsKey).forEach(id -> absentees.put(id, rosterById.get(id)));
            List<UUID> leavers = newlyAbsent.stream().filter(id -> !rosterById.containsKey(id)).toList();
            absentees.putAll(roster.students(leavers, year.id()));
            alerted = alerts.apply(section, date, newlyAbsent.stream().map(absentees::get)
                    .filter(Objects::nonNull).toList(), noLongerAbsent, at);
            events.publishEvent(new AttendanceSaved(TenantContext.require(), register.getId(), sectionId, date, first,
                    afterCounts));
        }
        RegisterView view = view(scope, section, date, year, register, after, rosterList);
        return new SaveResult(view, first, changed, alerted.queued(), alerted.cancelled());
    }

    private Map<UUID, AttendanceStatus> checkInput(SectionInfo section, List<EntryInput> input,
            List<RosterStudent> rosterList, Map<UUID, RosterStudent> rosterById,
            Map<UUID, AttendanceEntry> existing) {
        if (rosterList.isEmpty() && existing.isEmpty()) {
            throw ApiException.badRequest("No students are enrolled in " + section.label() + " this year.", "entries");
        }
        if (input.size() > MAX_ENTRIES) {
            throw ApiException.badRequest("A register can have at most " + MAX_ENTRIES + " students.", "entries");
        }
        Map<UUID, AttendanceStatus> wanted = new LinkedHashMap<>();
        for (EntryInput e : input) {
            if (e.studentId() == null || e.status() == null) {
                throw ApiException.badRequest("Give every student a mark.", "entries");
            }
            if (wanted.put(e.studentId(), e.status()) != null) {
                throw ApiException.badRequest("Each student can be marked only once.", "entries");
            }
            if (!rosterById.containsKey(e.studentId()) && !existing.containsKey(e.studentId())) {
                throw ApiException.badRequest("Mark only students of " + section.label() + ".", "entries");
            }
        }
        long missing = rosterList.stream().filter(s -> !wanted.containsKey(s.id())).count();
        if (missing > 0) {
            throw ApiException.badRequest("Mark every student of " + section.label() + " (" + missing
                    + (missing == 1 ? " student is" : " students are")
                    + " not marked). If the class list changed, reload and try again.", "entries");
        }
        return wanted;
    }

    // ------------------------------------------------------------------ shared helpers

    SectionInfo section(UUID sectionId) {
        return academics.section(sectionId).orElseThrow(() -> ApiException.notFound("Section"));
    }

    /** The current academic year, when the date is a school day of it that is not in the future. */
    YearInfo yearFor(LocalDate date) {
        YearInfo year = academics.currentYear().orElseThrow(() -> ApiException.badRequest(
                "Set up the current academic year in School setup first.", "date"));
        if (date.isAfter(today())) {
            throw ApiException.badRequest("Attendance cannot be marked for a future date.", "date");
        }
        if (date.isBefore(year.startsOn()) || date.isAfter(year.endsOn())) {
            throw ApiException.badRequest("Pick a date in the current academic year, " + year.name() + " ("
                    + year.startsOn() + " to " + year.endsOn() + ").", "date");
        }
        return year;
    }

    /** Marks of each kind per register id. */
    Map<UUID, AttendanceCounts> countsPerRegister(List<UUID> registerIds) {
        if (registerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AttendanceCounts> result = new HashMap<>();
        for (Object[] row : entries.countPerRegister(registerIds)) {
            UUID id = (UUID) row[0];
            result.merge(id, AttendanceCounts.NONE.plus((AttendanceStatus) row[1], ((Number) row[2]).intValue()),
                    AttendanceCounts::plus);
        }
        return result;
    }

    static Map<String, Object> countDetails(AttendanceCounts c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", c.present());
        m.put("absent", c.absent());
        m.put("late", c.late());
        m.put("halfDay", c.halfDay());
        m.put("leave", c.leave());
        return m;
    }

    private RegisterView view(AttendanceScope scope, SectionInfo section, LocalDate date, YearInfo year,
            AttendanceRegister register, Map<UUID, AttendanceStatus> marks, List<RosterStudent> rosterList) {
        List<RegisterEntry> rows = new ArrayList<>();
        Set<UUID> listed = rosterList.stream().map(RosterStudent::id).collect(Collectors.toSet());
        rosterList.forEach(s -> rows.add(new RegisterEntry(s.id(), s.fullName(), s.admissionNo(), s.rollNo(), true,
                marks.get(s.id()))));
        List<UUID> others = marks.keySet().stream().filter(id -> !listed.contains(id)).toList();
        roster.students(others, year.id()).values().stream()
                .sorted(Comparator.comparing(s -> s.fullName().toLowerCase(Locale.ROOT)))
                .forEach(s -> rows.add(new RegisterEntry(s.id(), s.fullName(), s.admissionNo(), s.rollNo(), false,
                        marks.get(s.id()))));
        AttendanceCounts counts = AttendanceCounts.of(marks.values());
        int unmarked = (int) rows.stream().filter(r -> r.status() == null).count();
        return new RegisterView(section.id(), section.classId(), section.className(), section.name(), section.label(),
                date, year.name(), register != null, register == null ? null : register.getMarkedByName(),
                register == null ? null : register.getMarkedAt(), register == null ? null : register.getUpdatedByName(),
                register == null ? null : register.getEditedAt(), scope.canMark(section.id()), counts, unmarked,
                counts.presentPercent(), rows);
    }
}
