package com.akshara.assist;

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

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A saved draft question paper: its blueprint and sections as JSON, the questions copied as text so the paper prints
 * the same after the bank changes. Print only.
 */
@Entity
@Table(schema = "assist", name = "paper_draft")
class PaperDraft extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID classId;

    @Column(nullable = false, updatable = false)
    private UUID subjectId;

    @Column(nullable = false)
    private String className;

    @Column(nullable = false)
    private String subjectName;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int durationMinutes;

    @Column(nullable = false)
    private BigDecimal totalMarks;

    private String instructions;

    @Column(nullable = false)
    private String blueprintJson;

    @Column(nullable = false)
    private String sectionsJson;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false, updatable = false)
    private UUID createdById;

    @Column(nullable = false, updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PaperDraft() {
    }

    PaperDraft(UUID classId, UUID subjectId, String className, String subjectName, String provider, Actor by,
            Instant at) {
        this.id = Ids.newId();
        this.classId = classId;
        this.subjectId = subjectId;
        this.className = className;
        this.subjectName = subjectName;
        this.provider = provider;
        this.createdById = by.id();
        this.createdByName = by.name();
        this.createdAt = at;
        this.updatedAt = at;
    }

    void update(String title, int durationMinutes, BigDecimal totalMarks, String instructions, String blueprintJson,
            String sectionsJson) {
        this.title = title;
        this.durationMinutes = durationMinutes;
        this.totalMarks = totalMarks;
        this.instructions = instructions;
        this.blueprintJson = blueprintJson;
        this.sectionsJson = sectionsJson;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getClassId() {
        return classId;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    String getClassName() {
        return className;
    }

    String getSubjectName() {
        return subjectName;
    }

    String getTitle() {
        return title;
    }

    int getDurationMinutes() {
        return durationMinutes;
    }

    BigDecimal getTotalMarks() {
        return totalMarks;
    }

    String getInstructions() {
        return instructions;
    }

    String getBlueprintJson() {
        return blueprintJson;
    }

    String getSectionsJson() {
        return sectionsJson;
    }

    String getProvider() {
        return provider;
    }

    UUID getCreatedById() {
        return createdById;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getVersion() {
        return version;
    }
}
