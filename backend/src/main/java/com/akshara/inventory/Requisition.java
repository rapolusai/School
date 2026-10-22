package com.akshara.inventory;

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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A purchase requisition with its approval chain state. */
@Entity
@Table(schema = "inventory", name = "requisition")
class Requisition extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequisitionStatus status;

    private UUID departmentId;

    private String departmentName;

    @Column(updatable = false)
    private UUID requestedById;

    @Column(nullable = false, updatable = false)
    private String requestedByName;

    private LocalDate neededBy;

    @Column(nullable = false)
    private String purpose;

    private long estimatedPaise;

    private UUID headUserId;

    private String headName;

    private Long escalatedAbovePaise;

    private String rejectionReason;

    private Instant submittedAt;

    private Instant decidedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Requisition() {
    }

    Requisition(String number, RequisitionStatus status, UUID departmentId, String departmentName, UUID requestedById, String requestedByName, LocalDate neededBy, String purpose, long estimatedPaise, UUID headUserId, String headName, Long escalatedAbovePaise, String rejectionReason, Instant submittedAt, Instant decidedAt) {
        this.id = Ids.newId();
        this.number = number;
        this.status = status;
        this.departmentId = departmentId;
        this.departmentName = departmentName;
        this.requestedById = requestedById;
        this.requestedByName = requestedByName;
        this.neededBy = neededBy;
        this.purpose = purpose;
        this.estimatedPaise = estimatedPaise;
        this.headUserId = headUserId;
        this.headName = headName;
        this.escalatedAbovePaise = escalatedAbovePaise;
        this.rejectionReason = rejectionReason;
        this.submittedAt = submittedAt;
        this.decidedAt = decidedAt;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getNumber() {
        return number;
    }

    void setNumber(String number) {
        this.number = number;
    }

    RequisitionStatus getStatus() {
        return status;
    }

    void setStatus(RequisitionStatus status) {
        this.status = status;
    }

    UUID getDepartmentId() {
        return departmentId;
    }

    void setDepartment(UUID departmentId, String departmentName) {
        this.departmentId = departmentId;
        this.departmentName = departmentName;
    }

    String getDepartmentName() {
        return departmentName;
    }

    UUID getRequestedById() {
        return requestedById;
    }

    String getRequestedByName() {
        return requestedByName;
    }

    LocalDate getNeededBy() {
        return neededBy;
    }

    void setNeededBy(LocalDate neededBy) {
        this.neededBy = neededBy;
    }

    String getPurpose() {
        return purpose;
    }

    void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    long getEstimatedPaise() {
        return estimatedPaise;
    }

    void setEstimatedPaise(long estimatedPaise) {
        this.estimatedPaise = estimatedPaise;
    }

    UUID getHeadUserId() {
        return headUserId;
    }

    void setHeadUserId(UUID headUserId) {
        this.headUserId = headUserId;
    }

    String getHeadName() {
        return headName;
    }

    void setHeadName(String headName) {
        this.headName = headName;
    }

    Long getEscalatedAbovePaise() {
        return escalatedAbovePaise;
    }

    void setEscalatedAbovePaise(Long escalatedAbovePaise) {
        this.escalatedAbovePaise = escalatedAbovePaise;
    }

    String getRejectionReason() {
        return rejectionReason;
    }

    void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}
