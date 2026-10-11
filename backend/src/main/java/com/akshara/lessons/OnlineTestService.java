package com.akshara.lessons;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.lessons.LessonsForms.MarkForm;
import com.akshara.lessons.LessonsForms.MarksForm;
import com.akshara.lessons.LessonsForms.RandomQuestionsForm;
import com.akshara.lessons.LessonsForms.TestForm;
import com.akshara.lessons.LessonsForms.TestQuestionForm;
import com.akshara.lessons.LessonsForms.TestQuestionsForm;
import com.akshara.lessons.LessonsViews.AnswerReview;
import com.akshara.lessons.LessonsViews.AttemptRef;
import com.akshara.lessons.LessonsViews.AttemptReview;
import com.akshara.lessons.LessonsViews.OptionView;
import com.akshara.lessons.LessonsViews.QuestionStat;
import com.akshara.lessons.LessonsViews.QuestionView;
import com.akshara.lessons.LessonsViews.SectionRef;
import com.akshara.lessons.LessonsViews.StudentResult;
import com.akshara.lessons.LessonsViews.TestDetail;
import com.akshara.lessons.LessonsViews.TestQuestionView;
import com.akshara.lessons.LessonsViews.TestResults;
import com.akshara.lessons.LessonsViews.TestRow;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Online tests for staff. A teacher builds a test for one or more sections of a class (subjects they teach) from the
 * question bank, by picking questions or drawing random ones by topic and difficulty; sets its window, duration and
 * attempts; publishes it; marks the short answers once students submit; and releases the results to students and
 * parents after the test closes. Only the test's author (or an approver who manages) changes it; everyone with
 * lessons.read sees tests and results. Online test scores are separate from exam marks and never reach report cards.
 */
@Service
@Transactional
public class OnlineTestService {

    static final String DRAFT = "DRAFT";
    static final String SCHEDULED = "SCHEDULED";

    private final OnlineTestRepository tests;
    private final TestSectionRepository testSections;
    private final TestQuestionRepository testQuestions;
    private final TestAttemptRepository attempts;
    private final TestAnswerRepository answers;
    private final QuestionService bank;
    private final TestMarking marking;
    private final TestNotifications notifications;
    private final StudentRoster roster;
    private final LessonsSupport support;
    private final LessonsAccess access;
    private final AuditService audit;

    OnlineTestService(OnlineTestRepository tests, TestSectionRepository testSections,
            TestQuestionRepository testQuestions, TestAttemptRepository attempts, TestAnswerRepository answers,
            QuestionService bank, TestMarking marking, TestNotifications notifications, StudentRoster roster,
            LessonsSupport support, LessonsAccess access, AuditService audit) {
        this.tests = tests;
        this.testSections = testSections;
        this.testQuestions = testQuestions;
        this.attempts = attempts;
        this.answers = answers;
        this.bank = bank;
        this.marking = marking;
        this.notifications = notifications;
        this.roster = roster;
        this.support = support;
        this.access = access;
        this.audit = audit;
    }

