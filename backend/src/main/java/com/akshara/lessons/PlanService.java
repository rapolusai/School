package com.akshara.lessons;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.lessons.LessonsForms.PlanForm;
import com.akshara.lessons.LessonsViews.PlanDetail;
import com.akshara.lessons.LessonsViews.PlanRow;
import com.akshara.shared.ApiException;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/**
 * Lesson plans. A teacher (lessons.manage) plans the sections and subjects they teach, week by week, and edits only
 * their own draft or returned plans; submitting sends a plan to the approvers (lessons.approve), who approve it or
 * return it with a comment the teacher sees. An approved plan is marked taught on the day it was taught, which counts
 * its topic in the syllabus coverage. Nobody approves their own plan. Everyone with lessons.read sees every plan.
 */
@Service
@Transactional
public class PlanService {

    /** The filters of the plan list; null means any. */
    public record PlanFilter(UUID yearId, boolean mine, PlanStatus status, UUID sectionId, UUID subjectId,
            UUID teacherId, LocalDate from, LocalDate to) {
    }

    static final int MAX_ROWS = 500;

    private final LessonPlanRepository plans;
    private final SyllabusRepository syllabi;
    private final SyllabusUnitRepository units;
    private final SyllabusTopicRepository topics;
    private final LessonsSupport support;
    private final LessonsAccess access;
    private final AuditService audit;

