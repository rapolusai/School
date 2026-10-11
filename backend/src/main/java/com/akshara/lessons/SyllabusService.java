package com.akshara.lessons;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SectionView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.lessons.LessonsForms.CopyForm;
import com.akshara.lessons.LessonsForms.SyllabusForm;
import com.akshara.lessons.LessonsForms.TopicForm;
import com.akshara.lessons.LessonsForms.UnitForm;
import com.akshara.lessons.LessonsViews.CopyResult;
import com.akshara.lessons.LessonsViews.SyllabusDetail;
import com.akshara.lessons.LessonsViews.SyllabusRow;
import com.akshara.lessons.LessonsViews.TopicOption;
import com.akshara.lessons.LessonsViews.TopicView;
import com.akshara.lessons.LessonsViews.UnitView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The syllabus of each class and subject per academic year: units and topics in order, with the periods planned for
 * each topic and the month it should be taught by. Approvers change any syllabus; teachers (lessons.manage) the
 * subjects they teach in the class. A topic that lesson plans use cannot be removed, and a syllabus with plans cannot
 * be deleted. A year's syllabi can be copied to another year, with the target months moved along.
 */
@Service
@Transactional
public class SyllabusService {

    static final int MAX_TOPICS = 600;

    private final SyllabusRepository syllabi;
    private final SyllabusUnitRepository units;
    private final SyllabusTopicRepository topics;
    private final LessonPlanRepository plans;
    private final LessonsSupport support;
    private final LessonsAccess access;
    private final AuditService audit;

    SyllabusService(SyllabusRepository syllabi, SyllabusUnitRepository units, SyllabusTopicRepository topics,
            LessonPlanRepository plans, LessonsSupport support, LessonsAccess access, AuditService audit) {
        this.syllabi = syllabi;
        this.units = units;
        this.topics = topics;
        this.plans = plans;
        this.support = support;
        this.access = access;
        this.audit = audit;
    }

    /** A unit or topic as written in a form or a file, before it is saved. */
    record UnitDraft(UUID id, String title, List<TopicDraft> topics) {
    }

