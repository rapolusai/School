package com.akshara.lessons;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.lessons.LessonsViews.CoverageDetail;
import com.akshara.lessons.LessonsViews.CoverageReport;
import com.akshara.lessons.LessonsViews.CoverageRow;
import com.akshara.lessons.LessonsViews.CoverageTopic;
import com.akshara.lessons.LessonsViews.CoverageUnit;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Syllabus coverage per section and subject: the topics taught (lesson plans marked taught) against the syllabus, the
 * percent covered, and whether the section is behind schedule (a topic whose target month has ended is not taught
 * yet; see {@link CoverageMath}). The teacher shown is the one of the section's latest plan for the subject.
 */
@Service
@Transactional(readOnly = true)
public class CoverageService {

    /** The report's filters; null means any. */
    public record CoverageFilter(UUID yearId, UUID classId, UUID subjectId, UUID sectionId, boolean behindOnly) {
    }

    private final SyllabusRepository syllabi;
    private final SyllabusUnitRepository units;
    private final SyllabusTopicRepository topics;
    private final LessonPlanRepository plans;
    private final LessonsSupport support;

    CoverageService(SyllabusRepository syllabi, SyllabusUnitRepository units, SyllabusTopicRepository topics,
            LessonPlanRepository plans, LessonsSupport support) {
        this.syllabi = syllabi;
        this.units = units;
        this.topics = topics;
        this.plans = plans;
        this.support = support;
    }

