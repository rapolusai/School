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

/** A reminder requested for a student's overdue fees. Delivery belongs to the communication module. */
@Entity
@Immutable
@Table(schema = "fees", name = "fee_reminder")
class FeeReminder extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID studentId;

    @Column(nullable = false)
    private long overduePaise;

    @Column(nullable = false)
    private int daysOverdue;

    private UUID requestedBy;

    @Column(nullable = false)
    private String requestedByName;

    @Column(nullable = false)
    private Instant createdAt;

    protected FeeReminder() {
    }

    FeeReminder(UUID studentId, long overduePaise, int daysOverdue, UUID requestedBy, String requestedByName) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.overduePaise = overduePaise;
        this.daysOverdue = daysOverdue;
        this.requestedBy = requestedBy;
        this.requestedByName = requestedByName;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
