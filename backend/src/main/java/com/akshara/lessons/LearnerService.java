package com.akshara.lessons;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileStore;
import com.akshara.files.StoredFileInfo;
import com.akshara.lessons.LessonsForms.AnswerForm;
import com.akshara.lessons.LessonsViews.AttemptSummary;
import com.akshara.lessons.LessonsViews.LearnerAttempt;
import com.akshara.lessons.LessonsViews.LearnerOption;
import com.akshara.lessons.LessonsViews.LearnerQuestion;
import com.akshara.lessons.LessonsViews.LearnerReviewItem;
import com.akshara.lessons.LessonsViews.OptionView;
import com.akshara.lessons.LessonsViews.Study;
import com.akshara.lessons.LessonsViews.StudyMaterial;
import com.akshara.lessons.LessonsViews.StudyTest;
import com.akshara.lessons.LessonsViews.TestIntro;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;

/**
 * Study material and online tests as students and parents see them. A student sees the material of their own class
 * (for every section or their own) from its publish date, and the published tests of their section; they take a test
 * in the browser one question at a time while the server keeps the clock: answers are saved as they go, refused after
 * the deadline, and the attempt is submitted automatically when time runs out. Scores, and the correct answers if
 * the teacher shows them, appear only once the results are released. A parent sees their children's material and
 * released scores, read-only. Anyone else's is 404.
 */
@Service
@Transactional
public class LearnerService {

    private static final Random SHUFFLE = new SecureRandom();

    private final MaterialRepository materials;
    private final OnlineTestRepository tests;
    private final TestSectionRepository testSections;
    private final TestQuestionRepository testQuestions;
    private final TestAttemptRepository attempts;
    private final TestAnswerRepository answers;
    private final QuestionService bank;
    private final MaterialService materialService;
    private final TestMarking marking;
    private final FileStore files;
    private final StudentService students;
    private final StudentRoster roster;
    private final LessonsSupport support;
    private final AuditService audit;

    LearnerService(MaterialRepository materials, OnlineTestRepository tests, TestSectionRepository testSections,
            TestQuestionRepository testQuestions, TestAttemptRepository attempts, TestAnswerRepository answers,
            QuestionService bank, MaterialService materialService, TestMarking marking, FileStore files,
            StudentService students, StudentRoster roster, LessonsSupport support, AuditService audit) {
        this.materials = materials;
        this.tests = tests;
        this.testSections = testSections;
        this.testQuestions = testQuestions;
        this.attempts = attempts;
        this.answers = answers;
        this.bank = bank;
        this.materialService = materialService;
        this.marking = marking;
        this.files = files;
        this.students = students;
        this.roster = roster;
        this.support = support;
        this.audit = audit;
    }

    /** The student behind a sign-in, with their section this year (null when not enrolled). */
    record Learner(UUID id, String name, UUID yearId, SectionInfo section) {
    }

    Learner student(UUID userId) {
        ChildView me = students.studentOf(userId).orElseThrow(() -> ApiException.notFound("Student record"));
        return learner(me);
    }

