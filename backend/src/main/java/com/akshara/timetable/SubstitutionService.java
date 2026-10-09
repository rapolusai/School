package com.akshara.timetable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.TeacherRef;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;
import com.akshara.timetable.SubstituteRanking.Candidate;
import com.akshara.timetable.TimetableSupport.Names;
import com.akshara.timetable.TimetableViews.AbsenceView;
import com.akshara.timetable.TimetableViews.AffectedPeriod;
import com.akshara.timetable.TimetableViews.DayEntry;
import com.akshara.timetable.TimetableViews.DayPeriod;
import com.akshara.timetable.TimetableViews.FreeTeacher;
import com.akshara.timetable.TimetableViews.FreeTeachers;
import com.akshara.timetable.TimetableViews.SubstituteRef;
import com.akshara.timetable.TimetableViews.SubstitutionDay;
import com.akshara.timetable.TimetableViews.TeacherDay;

/**
 * A day's timetable with substitutions: teachers marked away, the periods they would have taught, the free teachers
 * who could cover each one (same subject first, then the least busy that day) and the substitutes chosen. A
 * substitute sees the class in their own day's schedule.
 */
@Service
@Transactional
public class SubstitutionService {

    static final String CLASS = "CLASS";
    static final String SUBSTITUTION = "SUBSTITUTION";
    static final String COVERED = "COVERED";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private final TeacherAbsenceRepository absences;
    private final SubstitutionRepository substitutions;
    private final SlotRepository slots;
    private final TeacherAssignmentRepository assignments;
    private final TimetableSupport support;
    private final AuditService audit;

    SubstitutionService(TeacherAbsenceRepository absences, SubstitutionRepository substitutions, SlotRepository slots,
            TeacherAssignmentRepository assignments, TimetableSupport support, AuditService audit) {
        this.absences = absences;
        this.substitutions = substitutions;
        this.slots = slots;
        this.assignments = assignments;
        this.support = support;
        this.audit = audit;
    }

    /** Everything about one day that the substitution logic needs, loaded once. */
    private final class Day {

        final LocalDate date;
        final DayOfWeek dayOfWeek;
        final Bells bells;
        final YearInfo year;
        final List<Slot> slots;
        final List<TeacherAbsence> away;
        final List<Substitution> subs;

        Day(LocalDate date) {
            this.date = date;
            this.dayOfWeek = date.getDayOfWeek();
            this.bells = support.bells();
            this.year = support.currentYearIfAny().orElse(null);
            this.slots = year == null || !bells.isWorking(dayOfWeek) ? List.of()
                    : SubstitutionService.this.slots.findByAcademicYearIdAndDayOfWeek(year.id(), dayOfWeek.getValue());
            this.away = absences.findByAbsenceDate(date);
            this.subs = substitutions.findBySubDate(date);
        }

        Set<UUID> awayIds() {
            return away.stream().map(TeacherAbsence::getTeacherId).collect(Collectors.toSet());
        }

        /** Classes plus substitutions each teacher has on the day. */
        Map<UUID, Integer> load() {
            Map<UUID, Integer> load = new HashMap<>();
            slots.stream().filter(s -> s.getTeacherId() != null)
                    .forEach(s -> load.merge(s.getTeacherId(), 1, Integer::sum));
            subs.forEach(s -> load.merge(s.getSubstituteTeacherId(), 1, Integer::sum));
            return load;
        }

        /** Active teachers with no class, no substitution and no absence in the period, best substitutes first. */
        List<FreeTeacher> free(int period, UUID subjectId, Collection<TeacherRef> teachers, Set<UUID> subjectTeachers) {
            Set<UUID> busy = new HashSet<>(awayIds());
            slots.stream().filter(s -> s.getPeriodNo() == period && s.getTeacherId() != null)
                    .forEach(s -> busy.add(s.getTeacherId()));
            subs.stream().filter(s -> s.getPeriodNo() == period).forEach(s -> busy.add(s.getSubstituteTeacherId()));
            Map<UUID, Integer> load = load();
            List<Candidate> candidates = teachers.stream()
                    .filter(t -> !busy.contains(t.id()))
                    .map(t -> new Candidate(t.id(), t.name(), subjectId != null && subjectTeachers.contains(t.id()),
                            load.getOrDefault(t.id(), 0)))
                    .toList();
            return SubstituteRanking.rank(candidates).stream()
                    .map(c -> new FreeTeacher(c.teacherId(), c.name(), c.periodsThatDay(), c.teachesSubject()))
                    .toList();
        }
    }