    record TopicDraft(UUID id, String title, int plannedPeriods, YearMonth targetMonth) {
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<SyllabusRow> list(UUID yearId) {
        TenantContext.require();
        Optional<YearInfo> year = support.yearOrCurrent(yearId);
        if (year.isEmpty()) {
            return List.of();
        }
        LessonsAccess.Scope scope = access.scope(year.get().id());
        List<Syllabus> all = syllabi.findByAcademicYearId(year.get().id());
        Set<UUID> ids = all.stream().map(Syllabus::getId).collect(Collectors.toSet());
        Map<UUID, Long> unitCount = ids.isEmpty() ? Map.of() : units.findBySyllabusIdIn(ids).stream()
                .collect(Collectors.groupingBy(SyllabusUnit::getSyllabusId, Collectors.counting()));
        Map<UUID, List<SyllabusTopic>> topicsOf = ids.isEmpty() ? Map.of() : topics.findBySyllabusIdIn(ids).stream()
                .collect(Collectors.groupingBy(SyllabusTopic::getSyllabusId));
        Map<UUID, ClassView> classes = support.classes();
        Map<UUID, String> subjects = support.subjectNames();
        List<UUID> classOrder = new ArrayList<>(classes.keySet());
        return all.stream()
                .sorted(Comparator.comparingInt((Syllabus s) -> classOrder.indexOf(s.getClassId()))
                        .thenComparing(s -> subjects.getOrDefault(s.getSubjectId(), "").toLowerCase()))
                .map(s -> {
                    List<SyllabusTopic> list = topicsOf.getOrDefault(s.getId(), List.of());
                    ClassView c = classes.get(s.getClassId());
                    return new SyllabusRow(s.getId(), s.getAcademicYearId(), s.getClassId(),
                            c == null ? "" : c.name(), s.getSubjectId(), subjects.getOrDefault(s.getSubjectId(), ""),
                            unitCount.getOrDefault(s.getId(), 0L).intValue(), list.size(),
                            list.stream().mapToInt(SyllabusTopic::getPlannedPeriods).sum(), s.getUpdatedAt(),
                            s.getUpdatedByName(), c != null && canEdit(scope, c, s.getSubjectId()));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public SyllabusDetail detail(UUID id) {
        TenantContext.require();
        return view(syllabus(id));
    }

    /** The topics of a class and subject's syllabus in a year, in order, for pickers (empty without a syllabus). */
    @Transactional(readOnly = true)
    public List<TopicOption> topicOptions(UUID yearId, UUID classId, UUID subjectId) {
        TenantContext.require();
        Optional<YearInfo> year = support.yearOrCurrent(yearId);
        if (year.isEmpty() || classId == null || subjectId == null) {
            return List.of();
        }
        return syllabi.findByAcademicYearIdAndClassIdAndSubjectId(year.get().id(), classId, subjectId)
                .map(s -> {
                    List<SyllabusUnit> unitList = units.findBySyllabusIdOrderByPosition(s.getId());
                    Map<UUID, List<SyllabusTopic>> byUnit = topics.findBySyllabusIdOrderByPosition(s.getId())
                            .stream().collect(Collectors.groupingBy(SyllabusTopic::getUnitId));
                    List<TopicOption> result = new ArrayList<>();
                    for (SyllabusUnit u : unitList) {
                        for (SyllabusTopic t : byUnit.getOrDefault(u.getId(), List.of())) {
                            result.add(new TopicOption(t.getId(), t.getTitle(), u.getTitle(), month(t)));
                        }
                    }
                    return result;
                })
                .orElse(List.of());
    }

    Syllabus syllabus(UUID id) {
        return syllabi.findById(id).orElseThrow(() -> ApiException.notFound("Syllabus"));
    }

    SyllabusDetail view(Syllabus s) {
        LessonsAccess.Scope scope = access.scope(s.getAcademicYearId());
        List<SyllabusUnit> unitList = units.findBySyllabusIdOrderByPosition(s.getId());
        List<SyllabusTopic> topicList = topics.findBySyllabusIdOrderByPosition(s.getId());
        Map<UUID, Long> planCounts = planCounts(topicList.stream().map(SyllabusTopic::getId).toList());
        Map<UUID, List<SyllabusTopic>> byUnit = topicList.stream()
                .collect(Collectors.groupingBy(SyllabusTopic::getUnitId));
        List<UnitView> unitViews = unitList.stream()
                .map(u -> new UnitView(u.getId(), u.getPosition(), u.getTitle(),
                        byUnit.getOrDefault(u.getId(), List.of()).stream()
                                .map(t -> new TopicView(t.getId(), t.getPosition(), t.getTitle(),
                                        t.getPlannedPeriods(), month(t), planCounts.getOrDefault(t.getId(), 0L) > 0))
                                .toList()))
                .toList();
        ClassView c = support.classes().get(s.getClassId());
        String yearName = support.yearOrCurrent(s.getAcademicYearId()).map(YearInfo::name).orElse("");
        return new SyllabusDetail(s.getId(), s.getAcademicYearId(), yearName, s.getClassId(),
                c == null ? "" : c.name(), s.getSubjectId(), support.subjectNames().getOrDefault(s.getSubjectId(), ""),
                unitViews, topicList.size(), topicList.stream().mapToInt(SyllabusTopic::getPlannedPeriods).sum(),
                s.getUpdatedAt(), s.getUpdatedByName(), s.getVersion(), c != null && canEdit(scope, c,
                        s.getSubjectId()));
    }

    Map<UUID, Long> planCounts(Collection<UUID> topicIds) {
        if (topicIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> result = new HashMap<>();
        for (Object[] row : plans.countPerTopic(topicIds)) {
            result.put((UUID) row[0], (Long) row[1]);
        }
        return result;
    }

    static YearMonth month(SyllabusTopic t) {
        return t.getTargetMonth() == null ? null : YearMonth.from(t.getTargetMonth());
    }

    static boolean canEdit(LessonsAccess.Scope scope, ClassView c, UUID subjectId) {
        return scope.wholeSchool()
                || (scope.canManage() && scope.teachesAny(c.sections().stream().map(SectionView::id).toList(),
                        subjectId));
    }

    // ------------------------------------------------------------------ changing

    public SyllabusDetail create(SyllabusForm form, Actor actor) {
        TenantContext.require();
        YearInfo year = support.yearInBody(form.academicYearId(), "academicYearId");
        ClassView c = support.classInBody(form.classId(), "classId");
        LessonsSupport.subjectOfClass(c, form.subjectId(), "subjectId");
        mustEdit(year, c, form.subjectId());
        if (syllabi.findByAcademicYearIdAndClassIdAndSubjectId(year.id(), c.id(), form.subjectId()).isPresent()) {
            throw ApiException.conflict("This class already has a syllabus for the subject in " + year.name() + ".",
                    "subjectId");
        }
        List<UnitDraft> drafts = drafts(form.units(), year);
        Syllabus s = save(year, c.id(), form.subjectId(), drafts, actor);
        audit.record(actor, "syllabus.created", "syllabus", s.getId(), Map.of("year", year.name(),
                "class", c.name(), "subjectId", form.subjectId(), "units", drafts.size(),
                "topics", drafts.stream().mapToInt(d -> d.topics().size()).sum()));
        return view(s);
    }

    /** Saves a new syllabus with its units and topics (no checks: the callers have made them). */
    Syllabus save(YearInfo year, UUID classId, UUID subjectId, List<UnitDraft> drafts, Actor actor) {
        Syllabus s = syllabi.saveAndFlush(new Syllabus(year.id(), classId, subjectId, actor.name()));
        int unitPosition = 0;
        for (UnitDraft d : drafts) {
            SyllabusUnit u = units.save(new SyllabusUnit(s.getId(), ++unitPosition, d.title()));
            int topicPosition = 0;
            for (TopicDraft t : d.topics()) {
                topics.save(new SyllabusTopic(s.getId(), u.getId(), ++topicPosition, t.title(), t.plannedPeriods(),
                        t.targetMonth() == null ? null : t.targetMonth().atDay(1)));
            }
        }
        topics.flush();
        return s;
    }

    public SyllabusDetail update(UUID id, SyllabusForm form, Actor actor) {
        TenantContext.require();
        Syllabus s = syllabus(id);
        if (!s.getClassId().equals(form.classId()) || !s.getSubjectId().equals(form.subjectId())) {
            throw ApiException.badRequest("The class and subject of a syllabus cannot change.", "subjectId");
        }
        YearInfo year = support.year(s.getAcademicYearId());
        ClassView c = support.classInBody(s.getClassId(), "classId");
        mustEdit(year, c, s.getSubjectId());
        if (form.version() != null && form.version() != s.getVersion()) {
            throw ApiException.conflict("Someone else changed this syllabus. Reload it and try again.", "version");
        }
        List<UnitDraft> drafts = drafts(form.units(), year);
        Map<UUID, SyllabusUnit> oldUnits = units.findBySyllabusIdOrderByPosition(s.getId()).stream()
                .collect(Collectors.toMap(SyllabusUnit::getId, u -> u, (a, b) -> a, LinkedHashMap::new));
        Map<UUID, SyllabusTopic> oldTopics = topics.findBySyllabusIdOrderByPosition(s.getId()).stream()
                .collect(Collectors.toMap(SyllabusTopic::getId, t -> t, (a, b) -> a, LinkedHashMap::new));
        Set<UUID> keptUnits = new HashSet<>();
        Set<UUID> keptTopics = new HashSet<>();
        for (UnitDraft d : drafts) {
            if (d.id() != null && !oldUnits.containsKey(d.id())) {
                throw ApiException.badRequest("A unit is not part of this syllabus. Reload it and try again.",
                        "units");
            }
            for (TopicDraft t : d.topics()) {
                if (t.id() != null && !oldTopics.containsKey(t.id())) {
                    throw ApiException.badRequest("A topic is not part of this syllabus. Reload it and try again.",
                            "units");
                }
                if (t.id() != null && !keptTopics.add(t.id())) {
                    throw ApiException.badRequest("A topic appears twice.", "units");
                }
            }
            if (d.id() != null && !keptUnits.add(d.id())) {
                throw ApiException.badRequest("A unit appears twice.", "units");
            }
        }
        List<SyllabusTopic> removed = oldTopics.values().stream().filter(t -> !keptTopics.contains(t.getId()))
                .toList();
        if (!removed.isEmpty()) {
            Map<UUID, Long> used = planCounts(removed.stream().map(SyllabusTopic::getId).toList());
            Optional<SyllabusTopic> inUse = removed.stream().filter(t -> used.getOrDefault(t.getId(), 0L) > 0)
                    .findFirst();
            if (inUse.isPresent()) {
                throw ApiException.conflict("Lesson plans use the topic \"" + inUse.get().getTitle()
                        + "\", so it cannot be removed.", "units");
            }
        }
        int unitPosition = 0;
        for (UnitDraft d : drafts) {
            SyllabusUnit u = d.id() == null ? new SyllabusUnit(s.getId(), unitPosition + 1, d.title())
                    : oldUnits.get(d.id());
            u.update(++unitPosition, d.title());
            units.save(u);
            int topicPosition = 0;
            for (TopicDraft t : d.topics()) {
                LocalDate target = t.targetMonth() == null ? null : t.targetMonth().atDay(1);
                if (t.id() == null) {
                    topics.save(new SyllabusTopic(s.getId(), u.getId(), ++topicPosition, t.title(),
                            t.plannedPeriods(), target));
                } else {
                    oldTopics.get(t.id()).update(u.getId(), ++topicPosition, t.title(), t.plannedPeriods(), target);
                }
            }
        }
        units.flush();
        topics.flush();
        topics.deleteAll(removed);
        topics.flush();
        units.deleteAll(oldUnits.values().stream().filter(u -> !keptUnits.contains(u.getId())).toList());
        units.flush();
        s.changed(actor.name());
        syllabi.saveAndFlush(s);
        audit.record(actor, "syllabus.updated", "syllabus", s.getId(), Map.of("year", year.name(), "class", c.name(),
                "subjectId", s.getSubjectId(), "units", drafts.size(),
                "topics", drafts.stream().mapToInt(d -> d.topics().size()).sum(), "topicsRemoved", removed.size()));
        return view(s);
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        Syllabus s = syllabus(id);
        YearInfo year = support.year(s.getAcademicYearId());
        ClassView c = support.classInBody(s.getClassId(), "classId");
        mustEdit(year, c, s.getSubjectId());
        List<UUID> topicIds = topics.findBySyllabusIdOrderByPosition(s.getId()).stream().map(SyllabusTopic::getId)
                .toList();
        long used = topicIds.isEmpty() ? 0 : plans.countByTopicIdIn(topicIds);
        if (used > 0) {
            String detail = used + (used == 1 ? " lesson plan uses" : " lesson plans use")
                    + " this syllabus, so it cannot be deleted.";
            throw ApiException.conflict(detail, "syllabus");
        }
        syllabi.delete(s);
        syllabi.flush();
        audit.record(actor, "syllabus.deleted", "syllabus", s.getId(), Map.of("year", year.name(), "class", c.name(),
                "subjectId", s.getSubjectId(), "topics", topicIds.size()));
    }

    /**
     * Copies the syllabi of one year to another, moving each target month by the months between the two years'
     * starts. Class and subject pairs that already have a syllabus in the target year, or that the caller may not
     * change, are skipped.
     */
    public CopyResult copy(CopyForm form, Actor actor) {
        TenantContext.require();
        YearInfo from = support.yearInBody(form.fromYearId(), "fromYearId");
        YearInfo to = support.yearInBody(form.toYearId(), "toYearId");
        if (from.id().equals(to.id())) {
            throw ApiException.badRequest("Pick a different year to copy to.", "toYearId");
        }
        Set<UUID> onlyClasses = form.classIds() == null || form.classIds().isEmpty() ? null
                : Set.copyOf(form.classIds());
        long shift = ChronoUnit.MONTHS.between(YearMonth.from(from.startsOn()), YearMonth.from(to.startsOn()));
        LessonsAccess.Scope scope = access.scope(to.id());
        Map<UUID, ClassView> classes = support.classes();
        int copied = 0;
        int skipped = 0;
        for (Syllabus source : syllabi.findByAcademicYearId(from.id())) {
            if (onlyClasses != null && !onlyClasses.contains(source.getClassId())) {
                continue;
            }
            ClassView c = classes.get(source.getClassId());
            if (c == null || !canEdit(scope, c, source.getSubjectId())
                    || syllabi.findByAcademicYearIdAndClassIdAndSubjectId(to.id(), source.getClassId(),
                            source.getSubjectId()).isPresent()) {
                skipped++;
                continue;
            }
            Map<UUID, List<SyllabusTopic>> byUnit = topics.findBySyllabusIdOrderByPosition(source.getId()).stream()
                    .collect(Collectors.groupingBy(SyllabusTopic::getUnitId));
            List<UnitDraft> drafts = units.findBySyllabusIdOrderByPosition(source.getId()).stream()
                    .map(u -> new UnitDraft(null, u.getTitle(), byUnit.getOrDefault(u.getId(), List.of()).stream()
                            .map(t -> new TopicDraft(null, t.getTitle(), t.getPlannedPeriods(),
                                    t.getTargetMonth() == null ? null
                                            : YearMonth.from(t.getTargetMonth()).plusMonths(shift)))
                            .toList()))
                    .toList();
            save(to, source.getClassId(), source.getSubjectId(), drafts, actor);
            copied++;
        }
        audit.record(actor, "syllabus.copied", "syllabus", null, Map.of("from", from.name(), "to", to.name(),
                "copied", copied, "skipped", skipped));
        return new CopyResult(copied, skipped);
    }

    private void mustEdit(YearInfo year, ClassView c, UUID subjectId) {
        if (!canEdit(access.scope(year.id()), c, subjectId)) {
            throw LessonsAccess.notYours("You can change the syllabus only of subjects you teach in the class.",
                    "subjectId");
        }
    }

    /** Cleans the units and topics of a form; target months must fall within the academic year. */
    private List<UnitDraft> drafts(List<UnitForm> forms, YearInfo year) {
        List<UnitDraft> result = new ArrayList<>();
        int total = 0;
        for (UnitForm u : forms) {
            String title = LessonsSupport.required(u.title(), 200, "units", "unit name", "Give every unit a name.");
            List<TopicDraft> topicDrafts = new ArrayList<>();
            for (TopicForm t : u.topics()) {
                String topicTitle = LessonsSupport.required(t.title(), 200, "units", "topic name",
                        "Give every topic a name.");
                checkMonth(t.targetMonth(), year, "units");
                topicDrafts.add(new TopicDraft(t.id(), topicTitle, t.plannedPeriods(), t.targetMonth()));
            }
            total += topicDrafts.size();
            result.add(new UnitDraft(u.id(), title, topicDrafts));
        }
        if (total > MAX_TOPICS) {
            throw ApiException.badRequest("A syllabus can have at most " + MAX_TOPICS + " topics.", "units");
        }
        return result;
    }

    static void checkMonth(YearMonth month, YearInfo year, String field) {
        if (month == null) {
            return;
        }
        if (month.isBefore(YearMonth.from(year.startsOn())) || month.isAfter(YearMonth.from(year.endsOn()))) {
            throw ApiException.badRequest("Pick target months within " + year.name() + " ("
                    + YearMonth.from(year.startsOn()) + " to " + YearMonth.from(year.endsOn()) + ").", field);
        }
    }

    static boolean monthInYear(YearMonth month, YearInfo year) {
        return !month.isBefore(YearMonth.from(year.startsOn())) && !month.isAfter(YearMonth.from(year.endsOn()));
    }
}
