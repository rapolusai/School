package com.akshara.timetable;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.timetable.ClashDetector.Cell;
import com.akshara.timetable.ClashDetector.Conflict;
import com.akshara.timetable.ClashDetector.Excess;
import com.akshara.timetable.ClashDetector.SectionSubject;
import com.akshara.timetable.TimetableSupport.Names;
import com.akshara.timetable.TimetableViews.AssignmentSaved;
import com.akshara.timetable.TimetableViews.AssignmentView;
import com.akshara.timetable.TimetableViews.Booking;
import com.akshara.timetable.TimetableViews.Clash;
import com.akshara.timetable.TimetableViews.ClashReport;
import com.akshara.timetable.TimetableViews.SectionSummary;
import com.akshara.timetable.TimetableViews.SectionTimetable;
import com.akshara.timetable.TimetableViews.SectionsOverview;
import com.akshara.timetable.TimetableViews.SlotView;
import com.akshara.timetable.TimetableViews.SubjectLoad;
import com.akshara.timetable.TimetableViews.TeacherSlot;
import com.akshara.timetable.TimetableViews.TeacherSummary;
import com.akshara.timetable.TimetableViews.TeacherTimetable;
import com.akshara.timetable.TimetableViews.WeeklyWarning;
import com.akshara.timetable.TimetableViews.YearRef;

/**
 * Teacher assignments and the weekly timetable of each section in the current academic year. Saving a section's
 * timetable is refused when it would put a teacher in two sections at once or give the section two subjects in one
 * period; a subject placed more often than its weekly allowance is only a warning.
 */
@Service
@Transactional
public class TimetableService {

    static final int MAX_CELLS = 7 * BellScheduleService.MAX_PERIODS;

    /** One cell of a section timetable as sent by the editor. A missing teacher means the subject's teacher. */
    public record CellInput(DayOfWeek day, Integer period, UUID subjectId, UUID teacherId, String room) {
    }

    private final TeacherAssignmentRepository assignments;
    private final SlotRepository slots;
    private final TimetableSupport support;
    private final AuditService audit;