    /** The teachers of a subject this year: from teacher assignments and the timetable. */
    private Set<UUID> teachersOf(UUID yearId, UUID subjectId) {
        if (yearId == null || subjectId == null) {
            return Set.of();
        }
        Set<UUID> result = new HashSet<>();
        assignments.findByAcademicYearId(yearId).stream().filter(a -> a.getSubjectId().equals(subjectId))
                .forEach(a -> result.add(a.getTeacherId()));
        return result;
    }

    // ------------------------------------------------------------------ free teachers

    /** Teachers free in a period on a date (absences and substitutions counted) or on a weekday in general. */
    @Transactional(readOnly = true)
    public FreeTeachers freeTeachers(LocalDate date, DayOfWeek weekday, int period, UUID subjectId) {
        TenantContext.require();
        if (date == null && weekday == null) {
            throw ApiException.badRequest("Pick a date or a day of the week.", "date");
        }
        Day day = date != null ? new Day(date) : null;
        Bells bells = day != null ? day.bells : support.bells();
        DayOfWeek dow = date != null ? date.getDayOfWeek() : weekday;
        if (!bells.isWorking(dow)) {
            throw ApiException.badRequest("The school is closed on " + TimetableService.dayName(dow) + ".", "date");
        }
        if (period < 1 || period > bells.teachingPeriods(dow)) {
            throw ApiException.badRequest(TimetableService.dayName(dow) + " has no period " + period + ".", "period");
        }
        if (day == null) {
            // A weekday in general: only the weekly timetable counts.
            YearInfo year = support.currentYearIfAny().orElse(null);
            List<Slot> daySlots = year == null ? List.of()
                    : slots.findByAcademicYearIdAndDayOfWeek(year.id(), dow.getValue());
            Set<UUID> busy = daySlots.stream().filter(s -> s.getPeriodNo() == period && s.getTeacherId() != null)
                    .map(Slot::getTeacherId).collect(Collectors.toSet());
            Map<UUID, Integer> load = new HashMap<>();
            daySlots.stream().filter(s -> s.getTeacherId() != null)
                    .forEach(s -> load.merge(s.getTeacherId(), 1, Integer::sum));
            Set<UUID> subjectTeachers = teachersOf(year == null ? null : year.id(), subjectId);
            List<Candidate> candidates = support.activeTeachers().stream().filter(t -> !busy.contains(t.id()))
                    .map(t -> new Candidate(t.id(), t.name(), subjectId != null && subjectTeachers.contains(t.id()),
                            load.getOrDefault(t.id(), 0)))
                    .toList();
            return new FreeTeachers(null, dow, period, SubstituteRanking.rank(candidates).stream()
                    .map(c -> new FreeTeacher(c.teacherId(), c.name(), c.periodsThatDay(), c.teachesSubject()))
                    .toList());
        }
        Set<UUID> subjectTeachers = teachersOf(day.year == null ? null : day.year.id(), subjectId);
        return new FreeTeachers(date, dow, period, day.free(period, subjectId, support.activeTeachers(),
                subjectTeachers));
    }

    // ------------------------------------------------------------------ the substitution sheet

