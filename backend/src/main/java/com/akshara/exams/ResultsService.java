package com.akshara.exams;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.exams.ExamsViews.ExamClassDetail;
import com.akshara.exams.ExamsViews.ResultRow;
import com.akshara.exams.ExamsViews.SectionResults;
import com.akshara.exams.ExamsViews.SheetRow;
import com.akshara.exams.ExamsViews.SubjectHead;
import com.akshara.exams.ExamsViews.SubjectScore;
import com.akshara.notifications.NotificationQueue;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Results of an exam: per section, per student, with totals, percentages, grades and the pass rule of the class's
 * grading scheme, and ranks when the school turns them on. Publishing an exam's results for a class (exams.publish)
 * is an explicit, audited step that needs every sheet locked; only then do parents and students see them, and their
 * parents are told through the outbox. Withdrawing them hides them again and stops messages not yet sent.
 */
@Service
@Transactional
public class ResultsService {

    private final ExamClassRepository examClasses;
    private final ExamSupport support;
    private final GradingService grading;
    private final MarksService marks;
    private final Scorer scorer;
    private final ExamSetupService setup;
    private final StudentRoster roster;
    private final NotificationQueue queue;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    ResultsService(ExamClassRepository examClasses, ExamSupport support, GradingService grading, MarksService marks,
            Scorer scorer, ExamSetupService setup, StudentRoster roster, NotificationQueue queue, AuditService audit,
            ApplicationEventPublisher events) {
        this.examClasses = examClasses;
        this.support = support;
        this.grading = grading;
        this.marks = marks;
        this.scorer = scorer;
        this.setup = setup;
        this.roster = roster;
        this.queue = queue;
        this.audit = audit;
        this.events = events;
    }