    TimetableService(TeacherAssignmentRepository assignments, SlotRepository slots, TimetableSupport support,
            AuditService audit) {
        this.assignments = assignments;
        this.slots = slots;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ teacher assignments

    @Transactional(readOnly = true)
    public List<AssignmentView> assignments(UUID sectionId, UUID teacherId) {
        TenantContext.require();
        Optional<YearInfo> year = support.currentYearIfAny();
        if (year.isEmpty()) {
            return List.of();
        }
        List<TeacherAssignment> list = assignments.findByAcademicYearId(year.get().id()).stream()
                .filter(a -> sectionId == null || a.getSectionId().equals(sectionId))
                .filter(a -> teacherId == null || a.getTeacherId().equals(teacherId))
                .toList();
        Map<SectionSubject, Long> placed = placedCounts(slots.findByAcademicYearId(year.get().id()));
        Names names = support.names(list.stream().map(TeacherAssignment::getTeacherId).toList());
        List<UUID> order = List.copyOf(names.sections().keySet());
        return list.stream()
                .map(a -> view(a, names, placed))
                .sorted(Comparator.comparingInt((AssignmentView a) -> order.indexOf(a.sectionId()))
                        .thenComparing(a -> a.subjectName() == null ? "" : a.subjectName().toLowerCase(Locale.ROOT)))
                .toList();
    }

    public AssignmentView createAssignment(UUID sectionId, UUID subjectId, UUID teacherId, int periodsPerWeek) {
        TenantContext.require();
        support.lock();
        YearInfo year = support.currentYear();
        SectionInfo section = support.sections().get(sectionId);
        if (section == null) {
            throw ApiException.badRequest("Pick a section of this school.", "sectionId");
        }
        if (subjectId == null || !support.classSubjects(section.classId()).contains(subjectId)) {
            throw ApiException.badRequest("Pick a subject that " + section.className() + " studies (see School "
                    + "setup).", "subjectId");
        }
        support.checkTeacher(teacherId, "teacherId");
        checkPeriods(periodsPerWeek);
        Names names = support.names(List.of(teacherId));
        if (assignments.findByAcademicYearIdAndSectionIdAndSubjectId(year.id(), sectionId, subjectId).isPresent()) {
            throw ApiException.conflict(names.subject(subjectId) + " in " + section.label()
                    + " already has a teacher. Change that assignment instead.", "subjectId");
        }
        TeacherAssignment saved = assignments.saveAndFlush(new TeacherAssignment(year.id(), sectionId, subjectId,
                teacherId, periodsPerWeek));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("section", section.label());
        details.put("subject", names.subject(subjectId));
        details.put("teacher", names.teacher(teacherId));
        details.put("periodsPerWeek", periodsPerWeek);
        audit.record("teacher_assignment.created", "teacher_assignment", saved.getId(), details);
        return view(saved, names, placedCounts(
                slots.findByAcademicYearIdAndSectionIdAndSubjectId(year.id(), sectionId, subjectId)));
    }

    /**
     * Changes who teaches a subject in a section, or its weekly periods. The section's timetable periods of that
     * subject move to the new teacher; any clash this causes is returned (and shown in the clash report).
     */
    public AssignmentSaved updateAssignment(UUID id, UUID teacherId, int periodsPerWeek) {
        TenantContext.require();
        support.lock();
        TeacherAssignment assignment = assignments.findById(id)
                .orElseThrow(() -> ApiException.notFound("Teacher assignment"));
        support.checkTeacher(teacherId, "teacherId");
        checkPeriods(periodsPerWeek);
        UUID previous = assignment.getTeacherId();
        int moved = 0;
        if (!previous.equals(teacherId)) {
            for (Slot s : slots.findByAcademicYearIdAndSectionIdAndSubjectId(assignment.getAcademicYearId(),
                    assignment.getSectionId(), assignment.getSubjectId())) {
                if (s.getTeacherId() == null || s.getTeacherId().equals(previous)) {
                    s.reassign(teacherId);
                    moved++;
                }
            }
        }
        assignment.update(teacherId, periodsPerWeek);
        assignments.flush();
        slots.flush();
        Names names = support.names(List.of(teacherId, previous));
        List<Slot> teacherSlots = slots.findByAcademicYearIdAndTeacherId(assignment.getAcademicYearId(), teacherId);
        List<Clash> clashes = ClashDetector.clashes(teacherSlots.stream().map(TimetableSupport::cell).toList())
                .stream().map(names::clash).toList();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("section", names.section(assignment.getSectionId()));
        details.put("subject", names.subject(assignment.getSubjectId()));
        details.put("teacher", names.teacher(teacherId));
        if (!previous.equals(teacherId)) {
            details.put("previousTeacher", names.teacher(previous));
            details.put("periodsMoved", moved);
        }
        details.put("periodsPerWeek", periodsPerWeek);
        audit.record("teacher_assignment.updated", "teacher_assignment", id, details);
        AssignmentView view = view(assignment, names, placedCounts(slots.findByAcademicYearIdAndSectionIdAndSubjectId(
                assignment.getAcademicYearId(), assignment.getSectionId(), assignment.getSubjectId())));
        return new AssignmentSaved(view, moved, clashes);
    }

    public void deleteAssignment(UUID id) {
        TenantContext.require();
        support.lock();
        TeacherAssignment assignment = assignments.findById(id)
                .orElseThrow(() -> ApiException.notFound("Teacher assignment"));
        Names names = support.names(List.of(assignment.getTeacherId()));
        int placed = slots.findByAcademicYearIdAndSectionIdAndSubjectId(assignment.getAcademicYearId(),
                assignment.getSectionId(), assignment.getSubjectId()).size();
        if (placed > 0) {
            String detail = names.section(assignment.getSectionId()) + " has " + placed + " "
                    + names.subject(assignment.getSubjectId()) + (placed == 1 ? " period" : " periods")
                    + " in its timetable. Remove them from the timetable first.";
            throw new ApiException(HttpStatus.CONFLICT, "In use", detail, Map.of("assignment", detail));
        }
        assignments.delete(assignment);
        assignments.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("section", names.section(assignment.getSectionId()));
        details.put("subject", names.subject(assignment.getSubjectId()));
        details.put("teacher", names.teacher(assignment.getTeacherId()));
        audit.record("teacher_assignment.deleted", "teacher_assignment", id, details);
    }

    private static void checkPeriods(int periodsPerWeek) {
        if (periodsPerWeek < 0 || periodsPerWeek > 60) {
            throw ApiException.badRequest("Give between 0 and 60 periods a week.", "periodsPerWeek");
        }
    }

    private static AssignmentView view(TeacherAssignment a, Names names, Map<SectionSubject, Long> placed) {
        SectionInfo section = names.sections().get(a.getSectionId());
        return new AssignmentView(a.getId(), a.getSectionId(), section == null ? null : section.label(),
                section == null ? null : section.classId(), a.getSubjectId(), names.subject(a.getSubjectId()),
                a.getTeacherId(), names.teacher(a.getTeacherId()), a.getPeriodsPerWeek(),
                placed.getOrDefault(new SectionSubject(a.getSectionId(), a.getSubjectId()), 0L).intValue());
    }

    private static Map<SectionSubject, Long> placedCounts(Collection<Slot> list) {
        return list.stream().collect(Collectors.groupingBy(s -> new SectionSubject(s.getSectionId(),
                s.getSubjectId()), Collectors.counting()));
    }

    // ------------------------------------------------------------------ section timetables

    /** Every section with how full its timetable is and how many clashes it has. */
    @Transactional(readOnly = true)
    public SectionsOverview overview(boolean canEdit) {
        TenantContext.require();
        Bells bells = support.bells();
        Optional<YearInfo> year = support.currentYearIfAny();
        List<Slot> all = year.map(y -> slots.findByAcademicYearId(y.id())).orElse(List.of());
        Map<UUID, Long> filled = all.stream().collect(Collectors.groupingBy(Slot::getSectionId, Collectors.counting()));
        List<Conflict> conflicts = ClashDetector.clashes(all.stream().map(TimetableSupport::cell).toList());
        Map<UUID, Integer> clashCount = new HashMap<>();
        for (Conflict c : conflicts) {
            c.cells().stream().map(Cell::sectionId).distinct().forEach(id -> clashCount.merge(id, 1, Integer::sum));
        }
        int cells = bells.weeklyCells();
        List<SectionSummary> sections = support.sections().values().stream()
                .map(s -> new SectionSummary(s.id(), s.label(), s.classId(), s.className(), s.name(),
                        s.classTeacherName(), filled.getOrDefault(s.id(), 0L).intValue(), cells,
                        clashCount.getOrDefault(s.id(), 0)))
                .toList();
        return new SectionsOverview(year.map(YearRef::of).orElse(null), bells.view(), sections, conflicts.size(),
                canEdit && year.isPresent());
    }

    /**
     * A section's weekly timetable with its subjects, clashes and warnings. For editors it also lists when the
     * section's teachers are busy elsewhere, so the editor can warn before saving.
     */
    @Transactional(readOnly = true)
    public SectionTimetable section(UUID sectionId, boolean canEdit) {
        TenantContext.require();
        SectionInfo section = support.section(sectionId);
        Bells bells = support.bells();
        Optional<YearInfo> year = support.currentYearIfAny();
        List<Slot> all = year.map(y -> slots.findByAcademicYearId(y.id())).orElse(List.of());
        List<Slot> mine = all.stream().filter(s -> s.getSectionId().equals(sectionId))
                .sorted(Comparator.comparing(Slot::getDay).thenComparingInt(Slot::getPeriodNo)).toList();
        List<TeacherAssignment> assigned = year.map(y -> assignments.findByAcademicYearIdAndSectionId(y.id(),
                sectionId)).orElse(List.of());
        Set<UUID> teacherIds = new LinkedHashSet<>();
        assigned.forEach(a -> teacherIds.add(a.getTeacherId()));
        mine.forEach(s -> {
            if (s.getTeacherId() != null) {
                teacherIds.add(s.getTeacherId());
            }
        });
        List<Slot> elsewhere = all.stream()
                .filter(s -> !s.getSectionId().equals(sectionId) && s.getTeacherId() != null
                        && teacherIds.contains(s.getTeacherId()))
                .toList();
        Set<UUID> nameIds = new HashSet<>(teacherIds);
        List<Conflict> conflicts = ClashDetector.clashes(all.stream().map(TimetableSupport::cell).toList()).stream()
                .filter(c -> c.cells().stream().anyMatch(cell -> cell.sectionId().equals(sectionId)))
                .toList();
        conflicts.forEach(c -> c.cells().forEach(cell -> nameIds.add(cell.teacherId())));
        Names names = support.names(nameIds);

        Map<UUID, TeacherAssignment> bySubject = assigned.stream()
                .collect(Collectors.toMap(TeacherAssignment::getSubjectId, Function.identity()));
        Map<UUID, Long> placed = mine.stream()
                .collect(Collectors.groupingBy(Slot::getSubjectId, Collectors.counting()));
        Set<UUID> subjectIds = new LinkedHashSet<>(support.classSubjects(section.classId()));
        subjectIds.addAll(bySubject.keySet());
        subjectIds.addAll(placed.keySet());
        List<SubjectLoad> subjects = subjectIds.stream()
                .map(id -> {
                    TeacherAssignment a = bySubject.get(id);
                    return new SubjectLoad(id, names.subject(id), a == null ? null : a.getId(),
                            a == null ? null : a.getTeacherId(), a == null ? null : names.teacher(a.getTeacherId()),
                            a == null ? null : a.getPeriodsPerWeek(), placed.getOrDefault(id, 0L).intValue());
                })
                .sorted(Comparator.comparing(s -> s.subjectName() == null ? "" : s.subjectName().toLowerCase()))
                .toList();
        List<Booking> busy = !canEdit ? List.of() : elsewhere.stream()
                .map(s -> new Booking(s.getTeacherId(), s.getDay(), s.getPeriodNo(), s.getSectionId(),
                        names.section(s.getSectionId()), names.subject(s.getSubjectId())))
                .sorted(Comparator.comparing(Booking::day).thenComparingInt(Booking::period))
                .toList();
        List<WeeklyWarning> warnings = warnings(mine, assigned, names);
        return new SectionTimetable(section.id(), section.label(), section.classId(), section.className(),
                section.name(), section.classTeacherName(), year.map(YearRef::of).orElse(null), bells.view(),
                mine.stream().map(names::slot).toList(), subjects, busy,
                conflicts.stream().map(names::clash).toList(), warnings, canEdit && year.isPresent());
    }

    /** The slots of a section this year, for parents and students. */
    @Transactional(readOnly = true)
    public List<SlotView> sectionSlots(UUID yearId, UUID sectionId) {
        List<Slot> mine = slots.findByAcademicYearIdAndSectionId(yearId, sectionId).stream()
                .sorted(Comparator.comparing(Slot::getDay).thenComparingInt(Slot::getPeriodNo)).toList();
        Names names = support.names(mine.stream().map(Slot::getTeacherId).toList());
        return mine.stream().map(names::slot).toList();
    }

    /**
     * Replaces a section's timetable for the current year with exactly the given cells. Refused (409) when a teacher
     * would be in two sections at once; cells that do not fit the bell schedule or repeat a period are 400.
     */
    public SectionTimetable saveSection(UUID sectionId, List<CellInput> input) {
        TenantContext.require();
        support.lock();
        SectionInfo section = support.section(sectionId);
        YearInfo year = support.currentYear();
        Bells bells = support.bells();
        if (bells.isEmpty()) {
            throw ApiException.badRequest("Set up the bell schedule first.", "slots");
        }
        List<CellInput> cells = input == null ? List.of() : input;
        if (cells.size() > MAX_CELLS) {
            throw ApiException.badRequest("A week can have at most " + MAX_CELLS + " periods.", "slots");
        }
        Set<UUID> classSubjects = support.classSubjects(section.classId());
        Map<UUID, TeacherAssignment> bySubject = assignments.findByAcademicYearIdAndSectionId(year.id(), sectionId)
                .stream().collect(Collectors.toMap(TeacherAssignment::getSubjectId, Function.identity()));
        Set<UUID> teachers = support.activeTeachers().stream().map(TeacherRef::id).collect(Collectors.toSet());
        List<Slot> existing = slots.findByAcademicYearIdAndSectionId(year.id(), sectionId);
        Set<UUID> keptTeachers = existing.stream().map(Slot::getTeacherId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Cell> proposed = new LinkedHashMap<>();
        Map<String, String> rooms = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            CellInput c = cells.get(i);
            if (c == null || c.day() == null || c.period() == null || c.subjectId() == null) {
                errors.putIfAbsent("slots[" + i + "]", "Pick a day, period and subject.");
                continue;
            }
            String key = key(c.day(), c.period());
            if (!bells.isWorking(c.day())) {
                errors.putIfAbsent(key, "The school is closed on " + dayName(c.day()) + ".");
                continue;
            }
            if (c.period() < 1 || c.period() > bells.teachingPeriods(c.day())) {
                errors.putIfAbsent(key, dayName(c.day()) + " has no period " + c.period() + ".");
                continue;
            }
            if (proposed.containsKey(key)) {
                errors.putIfAbsent(key, section.label() + " already has a subject in this period.");
                continue;
            }
            TeacherAssignment assignment = bySubject.get(c.subjectId());
            if (assignment == null && !classSubjects.contains(c.subjectId())) {
                errors.putIfAbsent(key, "Pick a subject that " + section.className() + " studies.");
                continue;
            }
            UUID teacherId = c.teacherId() != null ? c.teacherId()
                    : assignment == null ? null : assignment.getTeacherId();
            // An inactive teacher may stay where they already are, but cannot be newly placed.
            if (teacherId != null && !teachers.contains(teacherId)
                    && !(c.teacherId() == null || keptTeachers.contains(teacherId))) {
                errors.putIfAbsent(key, "Pick a teacher from this school.");
                continue;
            }
            String room = c.room() == null || c.room().isBlank() ? null : c.room().strip().replaceAll("\\s+", " ");
            if (room != null && room.length() > 40) {
                errors.putIfAbsent(key, "Keep the room to 40 characters.");
                continue;
            }
            proposed.put(key, new Cell(sectionId, c.day(), c.period(), c.subjectId(), teacherId));
            rooms.put(key, room);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Check the timetable", errors.values().iterator().next(),
                    errors);
        }

        List<Cell> others = slots.findByAcademicYearId(year.id()).stream()
                .filter(s -> !s.getSectionId().equals(sectionId))
                .map(TimetableSupport::cell)
                .toList();
        List<Conflict> clashes = ClashDetector.clashesWith(proposed.values(), others);
        if (!clashes.isEmpty()) {
            Set<UUID> ids = new HashSet<>();
            clashes.forEach(c -> c.cells().forEach(cell -> ids.add(cell.teacherId())));
            Names names = support.names(ids);
            Map<String, String> clashErrors = new LinkedHashMap<>();
            for (Conflict c : clashes) {
                Cell other = c.cells().get(1);
                clashErrors.putIfAbsent(key(c.day(), c.period()), names.teacher(c.teacherId()) + " is teaching "
                        + names.section(other.sectionId()) + " (" + names.subject(other.subjectId())
                        + ") in this period.");
            }
            throw new ApiException(HttpStatus.CONFLICT, "Teacher clash", clashErrors.size() == 1
                    ? clashErrors.values().iterator().next()
                    : clashErrors.size() + " periods clash with other sections' timetables.", clashErrors);
        }

        Map<String, Slot> current = existing.stream()
                .collect(Collectors.toMap(s -> key(s.getDay(), s.getPeriodNo()), Function.identity()));
        int added = 0;
        int changed = 0;
        int removed = 0;
        List<Slot> toDelete = new ArrayList<>();
        for (Map.Entry<String, Slot> e : current.entrySet()) {
            if (!proposed.containsKey(e.getKey())) {
                toDelete.add(e.getValue());
            }
        }
        slots.deleteAll(toDelete);
        removed = toDelete.size();
        slots.flush();
        for (Map.Entry<String, Cell> e : proposed.entrySet()) {
            Cell cell = e.getValue();
            Slot slot = current.get(e.getKey());
            if (slot == null) {
                slots.save(new Slot(year.id(), sectionId, cell.day(), cell.period(), cell.subjectId(),
                        cell.teacherId(), rooms.get(e.getKey())));
                added++;
            } else if (slot.change(cell.subjectId(), cell.teacherId(), rooms.get(e.getKey()))) {
                changed++;
            }
        }
        slots.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("section", section.label());
        details.put("periods", proposed.size());
        details.put("added", added);
        details.put("changed", changed);
        details.put("removed", removed);
        audit.record("timetable.section_saved", "section", sectionId, details);
        return section(sectionId, true);
    }