    /** Who is away on the date, the periods to cover, their substitutes and suggestions. */
    @Transactional(readOnly = true)
    public SubstitutionDay day(LocalDate date) {
        TenantContext.require();
        Day day = new Day(date);
        List<TeacherRef> teachers = support.activeTeachers();
        Set<UUID> nameIds = new HashSet<>();
        day.away.forEach(a -> nameIds.add(a.getTeacherId()));
        day.subs.forEach(s -> nameIds.add(s.getSubstituteTeacherId()));
        Names names = support.names(nameIds);
        Map<String, Substitution> subByPeriod = day.subs.stream()
                .collect(Collectors.toMap(s -> s.getSectionId() + "|" + s.getPeriodNo(), Function.identity(),
                        (a, b) -> a));
        Map<UUID, Set<UUID>> subjectTeachers = new HashMap<>();
        if (day.year != null) {
            assignments.findByAcademicYearId(day.year.id()).forEach(a -> subjectTeachers
                    .computeIfAbsent(a.getSubjectId(), k -> new HashSet<>()).add(a.getTeacherId()));
        }
        int toCover = 0;
        int covered = 0;
        List<AbsenceView> views = new ArrayList<>();
        List<TeacherAbsence> sorted = day.away.stream()
                .sorted(Comparator.comparing(a -> Objects.toString(names.teacher(a.getTeacherId()), ""))).toList();
        for (TeacherAbsence absence : sorted) {
            List<AffectedPeriod> periods = new ArrayList<>();
            List<Slot> own = day.slots.stream()
                    .filter(s -> absence.getTeacherId().equals(s.getTeacherId()))
                    .sorted(Comparator.comparingInt(Slot::getPeriodNo)).toList();
            for (Slot slot : own) {
                Period p = day.bells.period(day.dayOfWeek, slot.getPeriodNo()).orElse(null);
                Substitution sub = subByPeriod.get(slot.getSectionId() + "|" + slot.getPeriodNo());
                List<FreeTeacher> suggestions = day.free(slot.getPeriodNo(), slot.getSubjectId(), teachers,
                        subjectTeachers.getOrDefault(slot.getSubjectId(), Set.of()));
                toCover++;
                if (sub != null) {
                    covered++;
                }
                periods.add(new AffectedPeriod(slot.getPeriodNo(), p == null ? null : p.getLabel(),
                        p == null ? null : Bells.time(p.getStartsAt()), p == null ? null : Bells.time(p.getEndsAt()),
                        slot.getSectionId(), names.section(slot.getSectionId()), slot.getSubjectId(),
                        names.subject(slot.getSubjectId()), slot.getRoom(),
                        sub == null ? null : new SubstituteRef(sub.getId(), sub.getSubstituteTeacherId(),
                                names.teacher(sub.getSubstituteTeacherId())),
                        suggestions));
            }
            views.add(new AbsenceView(absence.getId(), absence.getTeacherId(), names.teacher(absence.getTeacherId()),
                    absence.getReason(), periods));
        }
        return new SubstitutionDay(date, day.dayOfWeek, day.bells.isWorking(day.dayOfWeek), views, toCover, covered,
                teachers.stream().map(t -> new TimetableViews.TeacherRef(t.id(), t.name())).toList());
    }

