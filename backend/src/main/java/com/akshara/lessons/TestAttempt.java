package com.akshara.lessons;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A student's attempt at an online test. {@code deadlineAt} is when it started plus the test's duration, or the
 * test's close if sooner; it is submitted by the student or automatically at the deadline. {@code score} is set on
 * submission (objective answers plus short answers marked so far) and {@code pendingMarking} counts the short answers
 * still to be marked.
 */
@Entity
@Table(schema = "lessons", name = "test_attempt")
class TestAttempt extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID testId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private int attemptNo;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant deadlineAt;

    private Instant submittedAt;

    @Column(nullable = false)
    private boolean autoSubmitted;

    private BigDecimal score;

    @Column(nullable = false)
    private BigDecimal maxScore;

    @Column(nullable = false)
    private int pendingMarking;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TestAttempt() {
    }

    TestAttempt(UUID testId, UUID studentId, int attemptNo, Instant startedAt, Instant deadlineAt,
            BigDecimal maxScore) {
        this.id = Ids.newId();
        this.testId = testId;
        this.studentId = studentId;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
        this.deadlineAt = deadlineAt;
        this.maxScore = maxScore;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void submit(Instant at, boolean automatically) {
        this.submittedAt = at;
        this.autoSubmitted = automatically;
    }

    void scored(BigDecimal score, int pendingMarking) {
        this.score = score;
        this.pendingMarking = pendingMarking;
    }

    boolean isSubmitted() {
        return submittedAt != null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTestId() {
        return testId;
    }

    UUID getStudentId() {
        return studentId;
    }

    int getAttemptNo() {
        return attemptNo;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getDeadlineAt() {
        return deadlineAt;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    boolean isAutoSubmitted() {
        return autoSubmitted;
    }

    BigDecimal getScore() {
        return score;
    }

    BigDecimal getMaxScore() {
        return maxScore;
    }

    int getPendingMarking() {
        return pendingMarking;
    }
}