    static String key(DayOfWeek day, int period) {
        return day.name() + "-" + period;
    }

    static String dayName(DayOfWeek day) {
        return day.name().charAt(0) + day.name().substring(1).toLowerCase(Locale.ROOT);
    }

    private static List<WeeklyWarning> warnings(List<Slot> list, List<TeacherAssignment> assigned, Names names) {
        Map<SectionSubject, Integer> allowed = assigned.stream()
                .collect(Collectors.toMap(a -> new SectionSubject(a.getSectionId(), a.getSubjectId()),
                        TeacherAssignment::getPeriodsPerWeek));
        List<Excess> excess = ClashDetector.overWeeklyLimit(list.stream().map(TimetableSupport::cell).toList(),
                allowed);
        return excess.stream()
                .map(e -> new WeeklyWarning(e.sectionId(), names.section(e.sectionId()), e.subjectId(),
                        names.subject(e.subjectId()), e.scheduled(), e.allowed()))
                .toList();
    }

    // ------------------------------------------------------------------ the whole school

    /** Checks every section's timetable this year: clashes, and subjects over their weekly allowance. */
    @Transactional(readOnly = true)
    public ClashReport clashReport() {
        TenantContext.require();
        Optional<YearInfo> year = support.currentYearIfAny();
        if (year.isEmpty()) {
            return new ClashReport(null, 0, 0, List.of(), List.of());
        }
        List<Slot> all = slots.findByAcademicYearId(year.get().id());
        List<TeacherAssignment> assigned = assignments.findByAcademicYearId(year.get().id());
        Names names = support.names(all.stream().map(Slot::getTeacherId).toList());
        List<Clash> clashes = ClashDetector.clashes(all.stream().map(TimetableSupport::cell).toList()).stream()
                .map(names::clash).toList();
        List<UUID> order = List.copyOf(names.sections().keySet());
        List<WeeklyWarning> warnings = warnings(all, assigned, names).stream()
                .sorted(Comparator.comparingInt((WeeklyWarning w) -> order.indexOf(w.sectionId()))
                        .thenComparing(w -> w.subjectName() == null ? "" : w.subjectName()))
                .toList();
        int sections = (int) all.stream().map(Slot::getSectionId).distinct().count();
        return new ClashReport(YearRef.of(year.get()), sections, all.size(), clashes, warnings);
    }

