package com.akshara.lessons;

import java.time.Instant;
import java.time.LocalDate;
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
 * Study material: notes, files and a link for a class (every section when {@code sectionId} is null, or one section),
 * subject and optionally a topic, shown to students and parents from {@code publishOn}.
 */
@Entity
@Table(schema = "lessons", name = "material")
class Material extends AssignedIdEntity {

    /** What a teacher writes for a piece of material. */
    record Content(UUID classId, UUID sectionId, UUID subjectId, UUID topicId, String title, String notes,
            String linkUrl, LocalDate publishOn) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false)
    private UUID classId;

    private UUID sectionId;

    @Column(nullable = false)
    private UUID subjectId;

    private UUID topicId;

    @Column(nullable = false)
    private String title;

    private String notes;

    private String linkUrl;

    @Column(nullable = false)
    private LocalDate publishOn;

    private UUID createdById;

    @Column(nullable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Material() {
    }

    Material(UUID academicYearId, UUID createdById, String createdByName, Content content) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.createdById = createdById;
        this.createdByName = createdByName;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(content);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(Content c) {
        this.classId = c.classId();
        this.sectionId = c.sectionId();
        this.subjectId = c.subjectId();
        this.topicId = c.topicId();
        this.title = c.title();
        this.notes = c.notes();
        this.linkUrl = c.linkUrl();
        this.publishOn = c.publishOn();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    UUID getClassId() {
        return classId;
    }

    UUID getSectionId() {
        return sectionId;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    UUID getTopicId() {
        return topicId;
    }

    String getTitle() {
        return title;
    }

    String getNotes() {
        return notes;
    }

    String getLinkUrl() {
        return linkUrl;
    }

    LocalDate getPublishOn() {
        return publishOn;
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
}
