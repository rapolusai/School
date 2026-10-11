package com.akshara.lessons;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.lessons.LessonsForms.OptionForm;
import com.akshara.lessons.LessonsForms.QuestionForm;
import com.akshara.lessons.LessonsViews.OptionView;
import com.akshara.lessons.LessonsViews.QuestionView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The school's question bank: questions per class, subject and optionally topic, with difficulty, type, marks, an
 * explanation and an optional Bloom level. A question is shared with every teacher of the school unless its author
 * keeps it private; its author (or an approver who manages) changes it. A question in a published online test is
 * locked: it cannot be changed or deleted, so results stay true to what students saw.
 */
@Service
@Transactional
public class QuestionService {

    /** The filters of the bank; null means any. {@code q} searches the question text. */
    public record QuestionFilter(UUID classId, UUID subjectId, UUID topicId, QuestionType type,
            Difficulty difficulty, String q, boolean mine) {
    }

    static final int MAX_ROWS = 1000;

    /** A checked question with its options, ready to save. */
    record Checked(Question.Content content, List<OptionForm> options) {
    }

    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final TestQuestionRepository testQuestions;
    private final OnlineTestRepository tests;
    private final SyllabusRepository syllabi;
    private final SyllabusTopicRepository topics;
    private final LessonsSupport support;
    private final LessonsAccess access;
    private final AuditService audit;

    QuestionService(QuestionRepository questions, QuestionOptionRepository options,
            TestQuestionRepository testQuestions, OnlineTestRepository tests, SyllabusRepository syllabi,
            SyllabusTopicRepository topics, LessonsSupport support, LessonsAccess access, AuditService audit) {
        this.questions = questions;
        this.options = options;
        this.testQuestions = testQuestions;
        this.tests = tests;
        this.syllabi = syllabi;
        this.topics = topics;
        this.support = support;
        this.access = access;
        this.audit = audit;
    }

    LessonsAccess.Scope scope() {
        return access.scope(support.currentYear().map(YearInfo::id).orElse(null));
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<QuestionView> list(QuestionFilter filter) {
        TenantContext.require();
        LessonsAccess.Scope scope = scope();
        String q = filter.q() == null ? "" : filter.q().strip().toLowerCase(Locale.ROOT);
        List<Question> found = questions.findAllNewestFirst().stream()
                .filter(x -> visible(x, scope))
                .filter(x -> filter.classId() == null || x.getClassId().equals(filter.classId()))
                .filter(x -> filter.subjectId() == null || x.getSubjectId().equals(filter.subjectId()))
                .filter(x -> filter.topicId() == null || filter.topicId().equals(x.getTopicId()))
                .filter(x -> filter.type() == null || x.getType() == filter.type())
                .filter(x -> filter.difficulty() == null || x.getDifficulty() == filter.difficulty())
                .filter(x -> !filter.mine() || (scope.userId() != null && scope.userId().equals(x.getCreatedById())))
                .filter(x -> q.isEmpty() || x.getText().toLowerCase(Locale.ROOT).contains(q))
                .limit(MAX_ROWS)
                .toList();
        return views(found, scope);
    }

    @Transactional(readOnly = true)
    public QuestionView detail(UUID id) {
        TenantContext.require();
        LessonsAccess.Scope scope = scope();
        Question q = questions.findById(id).filter(x -> visible(x, scope))
                .orElseThrow(() -> ApiException.notFound("Question"));
        return views(List.of(q), scope).getFirst();
    }

    static boolean visible(Question q, LessonsAccess.Scope scope) {
        return q.isShared() || scope.wholeSchool()
                || (scope.userId() != null && scope.userId().equals(q.getCreatedById()));
    }

    List<QuestionView> views(List<Question> list, LessonsAccess.Scope scope) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = list.stream().map(Question::getId).toList();
        Map<UUID, List<QuestionOption>> optionsOf = optionsOf(ids);
        Map<UUID, ClassView> classes = support.classes();
        Map<UUID, String> subjects = support.subjectNames();
        Map<UUID, String> topicTitles = new HashMap<>();
        List<UUID> topicIds = list.stream().map(Question::getTopicId).filter(t -> t != null).distinct().toList();
        if (!topicIds.isEmpty()) {
            topics.findAllById(topicIds).forEach(t -> topicTitles.put(t.getId(), t.getTitle()));
        }
        List<TestQuestion> uses = testQuestions.findByQuestionIdIn(ids);
        Set<UUID> publishedTests = new HashSet<>();
        if (!uses.isEmpty()) {
            tests.findAllById(uses.stream().map(TestQuestion::getTestId).distinct().toList()).stream()
                    .filter(OnlineTest::isPublished).forEach(t -> publishedTests.add(t.getId()));
        }
        Map<UUID, Long> useCount = uses.stream()
                .collect(Collectors.groupingBy(TestQuestion::getQuestionId, Collectors.counting()));
        Set<UUID> locked = uses.stream().filter(u -> publishedTests.contains(u.getTestId()))
                .map(TestQuestion::getQuestionId).collect(Collectors.toSet());
        return list.stream().map(q -> {
            ClassView c = classes.get(q.getClassId());
            return view(q, optionsOf.getOrDefault(q.getId(), List.of()), c == null ? "" : c.name(),
                    subjects.getOrDefault(q.getSubjectId(), ""),
                    q.getTopicId() == null ? null : topicTitles.get(q.getTopicId()), scope.owns(q.getCreatedById()),
                    useCount.getOrDefault(q.getId(), 0L).intValue(), locked.contains(q.getId()));
        }).toList();
    }

