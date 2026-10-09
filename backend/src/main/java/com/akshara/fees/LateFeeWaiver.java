package com.akshara.fees;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** The late fee of one student's instalment is waived, with the reason and who decided. */
@Entity
@Immutable
@Table(schema = "fees", name = "late_fee_waiver")
class LateFeeWaiver extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID studentId;

    @Column(nullable = false)
    private UUID instalmentId;

    @Column(nullable = false)
    private String reason;

    private UUID waivedBy;

    @Column(nullable = false)
    private String waivedByName;

    @Column(nullable = false)
    private Instant createdAt;

    protected LateFeeWaiver() {
    }

    LateFeeWaiver(UUID studentId, UUID instalmentId, String reason, UUID waivedBy, String waivedByName) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.instalmentId = instalmentId;
        this.reason = reason;
        this.waivedBy = waivedBy;
        this.waivedByName = waivedByName;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getInstalmentId() {
        return instalmentId;
    }

    String getReason() {
        return reason;
    }

    String getWaivedByName() {
        return waivedByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
