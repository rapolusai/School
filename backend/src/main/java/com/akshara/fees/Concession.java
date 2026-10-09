package com.akshara.fees;

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
 * A reduction for one student in one academic year, approved by someone with fees.manage. Revoked rather than
 * deleted, so the record of who approved what stays.
 */
@Entity
@Table(schema = "fees", name = "concession")
class Concession extends AssignedIdEntity {

    static final String ACTIVE = "ACTIVE";
    static final String REVOKED = "REVOKED";

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ConcessionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ConcessionMode mode;

    @Column(updatable = false)
    private Integer percentBp;

    @Column(updatable = false)
    private Long fixedPaise;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(updatable = false)
    private UUID approvedBy;

    @Column(nullable = false, updatable = false)
    private String approvedByName;

    @Column(nullable = false)
    private String status;

    private Instant revokedAt;

    private UUID revokedBy;

    private String revokedByName;

    private String revokeReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Concession() {
    }

    Concession(UUID studentId, UUID academicYearId, ConcessionType type, ConcessionMode mode, Integer percentBp,
            Long fixedPaise, String reason, UUID approvedBy, String approvedByName) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.academicYearId = academicYearId;
        this.type = type;
        this.mode = mode;
        this.percentBp = percentBp;
        this.fixedPaise = fixedPaise;
        this.reason = reason;
        this.approvedBy = approvedBy;
        this.approvedByName = approvedByName;
        this.status = ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void revoke(UUID by, String byName, String reason) {
        this.status = REVOKED;
        this.revokedAt = Instant.now();
        this.revokedBy = by;
        this.revokedByName = byName;
        this.revokeReason = reason;
    }

    boolean isActive() {
        return ACTIVE.equals(status);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    ConcessionType getType() {
        return type;
    }

    ConcessionMode getMode() {
        return mode;
    }

    Integer getPercentBp() {
        return percentBp;
    }

    Long getFixedPaise() {
        return fixedPaise;
    }

    String getReason() {
        return reason;
    }

    String getApprovedByName() {
        return approvedByName;
    }

    String getStatus() {
        return status;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }

    String getRevokedByName() {
        return revokedByName;
    }

    String getRevokeReason() {
        return revokeReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