    /** The section's results in the exam, as computed from the marks entered so far. */
    @Transactional(readOnly = true)
    public SectionResults sectionResults(UUID examId, UUID sectionId) {
        TenantContext.require();
        Exam exam = support.exam(examId);
        SectionInfo section = support.section(sectionId);
        ExamClass ec = support.examClass(examId, section.classId());
        YearInfo year = support.year(exam.getAcademicYearId());
        GradingService.Graded graded = grading.graded(section.classId(), section.className(), year.id());
        Map<UUID, SubjectView> subjects = support.subjects();
        List<RosterStudent> students = studentsOf(year, section, List.of(ec));
        Scorer.Scored scored = scorer.score(ec, students.stream().map(RosterStudent::id).toList(), graded.scale(),
                subjects);
        boolean ranksEnabled = grading.ranksEnabled();
        Map<UUID, Integer> ranks = ranksEnabled ? GradeMath.ranks(scored.byStudent().entrySet().stream()
                .filter(e -> e.getValue().overall().result() != OverallResult.INCOMPLETE)
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue().overall().percent()),
                        Map::putAll)) : Map.of();
        List<SubjectHead> heads = scored.papers().stream()
                .map(p -> new SubjectHead(p.getId(), p.getSubjectId(), ExamSupport.subjectName(subjects,
                        p.getSubjectId()), p.max().total()))
                .toList();
        List<ResultRow> rows = new ArrayList<>();
        for (RosterStudent s : students) {
            Scorer.ExamScore score = scored.byStudent().get(s.id());
            List<SubjectScore> scores = scored.papers().stream()
                    .map(p -> subjectScore(p.getSubjectId(), score.bySubject().get(p.getSubjectId())))
                    .toList();
            GradeMath.Overall o = score.overall();
            rows.add(new ResultRow(s.id(), s.fullName(), s.admissionNo(), s.rollNo(), scores, o.obtained(), o.max(),
                    o.percent(), o.band() == null ? null : o.band().grade(), o.gpa(), o.result(), ranks.get(s.id())));
        }
        int pending = (int) marks.pendingFor(ec, year).stream().filter(r -> r.sectionId().equals(sectionId)).count();
        return new SectionResults(exam.getId(), exam.getName(), support.term(exam.getTermId()).getName(), ec.getId(),
                section.classId(), section.className(), section.id(), section.label(), graded.ref(), ranksEnabled,
                ec.getResultsPublishedAt(), pending, heads, rows);
    }

    static SubjectScore subjectScore(UUID subjectId, GradeMath.SubjectScore s) {
        if (s == null) {
            return new SubjectScore(subjectId, null, null, null, null, null, null, null);
        }
        return new SubjectScore(subjectId, s.obtained(), s.max(), s.percent(),
                s.band() == null ? null : s.band().grade(), s.band() == null ? null : s.band().gradePoint(),
                s.passed(), s.code());
    }

    /**
     * Active students of the section in the year (by roll number), then students who have marks on the section's
     * sheets of these exams but have left it.
     */
    List<RosterStudent> studentsOf(YearInfo year, SectionInfo section, List<ExamClass> ecs) {
        List<RosterStudent> students = new ArrayList<>(roster.activeInSection(year.id(), section.id()));
        Set<UUID> present = students.stream().map(RosterStudent::id).collect(Collectors.toSet());
        Set<UUID> left = new LinkedHashSet<>(scorer.studentsWithMarks(ecs, section.id()));
        left.removeAll(present);
        roster.students(left, year.id()).values().stream()
                .sorted((a, b) -> a.fullName().compareToIgnoreCase(b.fullName())).forEach(students::add);
        return students;
    }

    // ------------------------------------------------------------------ publishing

    /** Shows the exam's results for the class to families and tells their parents. Every sheet must be locked. */
    public ExamClassDetail publish(UUID examId, UUID classId, Actor actor) {
        TenantContext.require();
        Exam exam = support.exam(examId);
        // Locked, so of two publishes at once the second waits and then finds the results published (409).
        ExamClass ec = support.lockExamClass(examId, classId);
        ClassView schoolClass = support.classView(classId);
        if (ec.resultsPublished()) {
            throw new ApiException(HttpStatus.CONFLICT, "Already published", "The results of " + exam.getName()
                    + " for " + schoolClass.name() + " are already published.");
        }
        YearInfo year = support.year(exam.getAcademicYearId());
        if (support.papersOf(ec.getId()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "No papers", exam.getName() + " has no papers for "
                    + schoolClass.name() + ".");
        }
        List<SheetRow> pending = marks.pendingFor(ec, year);
        if (!pending.isEmpty()) {
            String which = pending.stream().limit(3).map(r -> r.sectionLabel() + " " + r.subjectName())
                    .collect(Collectors.joining(", "));
            throw new ApiException(HttpStatus.CONFLICT, "Marks not locked", pending.size()
                    + (pending.size() == 1 ? " mark sheet is" : " mark sheets are") + " not submitted yet (" + which
                    + (pending.size() > 3 ? ", …" : "") + "). Results can be published once every sheet is locked.");
        }
        Instant now = Instant.now();
        ec.publishResults(actor.name(), now);
        examClasses.saveAndFlush(ec);
        long students = roster.activePerSection(year.id()).entrySet().stream()
                .filter(e -> support.sectionsOfClass(classId).stream().anyMatch(s -> s.id().equals(e.getKey())))
                .mapToLong(Map.Entry::getValue).sum();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exam", exam.getName());
        details.put("class", schoolClass.name());
        details.put("students", students);
        audit.record(actor, "results.published", "exam_results", ec.getId(), details);
        events.publishEvent(new ExamResultsPublished(TenantContext.require(), exam.getId(), ec.getId(), classId,
                year.id(), exam.getName(), schoolClass.name(), now));
        return setup.detail(exam, ec);
    }

    /** Hides published results from families again (to correct marks), with the reason. Unsent messages stop. */
    public ExamClassDetail withdraw(UUID examId, UUID classId, String reason, Actor actor) {
        TenantContext.require();
        Exam exam = support.exam(examId);
        ExamClass ec = support.lockExamClass(examId, classId);
        ClassView schoolClass = support.classView(classId);
        if (!ec.resultsPublished()) {
            throw new ApiException(HttpStatus.CONFLICT, "Not published", "These results are not published.");
        }
        ec.withdrawResults();
        examClasses.saveAndFlush(ec);
        int stopped = queue.skipRelated(ResultMessages.RELATED, ec.getId(), "Results withdrawn");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exam", exam.getName());
        details.put("class", schoolClass.name());
        details.put("reason", reason.trim());
        details.put("messagesStopped", stopped);
        audit.record(actor, "results.withdrawn", "exam_results", ec.getId(), details);
        return setup.detail(exam, ec);
    }
}