    private Learner child(UUID userId, UUID studentId) {
        if (!roster.childIdsOf(userId).contains(studentId)) {
            throw ApiException.notFound("Student");
        }
        ChildView child = students.childrenOf(userId).stream().filter(c -> c.id().equals(studentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Student"));
        return learner(child);
    }

    private Learner learner(ChildView c) {
        Optional<YearInfo> year = support.currentYear();
        SectionInfo section = year.flatMap(y -> roster.sectionOf(c.id(), y.id())).flatMap(support::findSection)
                .orElse(null);
        return new Learner(c.id(), c.fullName(), year.map(YearInfo::id).orElse(null), section);
    }

    // ------------------------------------------------------------------ the study page

    @Transactional(readOnly = true)
    public Study mine(UUID userId, LocalDate today, Instant now) {
        TenantContext.require();
        return study(student(userId), today, now, true);
    }

    @Transactional(readOnly = true)
    public Study child(UUID userId, UUID studentId, LocalDate today, Instant now) {
        TenantContext.require();
        return study(child(userId, studentId), today, now, false);
    }

    private Study study(Learner learner, LocalDate today, Instant now, boolean canAct) {
        if (learner.section() == null) {
            return new Study(learner.id(), learner.name(), null, today, List.of(), List.of());
        }
        Map<UUID, String> subjects = support.subjectNames();
        List<Material> visible = visibleMaterial(learner.yearId(), Set.of(learner.section()), today);
        Map<UUID, String> topicTitles = materialService.topicTitles(visible.stream().map(Material::getTopicId)
                .toList());
        Map<UUID, List<StoredFileInfo>> attached = files.list(MaterialService.MODULE, MaterialService.MATERIAL_FILE,
                visible.stream().map(Material::getId).toList());
        List<StudyMaterial> material = visible.stream()
                .map(m -> new StudyMaterial(m.getId(), m.getTitle(), m.getNotes(), m.getLinkUrl(), m.getSubjectId(),
                        subjects.getOrDefault(m.getSubjectId(), ""),
                        m.getTopicId() == null ? null : topicTitles.get(m.getTopicId()), m.getPublishOn(),
                        m.getCreatedByName(), MaterialService.fileRefs(attached.getOrDefault(m.getId(), List.of()))))
                .toList();
        List<OnlineTest> visibleTests = visibleTests(learner);
        Set<UUID> ids = visibleTests.stream().map(OnlineTest::getId).collect(Collectors.toSet());
        Map<UUID, List<TestQuestion>> questionsOf = ids.isEmpty() ? Map.of() : testQuestions.findByTestIdIn(ids)
                .stream().collect(Collectors.groupingBy(TestQuestion::getTestId));
        Map<UUID, List<TestAttempt>> attemptsOf = ids.isEmpty() ? Map.of()
                : attempts.findByStudentIdAndTestIdIn(learner.id(), ids).stream()
                        .collect(Collectors.groupingBy(TestAttempt::getTestId));
        Comparator<StudyTest> order = Comparator
                .comparing((StudyTest t) -> switch (t.state()) {
                    case "OPEN" -> 0;
                    case "UPCOMING" -> 1;
                    default -> 2;
                })
                .thenComparing(t -> "CLOSED".equals(t.state()) ? -t.closesAt().getEpochSecond()
                        : t.opensAt().getEpochSecond());
        List<StudyTest> testRows = visibleTests.stream()
                .map(t -> row(t, questionsOf.getOrDefault(t.getId(), List.of()),
                        attemptsOf.getOrDefault(t.getId(), List.of()), subjects, canAct, now))
                .sorted(order)
                .toList();
        return new Study(learner.id(), learner.name(), learner.section().label(), today, material, testRows);
    }

    /** Material of the sections' classes (every section, or one of these) published by today, newest first. */
    List<Material> visibleMaterial(UUID yearId, Collection<SectionInfo> sections, LocalDate today) {
        if (yearId == null || sections.isEmpty()) {
            return List.of();
        }
        Set<UUID> classIds = sections.stream().map(SectionInfo::classId).collect(Collectors.toSet());
        Set<UUID> sectionIds = sections.stream().map(SectionInfo::id).collect(Collectors.toSet());
        return materials.findByAcademicYearId(yearId).stream()
                .filter(m -> classIds.contains(m.getClassId()))
                .filter(m -> m.getSectionId() == null || sectionIds.contains(m.getSectionId()))
                .filter(m -> !m.getPublishOn().isAfter(today))
                .sorted(Comparator.comparing(Material::getPublishOn).reversed()
                        .thenComparing(Material::getCreatedAt, Comparator.reverseOrder()))
                .toList();
    }

    private List<OnlineTest> visibleTests(Learner learner) {
        if (learner.section() == null) {
            return List.of();
        }
        List<UUID> testIds = testSections.findBySectionId(learner.section().id()).stream()
                .map(TestSection::getTestId).toList();
        return tests.findAllById(testIds).stream()
                .filter(t -> t.isPublished() && t.getAcademicYearId().equals(learner.yearId()))
                .toList();
    }

    private StudyTest row(OnlineTest t, List<TestQuestion> tq, List<TestAttempt> mine, Map<UUID, String> subjects,
            boolean canAct, Instant now) {
        TestTiming.Window window = TestTiming.window(t.getOpensAt(), t.getClosesAt(), now);
        Optional<TestAttempt> running = mine.stream()
                .filter(a -> !a.isSubmitted() && !TestTiming.expired(TestMarking.deadline(a, t), now)).findFirst();
        boolean anyDone = mine.stream().anyMatch(a -> a.isSubmitted()
                || TestTiming.expired(TestMarking.deadline(a, t), now));
        boolean canStart = canAct && window == TestTiming.Window.OPEN && running.isEmpty()
                && mine.size() < t.getAttemptsAllowed();
        Optional<TestAttempt> best = t.isReleased() ? OnlineTestService.best(mine) : Optional.empty();
        BigDecimal score = best.map(TestAttempt::getScore).orElse(null);
        BigDecimal max = best.map(TestAttempt::getMaxScore).orElse(null);
        return new StudyTest(t.getId(), t.getTitle(), t.getSubjectId(), subjects.getOrDefault(t.getSubjectId(), ""),
                t.getOpensAt(), t.getClosesAt(), t.getDurationMinutes(), tq.size(), OnlineTestService.total(tq),
                t.getAttemptsAllowed(), mine.size(), window.name(),
                canAct ? running.map(TestAttempt::getId).orElse(null) : null, canStart, anyDone, t.isReleased(),
                score, max, score == null ? null : OnlineTestService.percent(score, max),
                canAct ? best.map(TestAttempt::getId).orElse(null) : null);
    }

    // ------------------------------------------------------------------ taking a test

    /** A test's front page for its student: instructions, the clock rules and the attempts so far. */
    public TestIntro intro(UUID userId, UUID testId, Instant now) {
        TenantContext.require();
        Learner learner = student(userId);
        OnlineTest t = visibleTest(learner, testId);
        List<TestAttempt> mine = attempts.findByTestIdAndStudentIdOrderByAttemptNo(testId, learner.id());
        marking.finishExpired(t, mine, now);
        StudyTest row = row(t, testQuestions.findByTestIdOrderByPosition(testId), mine, support.subjectNames(), true,
                now);
        return new TestIntro(row, t.getInstructions(), mine.stream()
                .map(a -> new AttemptSummary(a.getId(), a.getAttemptNo(), a.getStartedAt(), a.getSubmittedAt(),
                        a.isAutoSubmitted(), t.isReleased() ? a.getScore() : null,
                        t.isReleased() ? a.getMaxScore() : null))
                .toList());
    }

    /**
     * Starts an attempt (or returns the one in progress). The questions, and their options, are put in the order
     * this student sees them, shuffled if the teacher chose so; the deadline is the duration from now or the test's
     * close, whichever comes first.
     */
    public LearnerAttempt start(UUID userId, UUID testId, Actor actor, Instant now) {
        TenantContext.require();
        Learner learner = student(userId);
        visibleTest(learner, testId);
        OnlineTest t = tests.lockById(testId).orElseThrow(() -> ApiException.notFound("Online test"));
        List<TestAttempt> mine = attempts.findByTestIdAndStudentIdOrderByAttemptNo(testId, learner.id());
        marking.finishExpired(t, mine, now);
        Optional<TestAttempt> running = mine.stream().filter(a -> !a.isSubmitted()).findFirst();
        if (running.isPresent()) {
            return view(t, running.get(), now);
        }
        TestTiming.Window window = TestTiming.window(t.getOpensAt(), t.getClosesAt(), now);
        if (window == TestTiming.Window.UPCOMING) {
            throw conflict("This test has not opened yet.");
        }
        if (window == TestTiming.Window.CLOSED) {
            throw conflict("This test has closed.");
        }
        if (mine.size() >= t.getAttemptsAllowed()) {
            throw conflict(t.getAttemptsAllowed() == 1 ? "You have already taken this test."
                    : "You have used all " + t.getAttemptsAllowed() + " attempts.");
        }
        List<TestQuestion> tq = new ArrayList<>(testQuestions.findByTestIdOrderByPosition(testId));
        if (tq.isEmpty()) {
            throw conflict("This test has no questions.");
        }
        if (t.isShuffleQuestions()) {
            Collections.shuffle(tq, SHUFFLE);
        }
        Map<UUID, Question> questions = bank.byIds(tq.stream().map(TestQuestion::getQuestionId).toList());
        Map<UUID, List<QuestionOption>> options = bank.optionsOf(questions.keySet());
        TestAttempt a = attempts.saveAndFlush(new TestAttempt(testId, learner.id(), mine.size() + 1, now,
                TestTiming.deadline(now, t.getDurationMinutes(), t.getClosesAt()), OnlineTestService.total(tq)));
        int position = 0;
        for (TestQuestion q : tq) {
            List<UUID> order = new ArrayList<>(options.getOrDefault(q.getQuestionId(), List.of()).stream()
                    .map(QuestionOption::getId).toList());
            if (t.isShuffleOptions()) {
                Collections.shuffle(order, SHUFFLE);
            }
            answers.save(new TestAnswer(a.getId(), q.getId(), ++position, order));
        }
        answers.flush();
        audit.record(actor, "test_attempt.started", "test_attempt", a.getId(), Map.of("test", t.getTitle(),
                "attempt", a.getAttemptNo()));
        return view(t, a, now);
    }

    public LearnerAttempt attempt(UUID userId, UUID attemptId, Instant now) {
        TenantContext.require();
        Learner learner = student(userId);
        TestAttempt a = ownAttempt(learner, attemptId, false);
        OnlineTest t = tests.findById(a.getTestId()).orElseThrow(() -> ApiException.notFound("Attempt"));
        marking.finishExpired(t, List.of(a), now);
        return view(t, a, now);
    }

    /** Saves one answer. After the deadline the attempt is submitted with what was saved, and the save is refused. */
    @Transactional(noRollbackFor = ApiException.class)
    public LearnerAttempt save(UUID userId, UUID attemptId, UUID answerId, AnswerForm form, Instant now) {
        TenantContext.require();
        Learner learner = student(userId);
        TestAttempt a = ownAttempt(learner, attemptId, true);
        OnlineTest t = tests.findById(a.getTestId()).orElseThrow(() -> ApiException.notFound("Attempt"));
        if (a.isSubmitted()) {
            throw conflict("This attempt is already submitted.");
        }
        Instant deadline = TestMarking.deadline(a, t);
        if (TestTiming.expired(deadline, now)) {
            marking.submit(a, deadline, true);
            throw conflict("Time is up. Your saved answers were submitted.");
        }
        TestAnswer answer = answers.findByAttemptIdOrderByPosition(a.getId()).stream()
                .filter(x -> x.getId().equals(answerId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Question"));
        TestQuestion tq = testQuestions.findById(answer.getTestQuestionId()).orElseThrow();
        Question q = bank.find(tq.getQuestionId()).orElseThrow();
        List<UUID> selected = List.of();
        String response = null;
        if (q.getType().hasOptions()) {
            selected = form.optionIds() == null ? List.of() : form.optionIds().stream().distinct().toList();
            if (!new HashSet<>(answer.optionOrder()).containsAll(selected)) {
                throw ApiException.badRequest("Pick from the question's options.", "optionIds");
            }
            if (q.getType() == QuestionType.MCQ_SINGLE && selected.size() > 1) {
                throw ApiException.badRequest("Pick one option.", "optionIds");
            }
            List<UUID> order = answer.optionOrder();
            selected = selected.stream().sorted(Comparator.comparingInt(order::indexOf)).toList();
        } else if (q.getType() == QuestionType.TRUE_FALSE) {
            if (form.text() != null && !form.text().isBlank()) {
                response = AutoMarker.trueFalse(form.text());
                if (response == null) {
                    throw ApiException.badRequest("Answer true or false.", "text");
                }
            }
        } else {
            response = LessonsSupport.text(form.text(), 1000, "text", "answer");
        }
        answer.save(selected, response, now);
        answers.saveAndFlush(answer);
        return view(t, a, now);
    }

    /** Submits the attempt (after the deadline it counts as submitted automatically at the deadline). */
    @Transactional(noRollbackFor = ApiException.class)
    public LearnerAttempt submit(UUID userId, UUID attemptId, Actor actor, Instant now) {
        TenantContext.require();
        Learner learner = student(userId);
        TestAttempt a = ownAttempt(learner, attemptId, true);
        OnlineTest t = tests.findById(a.getTestId()).orElseThrow(() -> ApiException.notFound("Attempt"));
        if (a.isSubmitted()) {
            throw conflict("This attempt is already submitted.");
        }
        Instant deadline = TestMarking.deadline(a, t);
        boolean late = TestTiming.expired(deadline, now);
        marking.submit(a, late ? deadline : now, late);
        audit.record(actor, "test_attempt.submitted", "test_attempt", a.getId(), Map.of("test", t.getTitle(),
                "attempt", a.getAttemptNo(), "automatically", late));
        return view(t, a, now);
    }

    private TestAttempt ownAttempt(Learner learner, UUID attemptId, boolean lock) {
        Optional<TestAttempt> found = lock ? attempts.lockById(attemptId) : attempts.findById(attemptId);
        return found.filter(a -> a.getStudentId().equals(learner.id()))
                .orElseThrow(() -> ApiException.notFound("Attempt"));
    }

    private OnlineTest visibleTest(Learner learner, UUID testId) {
        return visibleTests(learner).stream().filter(t -> t.getId().equals(testId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Online test"));
    }

    private static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", detail, Map.of("attempt", detail));
    }

    // ------------------------------------------------------------------ the attempt as the student sees it

    LearnerAttempt view(OnlineTest t, TestAttempt a, Instant now) {
        List<TestAnswer> list = answers.findByAttemptIdOrderByPosition(a.getId());
        Map<UUID, TestQuestion> tqs = testQuestions.findByTestIdOrderByPosition(t.getId()).stream()
                .collect(Collectors.toMap(TestQuestion::getId, Function.identity()));
        Map<UUID, Question> questions = bank.byIds(tqs.values().stream().map(TestQuestion::getQuestionId).toList());
        Map<UUID, List<QuestionOption>> options = bank.optionsOf(questions.keySet());
        Instant deadline = TestMarking.deadline(a, t);
        boolean running = !a.isSubmitted();
        List<LearnerQuestion> shown = new ArrayList<>();
        List<LearnerReviewItem> review = new ArrayList<>();
        boolean showAnswers = t.isReleased() && t.getShowAnswers() == ShowAnswers.WITH_RESULTS;
        for (TestAnswer ans : list) {
            TestQuestion tq = tqs.get(ans.getTestQuestionId());
            Question q = tq == null ? null : questions.get(tq.getQuestionId());
            if (q == null) {
                continue;
            }
            List<OptionView> ordered = OnlineTestService.ordered(options.getOrDefault(q.getId(), List.of()),
                    ans.optionOrder());
            if (running) {
                shown.add(new LearnerQuestion(ans.getId(), ans.getPosition(), q.getType(), q.getText(),
                        tq.getMarks(), ordered.stream().map(o -> new LearnerOption(o.id(), o.text())).toList(),
                        ans.selected(), ans.getResponse(), ans.isAnswered()));
            } else if (t.isReleased()) {
                review.add(new LearnerReviewItem(ans.getPosition(), q.getType(), q.getText(), tq.getMarks(),
                        showAnswers ? ordered : ordered.stream().map(o -> new OptionView(o.id(), o.text(), false))
                                .toList(),
                        ans.selected(), ans.getResponse(), showAnswers ? correctAnswer(q) : null,
                        showAnswers ? q.getExplanation() : null, ans.getMarks(), ans.getFeedback()));
            }
        }
        int answered = (int) list.stream().filter(TestAnswer::isAnswered).count();
        return new LearnerAttempt(a.getId(), t.getId(), t.getTitle(),
                support.subjectNames().getOrDefault(t.getSubjectId(), ""), a.getAttemptNo(), a.getStartedAt(),
                deadline, now, running ? TestTiming.secondsLeft(deadline, now) : 0, a.isSubmitted(),
                a.isAutoSubmitted(), a.getSubmittedAt(), answered, list.size(), shown, t.isReleased(),
                t.isReleased() ? a.getScore() : null, t.isReleased() ? a.getMaxScore() : null, review);
    }

    /** The answer shown after release: TRUE or FALSE, the accepted answers, or the model answer. */
    private static String correctAnswer(Question q) {
        if (q.getType().hasOptions()) {
            return null;
        }
        return q.getAnswerText();
    }

    // ------------------------------------------------------------------ files

    /** True when the material is visible to the sign-in's own student record or one of their children. */
    boolean canSee(UUID userId, Material m, LocalDate today) {
        Optional<YearInfo> year = support.currentYear();
        if (year.isEmpty() || !year.get().id().equals(m.getAcademicYearId())) {
            return false;
        }
        List<SectionInfo> sections = roster.sectionIdsOf(userId, year.get().id()).stream().map(support::findSection)
                .flatMap(Optional::stream).toList();
        return visibleMaterial(year.get().id(), sections, today).stream().anyMatch(x -> x.getId().equals(m.getId()));
    }
}