    static QuestionView view(Question q, List<QuestionOption> opts, String className, String subjectName,
            String topicTitle, boolean canEdit, int usedInTests, boolean locked) {
        return new QuestionView(q.getId(), q.getClassId(), className, q.getSubjectId(), subjectName, q.getTopicId(),
                topicTitle, q.getType(), q.getDifficulty(), q.getBloomLevel(), q.getText(), q.getAnswerText(),
                q.getExplanation(), q.getMarks(), q.isShared(),
                opts.stream().map(o -> new OptionView(o.getId(), o.getText(), o.isCorrect())).toList(),
                q.getCreatedByName(), q.getUpdatedAt(), canEdit, usedInTests, locked);
    }

    Map<UUID, List<QuestionOption>> optionsOf(Collection<UUID> questionIds) {
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<QuestionOption>> result = new HashMap<>();
        options.findByQuestionIdIn(questionIds).forEach(o ->
                result.computeIfAbsent(o.getQuestionId(), k -> new ArrayList<>()).add(o));
        result.values().forEach(l -> l.sort(java.util.Comparator.comparingInt(QuestionOption::getPosition)));
        return result;
    }

    Map<UUID, Question> byIds(Collection<UUID> ids) {
        return questions.findAllById(ids).stream().collect(Collectors.toMap(Question::getId, Function.identity()));
    }

    // ------------------------------------------------------------------ changes

    public QuestionView create(QuestionForm form, Actor actor) {
        TenantContext.require();
        Checked c = check(form);
        Question q = save(c, actor);
        audit.record(actor, "question.created", "question", q.getId(), details(q));
        return detail(q.getId());
    }

    /** Saves a checked question and its options. */
    Question save(Checked c, Actor actor) {
        Question q = questions.saveAndFlush(new Question(actor.id(), actor.name(), c.content()));
        saveOptions(q, c.options());
        return q;
    }

    private void saveOptions(Question q, List<OptionForm> forms) {
        int position = 0;
        for (OptionForm o : forms) {
            options.save(new QuestionOption(q.getId(), ++position, o.text().strip(), Boolean.TRUE.equals(o.correct())));
        }
        options.flush();
    }

    public QuestionView update(UUID id, QuestionForm form, Actor actor) {
        TenantContext.require();
        Question q = own(id);
        if (testQuestions.countInPublishedTests(id) > 0) {
            throw ApiException.conflict("This question is in a published online test, so it cannot be changed. "
                    + "Add a new question instead.", "question");
        }
        Checked c = check(form);
        q.update(c.content());
        questions.saveAndFlush(q);
        options.deleteAll(options.findByQuestionIdOrderByPosition(q.getId()));
        options.flush();
        saveOptions(q, c.options());
        audit.record(actor, "question.updated", "question", q.getId(), details(q));
        return detail(q.getId());
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        Question q = own(id);
        long used = testQuestions.countByQuestionId(id);
        if (used > 0) {
            String detail = "This question is in " + used + (used == 1 ? " online test" : " online tests")
                    + ". Remove it from " + (used == 1 ? "that test" : "those tests") + " first.";
            throw ApiException.conflict(detail, "question");
        }
        options.deleteAll(options.findByQuestionIdOrderByPosition(q.getId()));
        questions.delete(q);
        questions.flush();
        audit.record(actor, "question.deleted", "question", q.getId(), details(q));
    }

