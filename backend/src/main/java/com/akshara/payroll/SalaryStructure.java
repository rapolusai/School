package com.akshara.payroll;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A staff member's monthly salary from the first day of a month on, made of {@link SalaryStructureLine}s. A revision is
 * a new structure; earlier ones stay as the history and are what earlier months were paid on. Never changed once saved.
 */
@Entity
@Table(schema = "payroll", name = "salary_structure")
class SalaryStructure extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(updatable = false)
    private String note;

    @Column(updatable = false)
    private UUID createdById;

    @Column(updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected SalaryStructure() {
    }

    SalaryStructure(UUID userId, LocalDate effectiveFrom, String note, Actor by) {
        this.id = Ids.newId();
        this.userId = userId;
        this.effectiveFrom = effectiveFrom;
        this.note = note;
        this.createdById = by == null ? null : by.id();
        this.createdByName = by == null ? null : by.name();
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    String getNote() {
        return note;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