    /** DRAFT, SCHEDULED (published, not open yet), OPEN or CLOSED. */
    static String state(OnlineTest t, Instant now) {
        if (!t.isPublished()) {
            return DRAFT;
        }
        TestTiming.Window w = TestTiming.window(t.getOpensAt(), t.getClosesAt(), now);
        return w == TestTiming.Window.UPCOMING ? SCHEDULED : w.name();
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<TestRow> list(UUID yearId, boolean mine, Instant now) {
        TenantContext.require();
        Optional<YearInfo> year = support.yearOrCurrent(yearId);
        if (year.isEmpty()) {
            return List.of();
        }
        LessonsAccess.Scope scope = access.scope(year.get().id());
        List<OnlineTest> list = tests.findByAcademicYearId(year.get().id()).stream()
                .filter(t -> !mine || (scope.userId() != null && scope.userId().equals(t.getCreatedById())))
                .sorted(Comparator.comparing(OnlineTest::getOpensAt).reversed())
                .toList();
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = list.stream().map(OnlineTest::getId).collect(Collectors.toSet());
        Map<UUID, List<TestSection>> sectionsOf = testSections.findByTestIdIn(ids).stream()
                .collect(Collectors.groupingBy(TestSection::getTestId));
        Map<UUID, List<TestQuestion>> questionsOf = testQuestions.findByTestIdIn(ids).stream()
                .collect(Collectors.groupingBy(TestQuestion::getTestId));
        Map<UUID, List<TestAttempt>> attemptsOf = attempts.findByTestIdIn(ids).stream()
                .collect(Collectors.groupingBy(TestAttempt::getTestId));
        Map<UUID, SectionInfo> sections = support.sectionMap();
        Map<UUID, Long> perSection = roster.activePerSection(year.get().id());
        Map<UUID, ClassView> classes = support.classes();
        Map<UUID, String> subjects = support.subjectNames();
        return list.stream().map(t -> {
            List<UUID> sectionIds = sectionsOf.getOrDefault(t.getId(), List.of()).stream()
                    .map(TestSection::getSectionId).toList();
            List<TestQuestion> tq = questionsOf.getOrDefault(t.getId(), List.of());
            List<TestAttempt> at = attemptsOf.getOrDefault(t.getId(), List.of());
            ClassView c = classes.get(t.getClassId());
            return new TestRow(t.getId(), t.getTitle(), t.getClassId(), c == null ? "" : c.name(), t.getSubjectId(),
                    subjects.getOrDefault(t.getSubjectId(), ""), sectionRefs(sectionIds, sections), t.getOpensAt(),
                    t.getClosesAt(), t.getDurationMinutes(), t.getAttemptsAllowed(), state(t, now), tq.size(),
                    total(tq), sectionIds.stream().mapToInt(s -> perSection.getOrDefault(s, 0L).intValue()).sum(),
                    (int) at.stream().filter(TestAttempt::isSubmitted).map(TestAttempt::getStudentId).distinct()
                            .count(),
                    at.stream().filter(TestAttempt::isSubmitted).mapToInt(TestAttempt::getPendingMarking).sum(),
                    t.isReleased(), t.getCreatedByName(), scope.owns(t.getCreatedById()));
        }).toList();
    }

    @Transactional(readOnly = true)
    public TestDetail detail(UUID id, Instant now) {
        TenantContext.require();
        return view(test(id), now);
    }

    OnlineTest test(UUID id) {
        return tests.findById(id).orElseThrow(() -> ApiException.notFound("Online test"));
    }

    TestDetail view(OnlineTest t, Instant now) {
        LessonsAccess.Scope scope = access.scope(t.getAcademicYearId());
        List<TestQuestion> tq = testQuestions.findByTestIdOrderByPosition(t.getId());
        Map<UUID, QuestionView> questionViews = bank.views(new ArrayList<>(bank.byIds(tq.stream()
                .map(TestQuestion::getQuestionId).toList()).values()), scope).stream()
                .collect(Collectors.toMap(QuestionView::id, Function.identity()));
        ClassView c = support.classes().get(t.getClassId());
        return new TestDetail(t.getId(), t.getAcademicYearId(), t.getTitle(), t.getInstructions(), t.getClassId(),
                c == null ? "" : c.name(), t.getSubjectId(), support.subjectNames().getOrDefault(t.getSubjectId(), ""),
                sectionRefs(sectionIds(t.getId()), support.sectionMap()), t.getOpensAt(), t.getClosesAt(),
                t.getDurationMinutes(), t.getAttemptsAllowed(), t.isShuffleQuestions(), t.isShuffleOptions(),
                t.getShowAnswers(), t.getStatus(), state(t, now), t.getPublishedAt(), t.getResultsReleasedAt(),
                t.getReleasedByName(), t.getCreatedByName(),
                tq.stream().map(q -> new TestQuestionView(q.getId(), q.getQuestionId(), q.getPosition(), q.getMarks(),
                        questionViews.get(q.getQuestionId()))).toList(),
                total(tq), (int) attempts.countByTestId(t.getId()), t.getVersion(), scope.owns(t.getCreatedById()));
    }

    List<UUID> sectionIds(UUID testId) {
        return testSections.findByTestId(testId).stream().map(TestSection::getSectionId).toList();
    }

    static List<SectionRef> sectionRefs(List<UUID> ids, Map<UUID, SectionInfo> sections) {
        return ids.stream().map(sections::get).filter(s -> s != null)
                .sorted(Comparator.comparing(SectionInfo::name))
                .map(s -> new SectionRef(s.id(), s.name(), s.label())).toList();
    }

    static BigDecimal total(List<TestQuestion> list) {
        return list.stream().map(TestQuestion::getMarks).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }

    // ------------------------------------------------------------------ building

    public TestDetail create(TestForm form, Actor actor, Instant now) {
        TenantContext.require();
        YearInfo year = support.yearInBody(form.academicYearId(), "academicYearId");
        Checked c = check(form, year);
        OnlineTest t = tests.saveAndFlush(new OnlineTest(year.id(), actor.id(), actor.name(), c.content()));
        c.sections().forEach(s -> testSections.save(new TestSection(t.getId(), s.id())));
        testSections.flush();
        audit.record(actor, "online_test.created", "online_test", t.getId(), details(t, c.sections()));
        return view(t, now);
    }

    public TestDetail update(UUID id, TestForm form, Actor actor, Instant now) {
        TenantContext.require();
        OnlineTest t = own(id);
        mustBeDraft(t);
        if (form.version() != null && form.version() != t.getVersion()) {
            throw ApiException.conflict("This test was changed elsewhere. Reload it and try again.", "version");
        }
        YearInfo year = support.year(t.getAcademicYearId());
        Checked c = check(form, year);
        boolean classChanged = !t.getClassId().equals(form.classId()) || !t.getSubjectId().equals(form.subjectId());
        if (classChanged && !testQuestions.findByTestIdOrderByPosition(t.getId()).isEmpty()) {
            throw ApiException.conflict("Remove the questions before changing the class or subject.", "subjectId");
        }
        t.update(c.content());
        tests.saveAndFlush(t);
        Set<UUID> wanted = c.sections().stream().map(SectionInfo::id).collect(Collectors.toSet());
        List<TestSection> current = testSections.findByTestId(t.getId());
        testSections.deleteAll(current.stream().filter(s -> !wanted.contains(s.getSectionId())).toList());
        Set<UUID> have = current.stream().map(TestSection::getSectionId).collect(Collectors.toSet());
        wanted.stream().filter(s -> !have.contains(s)).forEach(s -> testSections.save(new TestSection(t.getId(), s)));
        testSections.flush();
        audit.record(actor, "online_test.updated", "online_test", t.getId(), details(t, c.sections()));
        return view(t, now);
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        OnlineTest t = own(id);
        long started = attempts.countByTestId(id);
        if (started > 0) {
            throw ApiException.conflict("Students have taken this test, so it cannot be deleted.", "test");
        }
        testQuestions.deleteAll(testQuestions.findByTestIdOrderByPosition(id));
        testSections.deleteAll(testSections.findByTestId(id));
        testQuestions.flush();
        tests.delete(t);
        tests.flush();
        audit.record(actor, "online_test.deleted", "online_test", t.getId(), Map.of("title", t.getTitle()));
    }

    /** Replaces the test's questions (in order). Only a draft test's questions can change. */
    public TestDetail setQuestions(UUID id, TestQuestionsForm form, Actor actor, Instant now) {
        TenantContext.require();
        OnlineTest t = own(id);
        mustBeDraft(t);
        LessonsAccess.Scope scope = access.scope(t.getAcademicYearId());
        Set<UUID> ids = new LinkedHashSet<>();
        for (TestQuestionForm q : form.questions()) {
            if (!ids.add(q.questionId())) {
                throw ApiException.badRequest("A question appears twice.", "questions");
            }
        }
        Map<UUID, Question> found = bank.byIds(ids);
        for (UUID qid : ids) {
            Question q = found.get(qid);
            if (q == null || !QuestionService.visible(q, scope) || !q.getClassId().equals(t.getClassId())
                    || !q.getSubjectId().equals(t.getSubjectId())) {
                throw ApiException.badRequest("Pick questions of the test's class and subject from the bank.",
                        "questions");
            }
        }
        testQuestions.deleteAll(testQuestions.findByTestIdOrderByPosition(id));
        testQuestions.flush();
        int position = 0;
        for (TestQuestionForm q : form.questions()) {
            BigDecimal marks = q.marks() == null ? found.get(q.questionId()).getMarks() : q.marks();
            testQuestions.save(new TestQuestion(id, q.questionId(), ++position, marks.setScale(2,
                    RoundingMode.HALF_UP)));
        }
        testQuestions.flush();
        audit.record(actor, "online_test.questions_set", "online_test", id, Map.of("title", t.getTitle(),
                "questions", form.questions().size()));
        return view(t, now);
    }

    /** Adds random bank questions of the test's class and subject that are not in the test yet. */
    public TestDetail addRandom(UUID id, RandomQuestionsForm form, Actor actor, Instant now, Random random) {
        TenantContext.require();
        OnlineTest t = own(id);
        mustBeDraft(t);
        LessonsAccess.Scope scope = access.scope(t.getAcademicYearId());
        List<TestQuestion> current = testQuestions.findByTestIdOrderByPosition(id);
        if (current.size() + form.count() > LessonsForms.MAX_TEST_QUESTIONS) {
            throw ApiException.badRequest("A test can have at most " + LessonsForms.MAX_TEST_QUESTIONS
                    + " questions.", "count");
        }
        Set<UUID> already = current.stream().map(TestQuestion::getQuestionId).collect(Collectors.toSet());
        List<Question> pool = new ArrayList<>(bank.list(new QuestionService.QuestionFilter(t.getClassId(),
                t.getSubjectId(), form.topicId(), form.type(), form.difficulty(), null, false)).stream()
                .map(QuestionView::id).filter(q -> !already.contains(q))
                .map(q -> bank.find(q).orElseThrow())
                .filter(q -> QuestionService.visible(q, scope))
                .toList());
        if (pool.isEmpty()) {
            throw ApiException.badRequest("No more questions in the bank match.", "count");
        }
        Collections.shuffle(pool, random);
        List<Question> picked = pool.subList(0, Math.min(form.count(), pool.size()));
        int position = current.stream().mapToInt(TestQuestion::getPosition).max().orElse(0);
        for (Question q : picked) {
            testQuestions.save(new TestQuestion(id, q.getId(), ++position, q.getMarks()));
        }
        testQuestions.flush();
        audit.record(actor, "online_test.questions_set", "online_test", id, Map.of("title", t.getTitle(),
                "added", picked.size(), "questions", current.size() + picked.size()));
        return view(t, now);
    }

    // ------------------------------------------------------------------ publishing

    public TestDetail publish(UUID id, Actor actor, Instant now) {
        TenantContext.require();
        OnlineTest t = own(id);
        mustBeDraft(t);
        if (testQuestions.findByTestIdOrderByPosition(id).isEmpty()) {
            throw ApiException.conflict("Add questions before publishing the test.", "questions");
        }
        if (!t.getClosesAt().isAfter(now)) {
            throw ApiException.conflict("The closing time has passed. Change it before publishing.", "closesAt");
        }
        t.publish(now);
        tests.saveAndFlush(t);
        List<SectionInfo> sections = sectionIds(id).stream().map(support::findSection).flatMap(Optional::stream)
                .toList();
        int messages = notifications.onPublished(t, sections, support.subjectNames().getOrDefault(t.getSubjectId(),
                ""), now);
        audit.record(actor, "online_test.published", "online_test", id, Map.of("title", t.getTitle(),
                "opensAt", t.getOpensAt().toString(), "closesAt", t.getClosesAt().toString(), "messages", messages));
        return view(t, now);
    }

    public TestDetail unpublish(UUID id, Actor actor, Instant now) {
        TenantContext.require();
        OnlineTest t = own(id);
        if (!t.isPublished()) {
            throw ApiException.conflict("This test is not published.", "status");
        }
        if (attempts.countByTestId(id) > 0) {
            throw ApiException.conflict("Students have started this test, so it cannot be unpublished.", "status");
        }
        t.unpublish();
        tests.saveAndFlush(t);
        audit.record(actor, "online_test.unpublished", "online_test", id, Map.of("title", t.getTitle()));
        return view(t, now);
    }

    /** Closes a published test now: no new attempts, and attempts in progress end now. */
    public TestDetail close(UUID id, Actor actor, Instant now) {
        TenantContext.require();
        OnlineTest t = own(id);
        if (!t.isPublished()) {
            throw ApiException.conflict("Only a published test can be closed.", "status");
        }
        if (!t.getClosesAt().isAfter(now)) {
            throw ApiException.conflict("This test is already closed.", "status");
        }
        t.closeAt(now);
        tests.saveAndFlush(t);
        audit.record(actor, "online_test.closed", "online_test", id, Map.of("title", t.getTitle()));
        return view(t, now);
    }

    // ------------------------------------------------------------------ results and marking

    public TestResults results(UUID id, Instant now) {
        TenantContext.require();
        OnlineTest t = test(id);
        LessonsAccess.Scope scope = access.scope(t.getAcademicYearId());
        List<TestAttempt> all = attempts.findByTestId(id);
        marking.finishExpired(t, all, now);
        List<TestQuestion> tq = testQuestions.findByTestIdOrderByPosition(id);
        BigDecimal totalMarks = total(tq);
        Map<UUID, SectionInfo> sections = support.sectionMap();
        // The students of the test's sections, and anyone who took it but has since moved.
        Map<UUID, String> sectionOf = new LinkedHashMap<>();
        Map<UUID, RosterStudent> students = new LinkedHashMap<>();
        for (UUID sectionId : sectionIds(id)) {
            for (RosterStudent s : roster.activeInSection(t.getAcademicYearId(), sectionId)) {
                students.put(s.id(), s);
                SectionInfo info = sections.get(sectionId);
                sectionOf.put(s.id(), info == null ? "" : info.label());
            }
        }
        Set<UUID> missing = all.stream().map(TestAttempt::getStudentId).filter(s -> !students.containsKey(s))
                .collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            students.putAll(roster.students(missing, t.getAcademicYearId()));
        }
        Map<UUID, List<TestAttempt>> byStudent = all.stream()
                .collect(Collectors.groupingBy(TestAttempt::getStudentId));
        List<StudentResult> rows = new ArrayList<>();
        List<TestAttempt> best = new ArrayList<>();
        for (RosterStudent s : students.values()) {
            List<TestAttempt> mine = byStudent.getOrDefault(s.id(), List.of()).stream()
                    .sorted(Comparator.comparingInt(TestAttempt::getAttemptNo)).toList();
            Optional<TestAttempt> top = best(mine);
            top.ifPresent(best::add);
            int pending = mine.stream().filter(TestAttempt::isSubmitted).mapToInt(TestAttempt::getPendingMarking)
                    .sum();
            String status = mine.isEmpty() ? "NOT_STARTED"
                    : mine.stream().anyMatch(a -> !a.isSubmitted()) ? "IN_PROGRESS"
                            : pending > 0 ? "MARKING" : "SUBMITTED";
            BigDecimal score = top.map(TestAttempt::getScore).orElse(null);
            BigDecimal max = top.map(TestAttempt::getMaxScore).orElse(totalMarks);
            rows.add(new StudentResult(s.id(), s.fullName(), s.admissionNo(), s.rollNo(),
                    sectionOf.getOrDefault(s.id(), ""), status, top.map(TestAttempt::getId).orElse(null), score, max,
                    score == null ? null : percent(score, max), pending,
                    mine.stream().map(a -> new AttemptRef(a.getId(), a.getAttemptNo(), a.getStartedAt(),
                            a.getSubmittedAt(), a.isAutoSubmitted(), a.getScore(), a.getPendingMarking())).toList()));
        }
        rows.sort(Comparator.comparing(StudentResult::sectionLabel)
                .thenComparing(r -> r.rollNo() == null ? Integer.MAX_VALUE : r.rollNo())
                .thenComparing(StudentResult::studentName));
        List<QuestionStat> stats = questionStats(tq, best);
        List<BigDecimal> percents = rows.stream().map(StudentResult::percent).filter(p -> p != null).toList();
        int pending = rows.stream().mapToInt(StudentResult::pendingMarking).sum();
        String blocker = t.isReleased() ? null : releaseBlocker(t, pending, now);
        return new TestResults(t.getId(), t.getTitle(), state(t, now), t.isReleased(), t.getResultsReleasedAt(),
                totalMarks, rows.size(), percents.size(), pending, average(percents),
                percents.stream().max(Comparator.naturalOrder()).orElse(null),
                percents.stream().min(Comparator.naturalOrder()).orElse(null), rows, stats,
                scope.owns(t.getCreatedById()), blocker);
    }