    public CoverageReport report(CoverageFilter filter, LocalDate today) {
        TenantContext.require();
        Optional<YearInfo> found = support.yearOrCurrent(filter.yearId());
        YearMonth month = YearMonth.from(today);
        if (found.isEmpty()) {
            return new CoverageReport(null, null, month, 0, BigDecimal.ZERO.setScale(1), 0, List.of());
        }
        YearInfo year = found.get();
        List<CoverageRow> rows = rows(year, filter, today).stream()
                .filter(r -> !filter.behindOnly() || r.behind())
                .toList();
        BigDecimal average = rows.isEmpty() ? BigDecimal.ZERO.setScale(1)
                : rows.stream().map(CoverageRow::percent).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(rows.size()), 1, RoundingMode.HALF_UP);
        int behind = (int) rows.stream().filter(CoverageRow::behind).count();
        return new CoverageReport(year.id(), year.name(), month, rows.size(), average, behind, rows);
    }

    /** The topics of one section's syllabus for a subject, unit by unit, with what was taught when and by whom. */
    public CoverageDetail detail(UUID yearId, UUID sectionId, UUID subjectId, LocalDate today) {
        TenantContext.require();
        YearInfo year = support.yearOrCurrent(yearId).orElseThrow(() -> ApiException.notFound("Academic year"));
        SectionInfo section = support.section(sectionId);
        Syllabus s = syllabi.findByAcademicYearIdAndClassIdAndSubjectId(year.id(), section.classId(), subjectId)
                .orElseThrow(() -> ApiException.notFound("Syllabus"));
        CoverageRow summary = rows(year, new CoverageFilter(year.id(), section.classId(), subjectId, sectionId, false),
                today).stream().findFirst().orElseThrow(() -> ApiException.notFound("Syllabus"));
        YearMonth month = YearMonth.from(today);
        List<LessonPlan> sectionPlans = plans.findByAcademicYearId(year.id()).stream()
                .filter(p -> p.getSectionId().equals(sectionId) && p.getSubjectId().equals(subjectId))
                .toList();
        Map<UUID, List<LessonPlan>> byTopic = sectionPlans.stream().filter(p -> p.getTopicId() != null)
                .collect(Collectors.groupingBy(LessonPlan::getTopicId));
        Map<UUID, List<SyllabusTopic>> byUnit = topics.findBySyllabusIdOrderByPosition(s.getId()).stream()
                .collect(Collectors.groupingBy(SyllabusTopic::getUnitId));
        List<CoverageUnit> unitViews = units.findBySyllabusIdOrderByPosition(s.getId()).stream()
                .map(u -> new CoverageUnit(u.getId(), u.getTitle(), byUnit.getOrDefault(u.getId(), List.of()).stream()
                        .map(t -> {
                            List<LessonPlan> topicPlans = byTopic.getOrDefault(t.getId(), List.of());
                            Optional<LessonPlan> taught = topicPlans.stream()
                                    .filter(p -> p.getStatus() == PlanStatus.TAUGHT)
                                    .min(Comparator.comparing(LessonPlan::getTaughtOn));
                            return new CoverageTopic(t.getId(), t.getTitle(), t.getPlannedPeriods(),
                                    SyllabusService.month(t), taught.isPresent(),
                                    taught.map(LessonPlan::getTaughtOn).orElse(null),
                                    taught.map(LessonPlan::getTeacherName).orElse(null),
                                    taught.isEmpty() && CoverageMath.isDue(t.getTargetMonth(), month),
                                    topicPlans.size());
                        })
                        .toList()))
                .toList();
        return new CoverageDetail(summary, unitViews);
    }

    private List<CoverageRow> rows(YearInfo year, CoverageFilter filter, LocalDate today) {
        YearMonth month = YearMonth.from(today);
        List<Syllabus> list = syllabi.findByAcademicYearId(year.id()).stream()
                .filter(s -> filter.classId() == null || s.getClassId().equals(filter.classId()))
                .filter(s -> filter.subjectId() == null || s.getSubjectId().equals(filter.subjectId()))
                .toList();
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<CoverageMath.Topic>> topicsOf = topics.findBySyllabusIdIn(
                list.stream().map(Syllabus::getId).toList()).stream()
                .collect(Collectors.groupingBy(SyllabusTopic::getSyllabusId,
                        Collectors.mapping(t -> new CoverageMath.Topic(t.getId(), t.getTargetMonth()),
                                Collectors.toList())));
        // Topics taught per section and subject, the last day taught, and the latest plan's teacher.
        Map<String, Set<UUID>> taught = new HashMap<>();
        Map<String, LocalDate> lastTaught = new HashMap<>();
        Map<String, LessonPlan> latest = new HashMap<>();
        for (LessonPlan p : plans.findByAcademicYearId(year.id())) {
            String key = p.getSectionId() + "|" + p.getSubjectId();
            if (p.getStatus() == PlanStatus.TAUGHT && p.getTopicId() != null) {
                taught.computeIfAbsent(key, k -> new HashSet<>()).add(p.getTopicId());
                lastTaught.merge(key, p.getTaughtOn(), (a, b) -> a.isAfter(b) ? a : b);
            }
            latest.merge(key, p, (a, b) -> a.getWeekStart().isAfter(b.getWeekStart()) ? a : b);
        }
        Map<UUID, String> subjects = support.subjectNames();
        List<CoverageRow> rows = new ArrayList<>();
        for (Syllabus s : list) {
            List<CoverageMath.Topic> topicList = topicsOf.getOrDefault(s.getId(), List.of());
            for (SectionInfo section : support.sectionsOfClass(s.getClassId())) {
                if (filter.sectionId() != null && !section.id().equals(filter.sectionId())) {
                    continue;
                }
                String key = section.id() + "|" + s.getSubjectId();
                CoverageMath.Coverage c = CoverageMath.of(topicList, taught.getOrDefault(key, Set.of()), month);
                LessonPlan last = latest.get(key);
                rows.add(new CoverageRow(section.id(), section.label(), section.classId(), section.className(),
                        s.getSubjectId(), subjects.getOrDefault(s.getSubjectId(), ""), c.topics(), c.taught(),
                        c.percent(), c.due(), c.overdue(), c.expectedPercent(), c.behind(), lastTaught.get(key),
                        last == null ? null : last.getTeacherName()));
            }
        }
        Map<UUID, Integer> classOrder = new HashMap<>();
        support.sections().forEach(sec -> classOrder.putIfAbsent(sec.classId(), sec.classOrder()));
        rows.sort(Comparator.comparingInt((CoverageRow r) -> classOrder.getOrDefault(r.classId(), 0))
                .thenComparing(r -> r.className().toLowerCase())
                .thenComparing(r -> r.sectionLabel().toLowerCase())
                .thenComparing(r -> r.subjectName().toLowerCase()));
        return rows;
    }

    /** The coverage report as CSV (UTF-8 with a byte order mark). */
    public LessonsCsv csv(CoverageFilter filter, LocalDate today) {
        CoverageReport report = report(filter, today);
        LessonsCsv csv = new LessonsCsv("Class", "Section", "Subject", "Teacher", "Topics", "Taught",
                "Percent covered", "Due by now", "Overdue", "Behind schedule", "Last taught");
        for (CoverageRow r : report.rows()) {
            csv.row(r.className(), r.sectionLabel(), r.subjectName(), r.teacherName(), r.topics(), r.taught(),
                    r.percent(), r.due(), r.overdue(), r.behind() ? "Yes" : "No", r.lastTaughtOn());
        }
        return csv;
    }
}
