package com.akshara.lessons;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.audit.AuditService.Actor;
import com.akshara.lessons.LessonsForms.AnswerForm;
import com.akshara.lessons.LessonsViews.LearnerAttempt;
import com.akshara.lessons.LessonsViews.Study;
import com.akshara.lessons.LessonsViews.TestIntro;
import com.akshara.shared.CurrentUser;

/**
 * Study material and online tests for students (their own class; they take tests) and parents (their own children;
 * material and released scores, read-only). A sign-in without a linked student record, a child who is not the
 * parent's, or another student's attempt is 404.
 */
@RestController
public class StudyController {

    private final LearnerService learners;

    StudyController(LearnerService learners) {
        this.learners = learners;
    }

    @GetMapping("/api/me/study")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public Study mine() {
        return learners.mine(CurrentUser.requireId(), LessonsSupport.today(), Instant.now());
    }

    @GetMapping("/api/me/children/{studentId}/study")
    @PreAuthorize("hasAuthority('child.view')")
    public Study child(@PathVariable UUID studentId) {
        return learners.child(CurrentUser.requireId(), studentId, LessonsSupport.today(), Instant.now());
    }

    @GetMapping("/api/me/tests/{testId}")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public TestIntro test(@PathVariable UUID testId) {
        return learners.intro(CurrentUser.requireId(), testId, Instant.now());
    }

    /** Starts an attempt, or returns the one in progress. */
    @PostMapping("/api/me/tests/{testId}/attempts")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public LearnerAttempt start(@PathVariable UUID testId) {
        return learners.start(CurrentUser.requireId(), testId, actor(), Instant.now());
    }

    @GetMapping("/api/me/attempts/{attemptId}")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public LearnerAttempt attempt(@PathVariable UUID attemptId) {
        return learners.attempt(CurrentUser.requireId(), attemptId, Instant.now());
    }

    /** Saves one answer while the attempt runs (409 once time is up: the attempt is then submitted). */
    @PutMapping("/api/me/attempts/{attemptId}/answers/{answerId}")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public LearnerAttempt save(@PathVariable UUID attemptId, @PathVariable UUID answerId,
            @Valid @RequestBody AnswerForm request) {
        return learners.save(CurrentUser.requireId(), attemptId, answerId, request, Instant.now());
    }

    @PostMapping("/api/me/attempts/{attemptId}/submit")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public LearnerAttempt submit(@PathVariable UUID attemptId) {
        return learners.submit(CurrentUser.requireId(), attemptId, actor(), Instant.now());
    }

    private static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Student"));
    }
}
