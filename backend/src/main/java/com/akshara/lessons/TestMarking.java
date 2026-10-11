package com.akshara.lessons;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Submitting and scoring attempts, shared by the student's and the teacher's side. On submission every objective
 * answer is marked automatically ({@link AutoMarker}); the score is the sum of the marks so far and the short answers
 * without marks are counted as pending. An attempt whose time has run out is submitted automatically, as at its
 * deadline, the next time anyone looks at it or at the test's results.
 */
@Component
class TestMarking {

    private final TestQuestionRepository testQuestions;
    private final TestAttemptRepository attempts;
    private final TestAnswerRepository answers;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;

    TestMarking(TestQuestionRepository testQuestions, TestAttemptRepository attempts, TestAnswerRepository answers,
            QuestionRepository questions, QuestionOptionRepository options) {
        this.testQuestions = testQuestions;
        this.attempts = attempts;
        this.answers = answers;
        this.questions = questions;
        this.options = options;
    }

    /** The attempt's deadline now: its own, or the test's close if the test was closed early. */
    static Instant deadline(TestAttempt a, OnlineTest t) {
        return TestTiming.effectiveDeadline(a.getDeadlineAt(), t.getClosesAt());
    }

    /** Submits the attempt and marks its objective answers. */
    void submit(TestAttempt a, Instant at, boolean automatically) {
        a.submit(at, automatically);
        List<TestAnswer> list = answers.findByAttemptIdOrderByPosition(a.getId());
        Map<UUID, TestQuestion> tqs = testQuestions.findAllById(list.stream().map(TestAnswer::getTestQuestionId)
                .toList()).stream().collect(Collectors.toMap(TestQuestion::getId, Function.identity()));
        Map<UUID, Question> qs = questions.findAllById(tqs.values().stream().map(TestQuestion::getQuestionId)
                .toList()).stream().collect(Collectors.toMap(Question::getId, Function.identity()));
        Map<UUID, Set<UUID>> correct = new HashMap<>();
        options.findByQuestionIdIn(qs.keySet()).stream().filter(QuestionOption::isCorrect)
                .forEach(o -> correct.computeIfAbsent(o.getQuestionId(), k -> new java.util.HashSet<>())
                        .add(o.getId()));
        for (TestAnswer answer : list) {
            TestQuestion tq = tqs.get(answer.getTestQuestionId());
            Question q = tq == null ? null : qs.get(tq.getQuestionId());
            if (q == null) {
                continue;
            }
            BigDecimal marks = AutoMarker.mark(q.getType(), tq.getMarks(), correct.getOrDefault(q.getId(), Set.of()),
                    q.getAnswerText(), answer.selected(), answer.getResponse());
            if (marks != null) {
                answer.mark(marks, null, null, at);
            }
        }
        answers.saveAll(list);
        answers.flush();
        rescore(a, list);
        attempts.saveAndFlush(a);
    }

    /** Sums the marks of the attempt's answers and counts the ones still to be marked. */
    void rescore(TestAttempt a, List<TestAnswer> list) {
        BigDecimal score = list.stream().map(TestAnswer::getMarks).filter(m -> m != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        int pending = (int) list.stream().filter(x -> x.getMarks() == null).count();
        a.scored(score, pending);
    }

    /** Submits the test's attempts whose time has run out. Returns how many were submitted. */
    int finishExpired(OnlineTest t, List<TestAttempt> list, Instant now) {
        int finished = 0;
        for (TestAttempt a : list) {
            if (!a.isSubmitted() && TestTiming.expired(deadline(a, t), now)) {
                Instant deadline = deadline(a, t);
                submit(a, deadline.isBefore(a.getStartedAt()) ? a.getStartedAt() : deadline, true);
                finished++;
            }
        }
        return finished;
    }
}