    /** The best submitted attempt (highest score, then the earliest). */
    static Optional<TestAttempt> best(List<TestAttempt> list) {
        return list.stream().filter(TestAttempt::isSubmitted)
                .max(Comparator.comparing((TestAttempt a) -> a.getScore() == null ? BigDecimal.ZERO : a.getScore())
                        .thenComparing(Comparator.comparingInt(TestAttempt::getAttemptNo).reversed()));
    }

    private List<QuestionStat> questionStats(List<TestQuestion> tq, List<TestAttempt> best) {
        Map<UUID, List<TestAnswer>> byQuestion = best.isEmpty() ? Map.of()
                : answers.findByAttemptIdIn(best.stream().map(TestAttempt::getId).toList()).stream()
                        .collect(Collectors.groupingBy(TestAnswer::getTestQuestionId));
        Map<UUID, Question> questions = bank.byIds(tq.stream().map(TestQuestion::getQuestionId).toList());
        return tq.stream().map(q -> {
            List<TestAnswer> list = byQuestion.getOrDefault(q.getId(), List.of());
            Question question = questions.get(q.getQuestionId());
            List<BigDecimal> marked = list.stream().map(TestAnswer::getMarks).filter(m -> m != null).toList();
            int correct = (int) marked.stream().filter(m -> m.compareTo(q.getMarks()) >= 0).count();
            BigDecimal avg = marked.isEmpty() ? null : marked.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(marked.size()), 2, RoundingMode.HALF_UP);
            return new QuestionStat(q.getId(), q.getPosition(), question == null ? "" : question.getText(),
                    question == null ? null : question.getType(), question == null ? null : question.getDifficulty(),
                    q.getMarks(), (int) list.stream().filter(TestAnswer::isAnswered).count(), correct, avg,
                    marked.isEmpty() ? null : CoverageMath.percent(correct, marked.size()),
                    list.size() - marked.size());
        }).toList();
    }

    static String releaseBlocker(OnlineTest t, int pending, Instant now) {
        if (!t.isPublished()) {
            return "Publish the test first.";
        }
        if (t.getClosesAt().isAfter(now)) {
            return "The test is still open. Close it or wait until it closes.";
        }
        if (pending > 0) {
            return "Mark " + pending + (pending == 1 ? " short answer" : " short answers") + " first.";
        }
        return null;
    }

    static BigDecimal percent(BigDecimal score, BigDecimal max) {
        if (max == null || max.signum() == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return score.multiply(BigDecimal.valueOf(100)).divide(max, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> list) {
        if (list.isEmpty()) {
            return null;
        }
        return list.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(list.size()), 1, RoundingMode.HALF_UP);
    }

    public AttemptReview attempt(UUID id, UUID attemptId, Instant now) {
        TenantContext.require();
        OnlineTest t = test(id);
        TestAttempt a = attempts.findById(attemptId).filter(x -> x.getTestId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Attempt"));
        marking.finishExpired(t, List.of(a), now);
        return review(t, a);
    }

    AttemptReview review(OnlineTest t, TestAttempt a) {
        LessonsAccess.Scope scope = access.scope(t.getAcademicYearId());
        List<TestAnswer> list = answers.findByAttemptIdOrderByPosition(a.getId());
        Map<UUID, TestQuestion> tqs = testQuestions.findByTestIdOrderByPosition(t.getId()).stream()
                .collect(Collectors.toMap(TestQuestion::getId, Function.identity()));
        Map<UUID, Question> questions = bank.byIds(tqs.values().stream().map(TestQuestion::getQuestionId).toList());
        Map<UUID, List<QuestionOption>> options = bank.optionsOf(questions.keySet());
        RosterStudent student = roster.student(a.getStudentId(), t.getAcademicYearId()).orElse(null);
        String section = roster.sectionOf(a.getStudentId(), t.getAcademicYearId()).flatMap(support::findSection)
                .map(SectionInfo::label).orElse("");
        List<AnswerReview> reviews = list.stream().map(ans -> {
            TestQuestion tq = tqs.get(ans.getTestQuestionId());
            Question q = tq == null ? null : questions.get(tq.getQuestionId());
            if (q == null) {
                return null;
            }
            return new AnswerReview(ans.getId(), ans.getPosition(), tq.getId(), q.getType(), q.getText(),
                    tq.getMarks(), ordered(options.getOrDefault(q.getId(), List.of()), ans.optionOrder()),
                    ans.selected(), ans.getResponse(), q.getAnswerText(), q.getExplanation(), ans.getMarks(),
                    q.getType().autoMarked(), ans.getFeedback(), ans.getMarkedByName());
        }).filter(r -> r != null).toList();
        return new AttemptReview(a.getId(), t.getId(), t.getTitle(), a.getStudentId(),
                student == null ? "" : student.fullName(), section, a.getAttemptNo(), a.getStartedAt(),
                TestMarking.deadline(a, t), a.getSubmittedAt(), a.isAutoSubmitted(), a.getScore(), a.getMaxScore(),
                a.getPendingMarking(), reviews, a.isSubmitted() && scope.owns(t.getCreatedById()));
    }

    /** The options in the order the student saw them (any not in that order last). */
    static List<OptionView> ordered(List<QuestionOption> options, List<UUID> order) {
        Map<UUID, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            rank.put(order.get(i), i);
        }
        return options.stream()
                .sorted(Comparator.comparing((QuestionOption o) -> rank.getOrDefault(o.getId(), Integer.MAX_VALUE))
                        .thenComparingInt(QuestionOption::getPosition))
                .map(o -> new OptionView(o.getId(), o.getText(), o.isCorrect())).toList();
    }

    /** Marks (or re-marks) answers of a submitted attempt, up to each question's marks in the test. */
    public AttemptReview mark(UUID id, UUID attemptId, MarksForm form, Actor actor, Instant now) {
        TenantContext.require();
        OnlineTest t = own(id);
        TestAttempt a = attempts.lockById(attemptId).filter(x -> x.getTestId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Attempt"));
        marking.finishExpired(t, List.of(a), now);
        if (!a.isSubmitted()) {
            throw ApiException.conflict("The student is still taking the test.", "marks");
        }
        List<TestAnswer> list = answers.findByAttemptIdOrderByPosition(a.getId());
        Map<UUID, TestAnswer> byId = list.stream().collect(Collectors.toMap(TestAnswer::getId, Function.identity()));
        Map<UUID, TestQuestion> tqs = testQuestions.findByTestIdOrderByPosition(t.getId()).stream()
                .collect(Collectors.toMap(TestQuestion::getId, Function.identity()));
        Set<UUID> seen = new HashSet<>();
        for (MarkForm m : form.marks()) {
            TestAnswer ans = byId.get(m.answerId());
            if (ans == null || !seen.add(m.answerId())) {
                throw ApiException.badRequest("An answer is not part of this attempt.", "marks");
            }
            BigDecimal max = tqs.get(ans.getTestQuestionId()).getMarks();
            if (m.marks().compareTo(max) > 0) {
                throw ApiException.badRequest("Marks cannot be more than " + max.stripTrailingZeros().toPlainString()
                        + " for question " + ans.getPosition() + ".", "marks");
            }
            ans.mark(m.marks().setScale(2, RoundingMode.HALF_UP), actor.name(),
                    LessonsSupport.text(m.feedback(), 500, "marks", "feedback"), now);
        }
        answers.saveAll(list);
        answers.flush();
        marking.rescore(a, list);
        attempts.saveAndFlush(a);
        RosterStudent student = roster.student(a.getStudentId(), t.getAcademicYearId()).orElse(null);
        audit.record(actor, "online_test.marked", "online_test", t.getId(), Map.of("title", t.getTitle(),
                "student", student == null ? "" : student.fullName(), "attempt", a.getAttemptNo(),
                "answers", form.marks().size()));
        return review(t, a);
    }

    /** Releases the results to students and parents: only after the test closes and every answer is marked. */
    public TestResults release(UUID id, Actor actor, Instant now) {
        TenantContext.require();
        own(id);
        OnlineTest t = tests.lockById(id).orElseThrow(() -> ApiException.notFound("Online test"));
        if (t.isReleased()) {
            throw ApiException.conflict("The results are already released.", "status");
        }
        List<TestAttempt> all = attempts.findByTestId(id);
        marking.finishExpired(t, all, now);
        int pending = all.stream().filter(TestAttempt::isSubmitted).mapToInt(TestAttempt::getPendingMarking).sum();
        String blocker = releaseBlocker(t, pending, now);
        if (blocker != null) {
            throw ApiException.conflict(blocker, "status");
        }
        t.release(actor.name(), now);
        tests.saveAndFlush(t);
        audit.record(actor, "online_test.released", "online_test", id, Map.of("title", t.getTitle(),
                "attempts", all.size()));
        return results(id, now);
    }

    // ------------------------------------------------------------------ checks

    private record Checked(OnlineTest.Content content, List<SectionInfo> sections) {
    }

    private Checked check(TestForm form, YearInfo year) {
        ClassView c = support.classInBody(form.classId(), "classId");
        LessonsSupport.subjectOfClass(c, form.subjectId(), "subjectId");
        LessonsAccess.Scope scope = access.scope(year.id());
        List<SectionInfo> sections = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID sectionId : form.sectionIds()) {
            SectionInfo s = support.sectionInBody(sectionId, "sectionIds");
            if (!s.classId().equals(c.id())) {
                throw ApiException.badRequest("Pick sections of " + c.name() + ".", "sectionIds");
            }
            if (!seen.add(s.id())) {
                continue;
            }
            if (!scope.teaches(s.id(), form.subjectId())) {
                throw LessonsAccess.notYours("You can set tests only for the sections and subjects you teach.",
                        "sectionIds");
            }
            sections.add(s);
        }
        if (!form.closesAt().isAfter(form.opensAt())) {
            throw ApiException.badRequest("The test must close after it opens.", "closesAt");
        }
        if (form.closesAt().isAfter(form.opensAt().plus(java.time.Duration.ofDays(90)))) {
            throw ApiException.badRequest("Keep a test open for at most 90 days.", "closesAt");
        }
        return new Checked(new OnlineTest.Content(c.id(), form.subjectId(),
                LessonsSupport.required(form.title(), 200, "title", "title", "Give the test a title."),
                LessonsSupport.text(form.instructions(), 2000, "instructions", "instructions"), form.opensAt(),
                form.closesAt(), form.durationMinutes(), form.attemptsAllowed() == null ? 1 : form.attemptsAllowed(),
                Boolean.TRUE.equals(form.shuffleQuestions()), Boolean.TRUE.equals(form.shuffleOptions()),
                form.showAnswers() == null ? ShowAnswers.WITH_RESULTS : form.showAnswers()), sections);
    }

    private static void mustBeDraft(OnlineTest t) {
        if (t.isPublished()) {
            throw ApiException.conflict("Unpublish the test to change it.", "status");
        }
    }

    /** The test, if the caller may change it: its author, or an approver who also manages. 403 otherwise. */
    private OnlineTest own(UUID id) {
        OnlineTest t = test(id);
        if (!access.scope(t.getAcademicYearId()).owns(t.getCreatedById())) {
            throw LessonsAccess.notYours("Only the teacher who set this test can change it.", "test");
        }
        return t;
    }

    private static Map<String, Object> details(OnlineTest t, List<SectionInfo> sections) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("title", t.getTitle());
        d.put("sections", sections.stream().map(SectionInfo::label).toList());
        d.put("subjectId", t.getSubjectId());
        d.put("opensAt", t.getOpensAt().toString());
        d.put("closesAt", t.getClosesAt().toString());
        return d;
    }
}
