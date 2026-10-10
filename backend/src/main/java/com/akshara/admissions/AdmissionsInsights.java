package com.akshara.admissions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.admissions.AdmissionTypes.TimelineKind;
import com.akshara.shared.TenantContext;

/**
 * Read-only admissions figures for dashboards and reports: the funnel with conversion rates (overall, by class and
 * by source) and the follow-ups due. An application has "reached" a stage when it got to that stage or a later one;
 * one that skipped the test or interview counts as having passed it, and a rejected or withdrawn application counts
 * up to the furthest stage it was in before it closed.
 */
@Service
@Transactional(readOnly = true)
public class AdmissionsInsights {

    /** Follow-ups listed on the dashboard. */
    public static final int FOLLOW_UP_ITEMS = 5;

    /**
     * Which applications to count. Null means any: {@code academicYearIds} the years applied for, {@code from} and
     * {@code to} the days the applications were made (India time, both included).
     */
    public record FunnelFilter(Collection<UUID> academicYearIds, UUID classId, ApplicationSource source,
            LocalDate from, LocalDate to) {
    }

    /** {@code reached}: got to this stage or a later one; {@code current}: in this stage now. */
    public record FunnelStage(ApplicationStage stage, long reached, long current, Double fromPrevious,
            Double fromEnquiry) {
    }

    /** One class's or one source's funnel; {@code conversionPercent} is admitted out of all applications. */
    public record FunnelLine(UUID classId, String className, ApplicationSource source, long total, long applied,
            long assessed, long offered, long admitted, long rejected, long withdrawn, Double conversionPercent) {
    }

    public record Funnel(long total, List<FunnelStage> stages, long open, long rejected, long withdrawn,
            Double conversionPercent, List<FunnelLine> byClass, List<FunnelLine> bySource) {
    }

    public record FollowUp(UUID id, String childName, UUID classId, String className, ApplicationStage stage,
            LocalDate followUpOn) {
    }

    /** Open applications with a follow-up date: due today, overdue, and due in the next seven days. */
    public record FollowUps(LocalDate date, long dueToday, long overdue, long upcoming, List<FollowUp> items) {
    }

    private record Row(UUID id, ApplicationStage stage, UUID classId, ApplicationSource source) {
    }

    private record Where(String jpql, Map<String, Object> params) {
    }

    private final EntityManager entityManager;
    private final AcademicsService academics;

    AdmissionsInsights(EntityManager entityManager, AcademicsService academics) {
        this.entityManager = entityManager;
        this.academics = academics;
    }

    public static LocalDate today() {
        return AdmissionsService.today();
    }

    // ------------------------------------------------------------------ the funnel

    public Funnel funnel(FunnelFilter filter) {
        TenantContext.require();
        Where where = where(filter);
        TypedQuery<Object[]> rowsQuery = entityManager.createQuery(
                "select a.id, a.stage, a.classId, a.source" + where.jpql(), Object[].class);
        TypedQuery<Object[]> moves = entityManager.createQuery("select t.applicationId, t.fromStage, t.toStage "
                + "from TimelineEntry t where t.kind = :moved and t.applicationId in (select a.id" + where.jpql() + ")",
                Object[].class);
        where.params().forEach((k, v) -> {
            rowsQuery.setParameter(k, v);
            moves.setParameter(k, v);
        });
        moves.setParameter("moved", TimelineKind.STAGE_CHANGED);
        List<Row> rows = rowsQuery.getResultList().stream()
                .map(r -> new Row((UUID) r[0], (ApplicationStage) r[1], (UUID) r[2], (ApplicationSource) r[3]))
                .toList();
        Map<UUID, Integer> furthest = new HashMap<>();
        rows.forEach(r -> furthest.put(r.id(), pipelineIndex(r.stage())));
        for (Object[] m : moves.getResultList()) {
            UUID id = (UUID) m[0];
            int best = Math.max(pipelineIndex((ApplicationStage) m[1]), pipelineIndex((ApplicationStage) m[2]));
            furthest.merge(id, best, Math::max);
        }
        // Every application starts as an enquiry (or straight away as an application, which is past it).
        rows.forEach(r -> furthest.merge(r.id(), 0, Math::max));

        List<FunnelStage> stages = new ArrayList<>();
        long previous = 0;
        long first = 0;
        for (int i = 0; i < ApplicationStage.PIPELINE.size(); i++) {
            ApplicationStage stage = ApplicationStage.PIPELINE.get(i);
            int index = i;
            long reached = furthest.values().stream().filter(f -> f >= index).count();
            long current = rows.stream().filter(r -> r.stage() == stage).count();
            if (i == 0) {
                first = reached;
            }
            stages.add(new FunnelStage(stage, reached, current, i == 0 ? null : percent(reached, previous),
                    percent(reached, first)));
            previous = reached;
        }
        long rejected = rows.stream().filter(r -> r.stage() == ApplicationStage.REJECTED).count();
        long withdrawn = rows.stream().filter(r -> r.stage() == ApplicationStage.WITHDRAWN).count();
        long open = rows.stream().filter(r -> r.stage().isOpen()).count();
        long admitted = rows.stream().filter(r -> r.stage() == ApplicationStage.ADMITTED).count();

        Map<UUID, String> classNames = new LinkedHashMap<>();
        academics.classes().forEach((ClassView c) -> classNames.put(c.id(), c.name()));
        List<FunnelLine> byClass = new ArrayList<>();
        classNames.forEach((id, name) -> {
            List<Row> mine = rows.stream().filter(r -> id.equals(r.classId())).toList();
            if (!mine.isEmpty()) {
                byClass.add(line(id, name, null, mine, furthest));
            }
        });
        List<FunnelLine> bySource = new ArrayList<>();
        for (ApplicationSource source : ApplicationSource.values()) {
            List<Row> mine = rows.stream().filter(r -> r.source() == source).toList();
            if (!mine.isEmpty()) {
                bySource.add(line(null, null, source, mine, furthest));
            }
        }
        return new Funnel(rows.size(), stages, open, rejected, withdrawn, percent(admitted, rows.size()), byClass,
                bySource);
    }

