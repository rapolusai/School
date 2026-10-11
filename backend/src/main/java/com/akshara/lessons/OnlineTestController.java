package com.akshara.lessons;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.lessons.LessonsForms.MarksForm;
import com.akshara.lessons.LessonsForms.RandomQuestionsForm;
import com.akshara.lessons.LessonsForms.TestForm;
import com.akshara.lessons.LessonsForms.TestQuestionsForm;
import com.akshara.lessons.LessonsViews.AttemptReview;
import com.akshara.lessons.LessonsViews.TestDetail;
import com.akshara.lessons.LessonsViews.TestResults;
import com.akshara.lessons.LessonsViews.TestRow;

/**
 * Online tests for staff: building a test from the question bank, publishing, closing, results with the per-question
 * analysis, marking short answers and releasing results to families. Reading needs lessons.read (or manage /
 * approve); everything else lessons.manage, on the caller's own tests.
 */
@RestController
@RequestMapping("/api/lessons/tests")
public class OnlineTestController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OnlineTestService tests;

    OnlineTestController(OnlineTestService tests) {
        this.tests = tests;
    }

    @GetMapping
    @PreAuthorize(LessonsController.READ)
    public List<TestRow> list(@RequestParam(required = false) UUID yearId,
            @RequestParam(defaultValue = "false") boolean mine) {
        return tests.list(yearId, mine, Instant.now());
    }

    @GetMapping("/{id}")
    @PreAuthorize(LessonsController.READ)
    public TestDetail detail(@PathVariable UUID id) {
        return tests.detail(id, Instant.now());
    }

    @PostMapping
    @PreAuthorize(LessonsController.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public TestDetail create(@Valid @RequestBody TestForm request) {
        return tests.create(request, LessonsAccess.actor(), Instant.now());
    }

    @PutMapping("/{id}")
    @PreAuthorize(LessonsController.MANAGE)
    public TestDetail update(@PathVariable UUID id, @Valid @RequestBody TestForm request) {
        return tests.update(id, request, LessonsAccess.actor(), Instant.now());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(LessonsController.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        tests.delete(id, LessonsAccess.actor());
    }

    /** Replaces the questions of a draft test, in order. */
    @PutMapping("/{id}/questions")
    @PreAuthorize(LessonsController.MANAGE)
    public TestDetail setQuestions(@PathVariable UUID id, @Valid @RequestBody TestQuestionsForm request) {
        return tests.setQuestions(id, request, LessonsAccess.actor(), Instant.now());
    }

    /** Adds random bank questions (by topic, difficulty or type) to a draft test. */
    @PostMapping("/{id}/questions/random")
    @PreAuthorize(LessonsController.MANAGE)
    public TestDetail addRandom(@PathVariable UUID id, @Valid @RequestBody RandomQuestionsForm request) {
        return tests.addRandom(id, request, LessonsAccess.actor(), Instant.now(), RANDOM);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize(LessonsController.MANAGE)
    public TestDetail publish(@PathVariable UUID id) {
        return tests.publish(id, LessonsAccess.actor(), Instant.now());
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize(LessonsController.MANAGE)
    public TestDetail unpublish(@PathVariable UUID id) {
        return tests.unpublish(id, LessonsAccess.actor(), Instant.now());
    }

    /** Closes a published test now; attempts in progress end now. */
    @PostMapping("/{id}/close")
    @PreAuthorize(LessonsController.MANAGE)
    public TestDetail close(@PathVariable UUID id) {
        return tests.close(id, LessonsAccess.actor(), Instant.now());
    }

    @GetMapping("/{id}/results")
    @PreAuthorize(LessonsController.READ)
    public TestResults results(@PathVariable UUID id) {
        return tests.results(id, Instant.now());
    }

    @GetMapping("/{id}/attempts/{attemptId}")
    @PreAuthorize(LessonsController.READ)
    public AttemptReview attempt(@PathVariable UUID id, @PathVariable UUID attemptId) {
        return tests.attempt(id, attemptId, Instant.now());
    }

    @PutMapping("/{id}/attempts/{attemptId}/marks")
    @PreAuthorize(LessonsController.MANAGE)
    public AttemptReview mark(@PathVariable UUID id, @PathVariable UUID attemptId,
            @Valid @RequestBody MarksForm request) {
        return tests.mark(id, attemptId, request, LessonsAccess.actor(), Instant.now());
    }

    /** Releases the results to students and parents (after the test closes and every answer is marked). */
    @PostMapping("/{id}/release")
    @PreAuthorize(LessonsController.MANAGE)
    public TestResults release(@PathVariable UUID id) {
        return tests.release(id, LessonsAccess.actor(), Instant.now());
    }
}