    PlanService(LessonPlanRepository plans, SyllabusRepository syllabi, SyllabusUnitRepository units,
            SyllabusTopicRepository topics, LessonsSupport support, LessonsAccess access, AuditService audit) {
        this.plans = plans;
        this.syllabi = syllabi;
        this.units = units;
        this.topics = topics;
        this.support = support;
        this.access = access;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<PlanRow> list(PlanFilter filter, UUID me) {
        TenantContext.require();
        Optional<YearInfo> year = support.yearOrCurrent(filter.yearId());
        if (year.isEmpty()) {
            return List.of();
        }
        List<LessonPlan> found = filter.mine() && me != null
                ? plans.findByAcademicYearIdAndTeacherId(year.get().id(), me)
                : filter.status() != null ? plans.findByAcademicYearIdAndStatus(year.get().id(), filter.status())
                        : plans.findByAcademicYearId(year.get().id());
        return rows(found.stream()
                .filter(p -> filter.status() == null || p.getStatus() == filter.status())
                .filter(p -> filter.sectionId() == null || p.getSectionId().equals(filter.sectionId()))
                .filter(p -> filter.subjectId() == null || p.getSubjectId().equals(filter.subjectId()))
                .filter(p -> filter.teacherId() == null || p.getTeacherId().equals(filter.teacherId()))
                .filter(p -> filter.from() == null || !p.getWeekStart().isBefore(monday(filter.from())))
                .filter(p -> filter.to() == null || !p.getWeekStart().isAfter(filter.to()))
                .sorted(Comparator.comparing(LessonPlan::getWeekStart).reversed()
                        .thenComparing(p -> p.getPlanDate() == null ? p.getWeekStart() : p.getPlanDate())
                        .thenComparing(LessonPlan::getTitle))
                .limit(MAX_ROWS)
                .toList(), me);
    }

    /** Plans waiting for approval in the year (oldest submission first); the caller's own are left out. */
    @Transactional(readOnly = true)
    public List<PlanRow> queue(UUID yearId, UUID me) {
        TenantContext.require();
        Optional<YearInfo> year = support.yearOrCurrent(yearId);
        if (year.isEmpty()) {
            return List.of();
        }
        return rows(plans.findByAcademicYearIdAndStatus(year.get().id(), PlanStatus.SUBMITTED).stream()
                .filter(p -> !p.getTeacherId().equals(me))
                .sorted(Comparator.comparing(LessonPlan::getSubmittedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_ROWS)
                .toList(), me);
    }

    @Transactional(readOnly = true)
    public PlanDetail detail(UUID id, UUID me) {
        TenantContext.require();
        return view(plan(id), me);
    }

    private List<PlanRow> rows(List<LessonPlan> list, UUID me) {
        Map<UUID, SectionInfo> sections = support.sectionMap();
        Map<UUID, String> subjects = support.subjectNames();
        Map<UUID, String> topicTitles = new HashMap<>();
        List<UUID> topicIds = list.stream().map(LessonPlan::getTopicId).filter(t -> t != null).distinct().toList();
        if (!topicIds.isEmpty()) {
            topics.findAllById(topicIds).forEach(t -> topicTitles.put(t.getId(), t.getTitle()));
        }
        return list.stream().map(p -> {
            SectionInfo s = sections.get(p.getSectionId());
            return new PlanRow(p.getId(), p.getSectionId(), s == null ? "" : s.label(), p.getSubjectId(),
                    subjects.getOrDefault(p.getSubjectId(), ""), p.getTopicId(),
                    p.getTopicId() == null ? null : topicTitles.get(p.getTopicId()), p.getTitle(), p.getWeekStart(),
                    p.getPlanDate(), p.getPeriods(), p.getStatus(), p.getTeacherId(), p.getTeacherName(),
                    p.getSubmittedAt(), p.getReviewComment(), p.getTaughtOn(), p.getUpdatedAt(),
                    p.getTeacherId().equals(me));
        }).toList();
    }

    PlanDetail view(LessonPlan p, UUID me) {
        SectionInfo s = support.findSection(p.getSectionId()).orElse(null);
        String topicTitle = null;
        String unitTitle = null;
        if (p.getTopicId() != null) {
            SyllabusTopic t = topics.findById(p.getTopicId()).orElse(null);
            if (t != null) {
                topicTitle = t.getTitle();
                unitTitle = units.findById(t.getUnitId()).map(SyllabusUnit::getTitle).orElse(null);
            }
        }
        boolean mine = p.getTeacherId().equals(me);
        boolean approver = LessonsAccess.can(Permissions.LESSONS_APPROVE);
        boolean manage = LessonsAccess.can(Permissions.LESSONS_MANAGE);
        List<String> links = p.getResourceLinks() == null ? List.of() : p.getResourceLinks().lines().toList();
        return new PlanDetail(p.getId(), p.getAcademicYearId(), s == null ? null : s.classId(), p.getSectionId(),
                s == null ? "" : s.label(), p.getSubjectId(), support.subjectNames().getOrDefault(p.getSubjectId(), ""),
                p.getTopicId(), topicTitle, unitTitle, p.getTitle(), p.getWeekStart(), p.getPlanDate(),
                p.getPeriods(), p.getObjectives(), p.getTeachingMethod(), p.getResources(), links,
                p.getHomeworkIdea(), p.getAssessmentNote(), p.getStatus(), p.getTeacherId(), p.getTeacherName(),
                p.getSubmittedAt(), p.getReviewedAt(), p.getReviewedByName(), p.getReviewComment(), p.getTaughtOn(),
                p.getUpdatedAt(), p.getVersion(),
                mine && manage && p.getStatus().editable(),
                mine && manage && p.getStatus().editable(),
                approver && !mine && p.getStatus() == PlanStatus.SUBMITTED,
                mine && manage && (p.getStatus() == PlanStatus.APPROVED || p.getStatus() == PlanStatus.TAUGHT));
    }

    LessonPlan plan(UUID id) {
        return plans.findById(id).orElseThrow(() -> ApiException.notFound("Lesson plan"));
    }

    static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ------------------------------------------------------------------ the teacher's changes

    public PlanDetail create(PlanForm form, Actor actor) {
        TenantContext.require();
        YearInfo year = support.yearInBody(form.academicYearId(), "academicYearId");
        Checked c = check(form, year);
        LessonPlan p = plans.saveAndFlush(new LessonPlan(year.id(), c.section().id(), form.subjectId(), actor.id(),
                actor.name(), c.content()));
        audit.record(actor, "lesson_plan.created", "lesson_plan", p.getId(), details(p, c.section()));
        return view(p, actor.id());
    }

    public PlanDetail update(UUID id, PlanForm form, Actor actor) {
        TenantContext.require();
        LessonPlan p = own(id, actor);
        if (!p.getStatus().editable()) {
            throw ApiException.conflict("Only draft or returned plans can be changed.", "status");
        }
        if (form.version() != null && form.version() != p.getVersion()) {
            throw ApiException.conflict("This plan was changed elsewhere. Reload it and try again.", "version");
        }
        YearInfo year = support.year(p.getAcademicYearId());
        Checked c = check(form, year);
        p.update(c.section().id(), form.subjectId(), c.content());
        plans.saveAndFlush(p);
        audit.record(actor, "lesson_plan.updated", "lesson_plan", p.getId(), details(p, c.section()));
        return view(p, actor.id());
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        LessonPlan p = own(id, actor);
        if (!p.getStatus().editable()) {
            throw ApiException.conflict("Only draft or returned plans can be deleted.", "status");
        }
        plans.delete(p);
        plans.flush();
        audit.record(actor, "lesson_plan.deleted", "lesson_plan", p.getId(), Map.of("title", p.getTitle(),
                "weekStart", p.getWeekStart().toString()));
    }

    public PlanDetail submit(UUID id, Actor actor, Instant at) {
        TenantContext.require();
        LessonPlan p = own(id, actor);
        if (!p.getStatus().editable()) {
            throw ApiException.conflict("Only draft or returned plans can be submitted.", "status");
        }
        p.submit(at);
        plans.saveAndFlush(p);
        audit.record(actor, "lesson_plan.submitted", "lesson_plan", p.getId(), Map.of("title", p.getTitle(),
                "weekStart", p.getWeekStart().toString()));
        return view(p, actor.id());
    }

    public PlanDetail taught(UUID id, LocalDate taughtOn, Actor actor, LocalDate today) {
        TenantContext.require();
        LessonPlan p = own(id, actor);
        if (p.getStatus() != PlanStatus.APPROVED && p.getStatus() != PlanStatus.TAUGHT) {
            throw ApiException.conflict("Only approved plans can be marked as taught.", "status");
        }
        YearInfo year = support.year(p.getAcademicYearId());
        if (taughtOn.isAfter(today)) {
            throw ApiException.badRequest("The day taught cannot be in the future.", "taughtOn");
        }
        if (taughtOn.isBefore(year.startsOn()) || taughtOn.isAfter(year.endsOn())) {
            throw ApiException.badRequest("Pick a day within " + year.name() + ".", "taughtOn");
        }
        p.taught(taughtOn);
        plans.saveAndFlush(p);
        audit.record(actor, "lesson_plan.taught", "lesson_plan", p.getId(), Map.of("title", p.getTitle(),
                "taughtOn", taughtOn.toString()));
        return view(p, actor.id());
    }

    public PlanDetail untaught(UUID id, Actor actor) {
        TenantContext.require();
        LessonPlan p = own(id, actor);
        if (p.getStatus() != PlanStatus.TAUGHT) {
            throw ApiException.conflict("This plan is not marked as taught.", "status");
        }
        p.untaught();
        plans.saveAndFlush(p);
        audit.record(actor, "lesson_plan.untaught", "lesson_plan", p.getId(), Map.of("title", p.getTitle()));
        return view(p, actor.id());
    }

    // ------------------------------------------------------------------ approvers

    public PlanDetail approve(UUID id, String comment, Actor actor, Instant at) {
        TenantContext.require();
        LessonPlan p = reviewable(id, actor);
        String clean = LessonsSupport.text(comment, 1000, "comment", "comment");
        p.approve(actor.id(), actor.name(), clean, at);
        plans.saveAndFlush(p);
        audit.record(actor, "lesson_plan.approved", "lesson_plan", p.getId(), Map.of("title", p.getTitle(),
                "teacher", p.getTeacherName()));
        return view(p, actor.id());
    }

    public PlanDetail returnPlan(UUID id, String comment, Actor actor, Instant at) {
        TenantContext.require();
        LessonPlan p = reviewable(id, actor);
        String clean = LessonsSupport.text(comment, 1000, "comment", "comment");
        if (clean == null) {
            throw ApiException.badRequest("Say what the teacher should change.", "comment");
        }
        p.returnWith(actor.id(), actor.name(), clean, at);
        plans.saveAndFlush(p);
        audit.record(actor, "lesson_plan.returned", "lesson_plan", p.getId(), Map.of("title", p.getTitle(),
                "teacher", p.getTeacherName(), "comment", clean));
        return view(p, actor.id());
    }

    private LessonPlan reviewable(UUID id, Actor actor) {
        LessonPlan p = plan(id);
        if (p.getTeacherId().equals(actor.id())) {
            String detail = "You cannot approve or return your own plan.";
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed", detail, Map.of("plan", detail));
        }
        if (p.getStatus() != PlanStatus.SUBMITTED) {
            throw ApiException.conflict("Only submitted plans can be approved or returned.", "status");
        }
        return p;
    }

    /** The caller's own plan: 404 when unknown, 403 when someone else's. */
    private LessonPlan own(UUID id, Actor actor) {
        LessonPlan p = plan(id);
        if (!p.getTeacherId().equals(actor.id())) {
            throw LessonsAccess.notYours("Only the teacher who wrote this plan can change it.", "plan");
        }
        return p;
    }

    // ------------------------------------------------------------------ checks

    private record Checked(SectionInfo section, LessonPlan.Content content) {
    }

    private Checked check(PlanForm form, YearInfo year) {
        SectionInfo section = support.sectionInBody(form.sectionId(), "sectionId");
        ClassView c = support.classInBody(section.classId(), "sectionId");
        LessonsSupport.subjectOfClass(c, form.subjectId(), "subjectId");
        if (!access.scope(year.id()).teaches(section.id(), form.subjectId())) {
            throw LessonsAccess.notYours("You can plan lessons only for the sections and subjects you teach.",
                    "sectionId");
        }
        LocalDate weekStart = monday(form.weekStart());
        if (weekStart.plusDays(6).isBefore(year.startsOn()) || weekStart.isAfter(year.endsOn())) {
            throw ApiException.badRequest("Pick a week within " + year.name() + ".", "weekStart");
        }
        if (form.planDate() != null && (form.planDate().isBefore(weekStart)
                || form.planDate().isAfter(weekStart.plusDays(6)))) {
            throw ApiException.badRequest("Pick a day of the planned week.", "planDate");
        }
        String topicTitle = null;
        if (form.topicId() != null) {
            SyllabusTopic t = topics.findById(form.topicId()).orElseThrow(() -> ApiException.badRequest(
                    "Pick a topic of the class's syllabus for the subject.", "topicId"));
            Syllabus s = syllabi.findById(t.getSyllabusId()).orElseThrow();
            if (!s.getAcademicYearId().equals(year.id()) || !s.getClassId().equals(section.classId())
                    || !s.getSubjectId().equals(form.subjectId())) {
                throw ApiException.badRequest("Pick a topic of the class's syllabus for the subject.", "topicId");
            }
            topicTitle = t.getTitle();
        }
        String title = LessonsSupport.line(form.title(), 200, "title", "title");
        if (title == null) {
            title = topicTitle;
        }
        if (title == null) {
            throw ApiException.badRequest("Give the plan a title or pick a topic.", "title");
        }
        return new Checked(section, new LessonPlan.Content(form.topicId(), title, weekStart, form.planDate(),
                form.periods(),
                LessonsSupport.text(form.objectives(), 2000, "objectives", "objectives"),
                LessonsSupport.text(form.teachingMethod(), 2000, "teachingMethod", "teaching method"),
                LessonsSupport.text(form.resources(), 2000, "resources", "resources"),
                LessonsSupport.links(form.resourceLinks(), "resourceLinks"),
                LessonsSupport.text(form.homeworkIdea(), 1000, "homeworkIdea", "homework idea"),
                LessonsSupport.text(form.assessmentNote(), 1000, "assessmentNote", "assessment note")));
    }

    private static Map<String, Object> details(LessonPlan p, SectionInfo section) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("title", p.getTitle());
        d.put("section", section.label());
        d.put("subjectId", p.getSubjectId());
        d.put("weekStart", p.getWeekStart().toString());
        if (p.getTopicId() != null) {
            d.put("topicId", p.getTopicId());
        }
        return d;
    }
}
