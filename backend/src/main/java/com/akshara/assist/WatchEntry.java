package com.akshara.assist;

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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A student on the early warning watch list: the rule-based score, its level, the reasons as JSON (each signal's
 * contribution), who follows it up and where that stands. Never shown to parents or students.
 */
@Entity
@Table(schema = "assist", name = "watch_entry")
class WatchEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false)
    private String studentName;

    @Column(nullable = false)
    private UUID sectionId;

    @Column(nullable = false)
    private String classLabel;

    @Column(nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskLevel level;

    @Column(nullable = false)
    private String reasonsJson;

    @Column(nullable = false)
    private Instant computedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WatchStatus status = WatchStatus.OPEN;

    private UUID assignedToId;

    private String assignedToName;

    private Instant parentInformedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected WatchEntry() {
    }

    WatchEntry(UUID studentId, Instant at) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.createdAt = at;
        this.updatedAt = at;
    }

    void score(String studentName, UUID sectionId, String classLabel, int score, RiskLevel level, String reasonsJson,
            Instant at) {
        this.studentName = studentName;
        this.sectionId = sectionId;
        this.classLabel = classLabel;
        this.score = score;
        this.level = level;
        this.reasonsJson = reasonsJson;
        this.computedAt = at;
    }

    void assign(UUID userId, String userName) {
        this.assignedToId = userId;
        this.assignedToName = userName;
        if (userId != null && status == WatchStatus.OPEN) {
            status = WatchStatus.FOLLOWING;
        }
    }

    void setStatus(WatchStatus status) {
        this.status = status;
    }

    void parentInformed(Instant at) {
        this.parentInformedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    String getStudentName() {
        return studentName;
    }

    UUID getSectionId() {
        return sectionId;
    }

    String getClassLabel() {
        return classLabel;
    }

    int getScore() {
        return score;
    }

    RiskLevel getLevel() {
        return level;
    }

    String getReasonsJson() {
        return reasonsJson;
    }

    Instant getComputedAt() {
        return computedAt;
    }

    WatchStatus getStatus() {
        return status;
    }

    UUID getAssignedToId() {
        return assignedToId;
    }

    String getAssignedToName() {
        return assignedToName;
    }

    Instant getParentInformedAt() {
        return parentInformedAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getVersion() {
        return version;
    }
}
