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

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A draft remark for one student in a term, overall ({@code subjectId} null) or for one subject. {@code inputsJson}
 * lists exactly what was sent to the provider. Once accepted, the text is fixed and (for an overall remark) written
 * to the exams module's term record.
 */
@Entity
@Table(schema = "assist", name = "remark_draft")
class RemarkDraft extends AssignedIdEntity {

    enum Status {
        DRAFT, ACCEPTED
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID termId;

    @Column(nullable = false, updatable = false)
    private UUID sectionId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false)
    private String studentName;

    @Column(updatable = false)
    private UUID subjectId;

    private String subjectName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RemarkTone tone;

    @Column(nullable = false, length = 2)
    private String language;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String draftText;

    private String acceptedText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(nullable = false)
    private String inputsJson;

    @Column(nullable = false, updatable = false)
    private UUID createdById;

    @Column(nullable = false)
    private String createdByName;

    private String acceptedByName;

    private Instant acceptedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected RemarkDraft() {
    }

    RemarkDraft(UUID termId, UUID sectionId, UUID studentId, String studentName, UUID subjectId, String subjectName,
            Actor by, Instant at) {
        this.id = Ids.newId();
        this.termId = termId;
        this.sectionId = sectionId;
        this.studentId = studentId;
        this.studentName = studentName;
        this.subjectId = subjectId;
        this.subjectName = subjectName;
        this.createdById = by.id();
        this.createdByName = by.name();
        this.createdAt = at;
        this.updatedAt = at;
    }

    /** A fresh draft from the provider replaces the text and inputs; the draft goes back to DRAFT. */
    void redraft(RemarkTone tone, String language, String provider, String text, String inputsJson, Actor by,
            Instant at) {
        this.tone = tone;
        this.language = language;
        this.provider = provider;
        this.draftText = text;
        this.inputsJson = inputsJson;
        this.status = Status.DRAFT;
        this.acceptedText = null;
        this.acceptedByName = null;
        this.acceptedAt = null;
        this.createdByName = by.name();
        this.updatedAt = at;
    }

    void edit(String text) {
        this.draftText = text;
    }

    void accept(String text, Actor by, Instant at) {
        this.acceptedText = text;
        this.status = Status.ACCEPTED;
        this.acceptedByName = by.name();
        this.acceptedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTermId() {
        return termId;
    }

    UUID getSectionId() {
        return sectionId;
    }

    UUID getStudentId() {
        return studentId;
    }

    String getStudentName() {
        return studentName;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    String getSubjectName() {
        return subjectName;
    }

    RemarkTone getTone() {
        return tone;
    }

    String getLanguage() {
        return language;
    }

    String getProvider() {
        return provider;
    }

    String getDraftText() {
        return draftText;
    }

    String getAcceptedText() {
        return acceptedText;
    }

    Status getStatus() {
        return status;
    }

    String getInputsJson() {
        return inputsJson;
    }

    UUID getCreatedById() {
        return createdById;
    }

    String getCreatedByName() {
        return createdByName;
    }

    String getAcceptedByName() {
        return acceptedByName;
    }

    Instant getAcceptedAt() {
        return acceptedAt;
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
