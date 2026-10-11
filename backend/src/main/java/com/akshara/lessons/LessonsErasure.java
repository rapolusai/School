package com.akshara.lessons;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.akshara.privacy.StudentDataErased;

/**
 * When a student's personal data is erased, clears what they typed in online tests and the teachers' feedback on it.
 * Scores stay as records of the test, tied to the student record the privacy module has already anonymised. Runs
 * inside the erasure's transaction.
 */
@Component
class LessonsErasure {

    private final TestAttemptRepository attempts;
    private final TestAnswerRepository answers;

    LessonsErasure(TestAttemptRepository attempts, TestAnswerRepository answers) {
        this.attempts = attempts;
        this.answers = answers;
    }

    @EventListener
    void on(StudentDataErased event) {
        List<TestAttempt> list = attempts.findByStudentId(event.studentId());
        if (list.isEmpty()) {
            return;
        }
        List<TestAnswer> written = answers.findByAttemptIdIn(list.stream().map(TestAttempt::getId).toList());
        written.forEach(TestAnswer::eraseText);
        answers.saveAll(written);
    }
}