    // ------------------------------------------------------------------ teachers

    /** Active teachers with their weekly load: periods assigned and periods placed in timetables. */
    @Transactional(readOnly = true)
    public List<TeacherSummary> teachers() {
        TenantContext.require();
        Optional<YearInfo> year = support.currentYearIfAny();
        List<TeacherAssignment> assigned = year.map(y -> assignments.findByAcademicYearId(y.id())).orElse(List.of());
        Map<UUID, Long> placed = year.map(y -> slots.findByAcademicYearId(y.id())).orElse(List.of()).stream()
                .filter(s -> s.getTeacherId() != null)
                .collect(Collectors.groupingBy(Slot::getTeacherId, Collectors.counting()));
        Map<UUID, String> subjects = support.subjectNames();
        Map<UUID, List<TeacherAssignment>> byTeacher = assigned.stream()
                .collect(Collectors.groupingBy(TeacherAssignment::getTeacherId));
        return support.activeTeachers().stream()
                .map(t -> {
                    List<TeacherAssignment> own = byTeacher.getOrDefault(t.id(), List.of());
                    return new TeacherSummary(t.id(), t.name(), own.stream()
                            .mapToInt(TeacherAssignment::getPeriodsPerWeek).sum(),
                            placed.getOrDefault(t.id(), 0L).intValue(),
                            own.stream().map(a -> subjects.get(a.getSubjectId())).filter(Objects::nonNull)
                                    .distinct().sorted().toList());
                })
                .toList();
    }