    private static FunnelLine line(UUID classId, String className, ApplicationSource source, List<Row> rows,
            Map<UUID, Integer> furthest) {
        Function<Integer, Long> reached = index -> rows.stream().filter(r -> furthest.get(r.id()) >= index).count();
        Function<Predicate<Row>, Long> count = p -> rows.stream().filter(p).count();
        long admitted = count.apply(r -> r.stage() == ApplicationStage.ADMITTED);
        return new FunnelLine(classId, className, source, rows.size(), reached.apply(1), reached.apply(2),
                reached.apply(3), admitted, count.apply(r -> r.stage() == ApplicationStage.REJECTED),
                count.apply(r -> r.stage() == ApplicationStage.WITHDRAWN), percent(admitted, rows.size()));
    }

    /** The stage's place in the pipeline, or -1 for rejected, withdrawn and none. */
    private static int pipelineIndex(ApplicationStage stage) {
        return stage == null ? -1 : ApplicationStage.PIPELINE.indexOf(stage);
    }

    private static Where where(FunnelFilter filter) {
        StringBuilder where = new StringBuilder(" from Application a where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (filter.academicYearIds() != null) {
            if (filter.academicYearIds().isEmpty()) {
                where.append(" and 1 = 0");
            } else {
                where.append(" and a.academicYearId in :years");
                params.put("years", List.copyOf(filter.academicYearIds()));
            }
        }
        if (filter.classId() != null) {
            where.append(" and a.classId = :schoolClass");
            params.put("schoolClass", filter.classId());
        }
        if (filter.source() != null) {
            where.append(" and a.source = :source");
            params.put("source", filter.source());
        }
        if (filter.from() != null) {
            where.append(" and a.createdAt >= :from");
            params.put("from", AdmissionsService.startOf(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" and a.createdAt < :until");
            params.put("until", AdmissionsService.startOf(filter.to().plusDays(1)));
        }
        return new Where(where.toString(), params);
    }

    // ------------------------------------------------------------------ follow-ups

    /** Open applications whose follow-up date is today or earlier come first, the oldest at the top. */
    public FollowUps followUps() {
        TenantContext.require();
        LocalDate today = today();
        List<Application> open = entityManager.createQuery("select a from Application a where a.followUpOn is not null "
                + "and a.followUpOn <= :horizon and a.stage in :open order by a.followUpOn, a.createdAt, a.id",
                Application.class)
                .setParameter("horizon", today.plusDays(7))
                .setParameter("open", List.of(ApplicationStage.values()).stream().filter(ApplicationStage::isOpen)
                        .toList())
                .getResultList();
        long dueToday = open.stream().filter(a -> a.getFollowUpOn().equals(today)).count();
        long overdue = open.stream().filter(a -> a.getFollowUpOn().isBefore(today)).count();
        Map<UUID, String> classNames = academics.classes().stream()
                .collect(Collectors.toMap(ClassView::id, ClassView::name));
        List<FollowUp> items = open.stream().filter(a -> !a.getFollowUpOn().isAfter(today))
                .limit(FOLLOW_UP_ITEMS)
                .map(a -> new FollowUp(a.getId(), a.childName(), a.getClassId(), classNames.get(a.getClassId()),
                        a.getStage(), a.getFollowUpOn()))
                .toList();
        return new FollowUps(today, dueToday, overdue, open.size() - dueToday - overdue, items);
    }

    static Double percent(long part, long whole) {
        if (whole <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP).doubleValue();
    }
}
