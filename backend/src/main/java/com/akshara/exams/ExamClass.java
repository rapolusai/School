package com.akshara.exams;

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
 * A class an exam covers, with when its date sheet and its results were published. Families see neither until then.
 */
@Entity
@Table(schema = "exams", name = "exam_class")
class ExamClass extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID examId;

    @Column(nullable = false, updatable = false)
    private UUID classId;

    @Column(name = "datesheet_published_at")
    private Instant dateSheetPublishedAt;

    @Column(name = "datesheet_published_by")
    private String dateSheetPublishedBy;

    private Instant resultsPublishedAt;

    private String resultsPublishedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ExamClass() {
    }

    ExamClass(UUID examId, UUID classId) {
        this.id = Ids.newId();
        this.examId = examId;
        this.classId = classId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void publishDateSheet(String by, Instant at) {
        this.dateSheetPublishedAt = at;
        this.dateSheetPublishedBy = by;
    }

    void withdrawDateSheet() {
        this.dateSheetPublishedAt = null;
        this.dateSheetPublishedBy = null;
    }

    void publishResults(String by, Instant at) {
        this.resultsPublishedAt = at;
        this.resultsPublishedBy = by;
    }

    void withdrawResults() {
        this.resultsPublishedAt = null;
        this.resultsPublishedBy = null;
    }

    /** Marks a change of the papers, so the version and updated time move. */
    void papersChanged() {
        this.updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getExamId() {
        return examId;
    }

    UUID getClassId() {
        return classId;
    }

    Instant getDateSheetPublishedAt() {
        return dateSheetPublishedAt;
    }

    String getDateSheetPublishedBy() {
        return dateSheetPublishedBy;
    }

    Instant getResultsPublishedAt() {
        return resultsPublishedAt;
    }

    String getResultsPublishedBy() {
        return resultsPublishedBy;
    }

    boolean dateSheetPublished() {
        return dateSheetPublishedAt != null;
    }

    boolean resultsPublished() {
        return resultsPublishedAt != null;
    }
}
