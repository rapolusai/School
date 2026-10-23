package com.akshara.hostel;

import java.time.Instant;
import java.time.LocalDate;
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

/** A boarder missing at roll call or back late: stays on the warden's screen until resolved. Never deleted. */
@Entity
@Table(schema = "hostel", name = "alert")
class HostelAlert extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID hostelId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AlertKind kind;

    @Column(nullable = false, updatable = false)
    private LocalDate day;

    @Column(nullable = false, updatable = false)
    private String message;

    @Column(nullable = false)
    private boolean resolved;

    private String resolvedByName;

    private Instant resolvedAt;

    private String resolutionNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected HostelAlert() {
    }

    HostelAlert(UUID hostelId, UUID studentId, AlertKind kind, LocalDate day, String message, Instant now) {
        this.id = Ids.newId();
        this.hostelId = hostelId;
        this.studentId = studentId;
        this.kind = kind;
        this.day = day;
        this.message = message;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void resolve(String note, Actor by, Instant now) {
        this.resolved = true;
        this.resolvedByName = by.name();
        this.resolvedAt = now;
        this.resolutionNote = note;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getHostelId() {
        return hostelId;
    }

    UUID getStudentId() {
        return studentId;
    }

    AlertKind getKind() {
        return kind;
    }

    LocalDate getDay() {
        return day;
    }

    String getMessage() {
        return message;
    }

    boolean isResolved() {
        return resolved;
    }

    String getResolvedByName() {
        return resolvedByName;
    }

    Instant getResolvedAt() {
        return resolvedAt;
    }

    String getResolutionNote() {
        return resolutionNote;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