    private Question own(UUID id) {
        LessonsAccess.Scope scope = scope();
        Question q = questions.findById(id).filter(x -> visible(x, scope))
                .orElseThrow(() -> ApiException.notFound("Question"));
        if (!scope.owns(q.getCreatedById())) {
            throw LessonsAccess.notYours("Only the teacher who wrote this question can change it.", "question");
        }
        return q;
    }

    // ------------------------------------------------------------------ checks

    Checked check(QuestionForm form) {
        ClassView c = support.classInBody(form.classId(), "classId");
        LessonsSupport.subjectOfClass(c, form.subjectId(), "subjectId");
        if (form.topicId() != null) {
            SyllabusTopic t = topics.findById(form.topicId()).orElseThrow(() -> ApiException.badRequest(
                    "Pick a topic of the class's syllabus for the subject.", "topicId"));
            Syllabus s = syllabi.findById(t.getSyllabusId()).orElseThrow();
            if (!s.getClassId().equals(c.id()) || !s.getSubjectId().equals(form.subjectId())) {
                throw ApiException.badRequest("Pick a topic of the class's syllabus for the subject.", "topicId");
            }
        }
        String text = LessonsSupport.text(form.text(), 2000, "text", "question");
        if (text == null) {
            throw ApiException.badRequest("Write the question.", "text");
        }
        List<OptionForm> opts = form.options() == null ? List.of() : form.options();
        String answer = LessonsSupport.text(form.answerText(), 1000, "answerText", "answer");
        QuestionType type = form.type();
        if (type.hasOptions()) {
            if (opts.size() < 2 || opts.size() > LessonsForms.MAX_OPTIONS) {
                throw ApiException.badRequest("Give 2 to " + LessonsForms.MAX_OPTIONS + " options.", "options");
            }
            Set<String> seen = new HashSet<>();
            for (OptionForm o : opts) {
                String clean = LessonsSupport.line(o.text(), 500, "options", "option");
                if (clean == null) {
                    throw ApiException.badRequest("Fill in every option.", "options");
                }
                if (!seen.add(clean.toLowerCase(Locale.ROOT))) {
                    throw ApiException.badRequest("Two options are the same.", "options");
                }
            }
            long correct = opts.stream().filter(o -> Boolean.TRUE.equals(o.correct())).count();
            if (type == QuestionType.MCQ_SINGLE && correct != 1) {
                throw ApiException.badRequest("Mark exactly one option as correct.", "options");
            }
            if (type == QuestionType.MCQ_MULTI && correct < 1) {
                throw ApiException.badRequest("Mark at least one option as correct.", "options");
            }
            answer = null;
        } else {
            if (!opts.isEmpty()) {
                throw ApiException.badRequest("Only multiple choice questions have options.", "options");
            }
            if (type == QuestionType.TRUE_FALSE) {
                answer = AutoMarker.trueFalse(answer);
                if (answer == null) {
                    throw ApiException.badRequest("Choose whether the statement is true or false.", "answerText");
                }
            } else if (type == QuestionType.FILL_BLANK) {
                List<String> accepted = answer == null ? List.of()
                        : answer.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
                if (accepted.isEmpty()) {
                    throw ApiException.badRequest("Give the accepted answer (one per line).", "answerText");
                }
                answer = String.join("\n", accepted);
            }
        }
        BigDecimal marks = form.marks().setScale(2, java.math.RoundingMode.HALF_UP);
        return new Checked(new Question.Content(c.id(), form.subjectId(), form.topicId(), type, form.difficulty(),
                form.bloomLevel(), text, answer, LessonsSupport.text(form.explanation(), 2000, "explanation",
                        "explanation"), marks, form.shared() == null || form.shared()),
                type.hasOptions() ? opts : List.of());
    }

    private static Map<String, Object> details(Question q) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", q.getType().name());
        d.put("classId", q.getClassId());
        d.put("subjectId", q.getSubjectId());
        d.put("text", q.getText().length() > 80 ? q.getText().substring(0, 80) + "..." : q.getText());
        return d;
    }

    Optional<Question> find(UUID id) {
        return questions.findById(id);
    }
}