    /** A teacher's weekly timetable. Another school's (or an unknown) person is 404. */
    @Transactional(readOnly = true)
    public TeacherTimetable teacher(UUID teacherId) {
        TenantContext.require();
        Map<UUID, String> found = support.userNames(List.of(teacherId));
        if (!found.containsKey(teacherId)) {
            throw ApiException.notFound("Teacher");
        }
        Bells bells = support.bells();
        Optional<YearInfo> year = support.currentYearIfAny();
        List<Slot> own = year.map(y -> slots.findByAcademicYearIdAndTeacherId(y.id(), teacherId)).orElse(List.of())
                .stream().sorted(Comparator.comparing(Slot::getDay).thenComparingInt(Slot::getPeriodNo)).toList();
        int periodsPerWeek = year.map(y -> assignments.findByAcademicYearIdAndTeacherId(y.id(), teacherId))
                .orElse(List.of()).stream().mapToInt(TeacherAssignment::getPeriodsPerWeek).sum();
        Names names = support.names(List.of(teacherId));
        List<TeacherSlot> list = own.stream()
                .map(s -> new TeacherSlot(s.getDay(), s.getPeriodNo(), s.getSectionId(),
                        names.section(s.getSectionId()), s.getSubjectId(), names.subject(s.getSubjectId()),
                        s.getRoom()))
                .toList();
        List<Clash> clashes = ClashDetector.clashes(own.stream().map(TimetableSupport::cell).toList()).stream()
                .map(names::clash).toList();
        return new TeacherTimetable(teacherId, found.get(teacherId), year.map(YearRef::of).orElse(null),
                bells.view(), list, periodsPerWeek, clashes);
    }
}