    /** Marks a teacher away for a date. Substitutions they were giving that day are cancelled. */
    public SubstitutionDay recordAbsence(LocalDate date, UUID teacherId, String reason) {
        TenantContext.require();
        support.lock();
        checkDate(date);
        support.checkTeacher(teacherId, "teacherId");
        String cleanReason = reason == null || reason.isBlank() ? null : reason.strip().replaceAll("\\s+", " ");
        if (cleanReason != null && cleanReason.length() > 200) {
            throw ApiException.badRequest("Keep the reason to 200 characters.", "reason");
        }
        Names names = support.names(List.of(teacherId));
        if (absences.findByAbsenceDateAndTeacherId(date, teacherId).isPresent()) {
            throw ApiException.conflict(names.teacher(teacherId) + " is already marked away on " + DATE.format(date)
                    + ".", "teacherId");
        }
        List<Substitution> giving = substitutions.findBySubDateAndSubstituteTeacherId(date, teacherId);
        substitutions.deleteAll(giving);
        substitutions.flush();
        TeacherAbsence absence = absences.saveAndFlush(new TeacherAbsence(date, teacherId, cleanReason, actor()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("teacher", names.teacher(teacherId));
        details.put("date", date.toString());
        if (cleanReason != null) {
            details.put("reason", cleanReason);
        }
        if (!giving.isEmpty()) {
            details.put("substitutionsCancelled", giving.size());
        }
        audit.record("teacher_absence.recorded", "teacher_absence", absence.getId(), details);
        return day(date);
    }

    /** Removes a teacher's absence and the substitutions arranged for it. */
    public SubstitutionDay removeAbsence(UUID absenceId) {
        TenantContext.require();
        support.lock();
        TeacherAbsence absence = absences.findById(absenceId).orElseThrow(() -> ApiException.notFound("Absence"));
        LocalDate date = absence.getAbsenceDate();
        List<Substitution> arranged = substitutions.findBySubDate(date).stream()
                .filter(s -> s.getAbsenceId().equals(absenceId)).toList();
        substitutions.deleteAll(arranged);
        substitutions.flush();
        absences.delete(absence);
        absences.flush();
        Names names = support.names(List.of(absence.getTeacherId()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("teacher", names.teacher(absence.getTeacherId()));
        details.put("date", date.toString());
        details.put("substitutionsRemoved", arranged.size());
        audit.record("teacher_absence.removed", "teacher_absence", absenceId, details);
        return day(date);
    }

    /**
     * Puts a substitute in an absent teacher's period. The substitute must be an active teacher who is not away and
     * has no class or other substitution in that period (409 otherwise).
     */
    public SubstitutionDay assign(LocalDate date, UUID sectionId, int period, UUID teacherId) {
        TenantContext.require();
        support.lock();
        checkDate(date);
        SectionInfo section = support.sections().get(sectionId);
        if (section == null) {
            throw ApiException.badRequest("Pick a section of this school.", "sectionId");
        }
        Day day = new Day(date);
        Slot slot = day.slots.stream()
                .filter(s -> s.getSectionId().equals(sectionId) && s.getPeriodNo() == period)
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(section.label() + " has no class in period " + period
                        + " on " + TimetableService.dayName(day.dayOfWeek) + ".", "period"));
        TeacherAbsence absence = slot.getTeacherId() == null ? null : day.away.stream()
                .filter(a -> a.getTeacherId().equals(slot.getTeacherId())).findFirst().orElse(null);
        Names names = support.names(new HashSet<>(Arrays.asList(slot.getTeacherId(), teacherId)));
        if (absence == null) {
            throw ApiException.badRequest("The teacher of this period is not marked away on " + DATE.format(date)
                    + ".", "period");
        }
        support.checkTeacher(teacherId, "teacherId");
        if (teacherId.equals(absence.getTeacherId())) {
            throw ApiException.badRequest("Pick someone other than the absent teacher.", "teacherId");
        }
        Substitution existing = day.subs.stream()
                .filter(s -> s.getSectionId().equals(sectionId) && s.getPeriodNo() == period)
                .findFirst().orElse(null);
        String busyReason = null;
        if (day.awayIds().contains(teacherId)) {
            busyReason = names.teacher(teacherId) + " is away on " + DATE.format(date) + ".";
        } else {
            Optional<Slot> teaching = day.slots.stream()
                    .filter(s -> teacherId.equals(s.getTeacherId()) && s.getPeriodNo() == period).findFirst();
            Optional<Substitution> covering = day.subs.stream()
                    .filter(s -> s != existing && s.getSubstituteTeacherId().equals(teacherId)
                            && s.getPeriodNo() == period)
                    .findFirst();
            if (teaching.isPresent()) {
                busyReason = names.teacher(teacherId) + " is teaching " + names.section(teaching.get().getSectionId())
                        + " in this period.";
            } else if (covering.isPresent()) {
                busyReason = names.teacher(teacherId) + " is already covering "
                        + names.section(covering.get().getSectionId()) + " in this period.";
            }
        }
        if (busyReason != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Not free", busyReason, Map.of("teacherId", busyReason));
        }
        Substitution saved;
        if (existing == null) {
            saved = substitutions.saveAndFlush(new Substitution(absence.getId(), date, sectionId, period,
                    slot.getSubjectId(), absence.getTeacherId(), teacherId, actor()));
        } else {
            existing.reassign(teacherId, actor());
            saved = substitutions.saveAndFlush(existing);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("date", date.toString());
        details.put("section", section.label());
        details.put("period", period);
        details.put("subject", names.subject(slot.getSubjectId()));
        details.put("absentTeacher", names.teacher(absence.getTeacherId()));
        details.put("substitute", names.teacher(teacherId));
        audit.record("substitution.assigned", "substitution", saved.getId(), details);
        return day(date);
    }

    public SubstitutionDay removeSubstitution(UUID id) {
        TenantContext.require();
        support.lock();
        Substitution sub = substitutions.findById(id).orElseThrow(() -> ApiException.notFound("Substitution"));
        LocalDate date = sub.getSubDate();
        Names names = support.names(List.of(sub.getSubstituteTeacherId(), sub.getAbsentTeacherId()));
        substitutions.delete(sub);
        substitutions.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("date", date.toString());
        details.put("section", names.section(sub.getSectionId()));
        details.put("period", sub.getPeriodNo());
        details.put("absentTeacher", names.teacher(sub.getAbsentTeacherId()));
        details.put("substitute", names.teacher(sub.getSubstituteTeacherId()));
        audit.record("substitution.removed", "substitution", id, details);
        return day(date);
    }

    private void checkDate(LocalDate date) {
        if (date == null) {
            throw ApiException.badRequest("Pick a date.", "date");
        }
        YearInfo year = support.currentYear();
        if (date.isBefore(year.startsOn()) || date.isAfter(year.endsOn())) {
            throw ApiException.badRequest("Pick a date in the current academic year, " + year.name() + ".", "date");
        }
    }

    private static Actor actor() {
        return new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(null));
    }

    // ------------------------------------------------------------------ one day's schedule

    /** A teacher's periods on a date: own classes (covered when away) and substitutions given. */
    @Transactional(readOnly = true)
    public TeacherDay teacherDay(UUID teacherId, LocalDate date) {
        TenantContext.require();
        Map<UUID, String> found = support.userNames(List.of(teacherId));
        if (!found.containsKey(teacherId)) {
            throw ApiException.notFound("Teacher");
        }
        Day day = new Day(date);
        boolean absent = day.awayIds().contains(teacherId);
        Set<UUID> ids = new HashSet<>();
        ids.add(teacherId);
        day.subs.forEach(s -> {
            ids.add(s.getSubstituteTeacherId());
            ids.add(s.getAbsentTeacherId());
        });
        Names names = support.names(ids);
        Map<String, Substitution> subByPeriod = day.subs.stream()
                .collect(Collectors.toMap(s -> s.getSectionId() + "|" + s.getPeriodNo(), Function.identity(),
                        (a, b) -> a));
        List<DayPeriod> periods = new ArrayList<>();
        for (Period p : day.bells.periods(day.dayOfWeek)) {
            List<DayEntry> entries = new ArrayList<>();
            if (!p.isBreak()) {
                int n = p.getNumber();
                for (Slot s : day.slots) {
                    if (s.getPeriodNo() == n && teacherId.equals(s.getTeacherId())) {
                        Substitution sub = subByPeriod.get(s.getSectionId() + "|" + n);
                        entries.add(new DayEntry(absent ? COVERED : CLASS, s.getSectionId(),
                                names.section(s.getSectionId()), s.getSubjectId(), names.subject(s.getSubjectId()),
                                names.teacher(teacherId), s.getRoom(),
                                sub == null ? null : names.teacher(sub.getSubstituteTeacherId()), null));
                    }
                }
                for (Substitution sub : day.subs) {
                    if (sub.getPeriodNo() == n && sub.getSubstituteTeacherId().equals(teacherId)) {
                        String room = day.slots.stream()
                                .filter(s -> s.getSectionId().equals(sub.getSectionId()) && s.getPeriodNo() == n)
                                .map(Slot::getRoom).filter(Objects::nonNull).findFirst().orElse(null);
                        entries.add(new DayEntry(SUBSTITUTION, sub.getSectionId(), names.section(sub.getSectionId()),
                                sub.getSubjectId(), names.subject(sub.getSubjectId()), names.teacher(teacherId), room,
                                null, names.teacher(sub.getAbsentTeacherId())));
                    }
                }
            }
            periods.add(new DayPeriod(p.getNumber(), p.getLabel(), Bells.time(p.getStartsAt()),
                    Bells.time(p.getEndsAt()), p.isBreak(), entries));
        }
        return new TeacherDay(date, day.dayOfWeek, day.bells.isWorking(day.dayOfWeek), teacherId,
                found.get(teacherId), absent, periods);
    }

    /** A section's periods on a date, showing substitute teachers where they have been arranged. */
    @Transactional(readOnly = true)
    public List<DayPeriod> sectionDay(UUID sectionId, LocalDate date) {
        TenantContext.require();
        Day day = new Day(date);
        Set<UUID> ids = new HashSet<>();
        day.slots.stream().filter(s -> s.getSectionId().equals(sectionId)).forEach(s -> ids.add(s.getTeacherId()));
        day.subs.forEach(s -> ids.add(s.getSubstituteTeacherId()));
        Names names = support.names(ids);
        List<DayPeriod> periods = new ArrayList<>();
        for (Period p : day.bells.periods(day.dayOfWeek)) {
            List<DayEntry> entries = new ArrayList<>();
            if (!p.isBreak()) {
                int n = p.getNumber();
                day.slots.stream().filter(s -> s.getSectionId().equals(sectionId) && s.getPeriodNo() == n)
                        .findFirst()
                        .ifPresent(s -> {
                            Substitution sub = day.subs.stream()
                                    .filter(x -> x.getSectionId().equals(sectionId) && x.getPeriodNo() == n)
                                    .findFirst().orElse(null);
                            entries.add(new DayEntry(sub == null ? CLASS : COVERED, sectionId,
                                    names.section(sectionId), s.getSubjectId(), names.subject(s.getSubjectId()),
                                    names.teacher(s.getTeacherId()), s.getRoom(),
                                    sub == null ? null : names.teacher(sub.getSubstituteTeacherId()), null));
                        });
            }
            periods.add(new DayPeriod(p.getNumber(), p.getLabel(), Bells.time(p.getStartsAt()),
                    Bells.time(p.getEndsAt()), p.isBreak(), entries));
        }
        return periods;
    }

    /** Whether the school works on the date's weekday. */
    @Transactional(readOnly = true)
    public boolean isWorkingDay(LocalDate date) {
        return support.bells().isWorking(date.getDayOfWeek());
    }
}
