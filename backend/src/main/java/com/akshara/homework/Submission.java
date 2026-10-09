package com.akshara.homework;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A student's answer to a homework: text and/or files. It can be sent again until a teacher reviews it. */
@Entity
@Table(schema = "homework", name = "submission")
class Submission extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID homeworkId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    private String body;

    @Column(nullable = false)
    private Instant submittedAt;

    @Column(nullable = false)
    private boolean late;

    @Column(nullable = false)
    private int attempts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status;

    private String grade;

    private String remark;

    private UUID submittedById;

    private UUID reviewedById;

    private String reviewedByName;

    private Instant reviewedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Submission() {
    }

    Submission(UUID homeworkId, UUID studentId, String body, boolean late, UUID submittedById, Instant at) {
        this.id = Ids.newId();
        this.homeworkId = homeworkId;
        this.studentId = studentId;
        this.body = body;
        this.late = late;
        this.attempts = 1;
        this.status = SubmissionStatus.SUBMITTED;
        this.submittedById = submittedById;
        this.submittedAt = at;
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** A new answer replaces the previous one and waits for review again (also after "needs redo"). */
    void resubmit(String body, boolean late, UUID submittedById, Instant at) {
        this.body = body;
        this.late = late;
        this.submittedById = submittedById;
        this.submittedAt = at;
        this.attempts++;
        this.status = SubmissionStatus.SUBMITTED;
    }

    void review(SubmissionStatus status, String grade, String remark, Actor by, Instant at) {
        this.status = status;
        this.grade = grade;
        this.remark = remark;
        this.reviewedById = by.id();
        this.reviewedByName = by.name();
        this.reviewedAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getHomeworkId() {
        return homeworkId;
    }

    UUID getStudentId() {
        return studentId;
    }

    String getBody() {
        return body;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    boolean isLate() {
        return late;
    }

    int getAttempts() {
        return attempts;
    }

    SubmissionStatus getStatus() {
        return status;
    }

    String getGrade() {
        return grade;
    }

    String getRemark() {
        return remark;
    }

    String getReviewedByName() {
        return reviewedByName;
    }

    Instant getReviewedAt() {
        return reviewedAt;
    }
}
