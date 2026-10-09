package com.akshara.attendance;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One student's mark in a register. Saving the register again keeps only the latest mark. */
@Entity
@Table(schema = "attendance", name = "entry")
class AttendanceEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID registerId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AttendanceEntry() {
    }

    AttendanceEntry(UUID registerId, UUID studentId, AttendanceStatus status, Instant at) {
        this.id = Ids.newId();
        this.registerId = registerId;
        this.studentId = studentId;
        this.status = status;
        this.createdAt = at;
        this.updatedAt = at;
    }

    /** Returns true when the mark changed. */
    boolean change(AttendanceStatus newStatus, Instant at) {
        if (status == newStatus) {
            return false;
        }
        status = newStatus;
        updatedAt = at;
        return true;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getRegisterId() {
        return registerId;
    }

    UUID getStudentId() {
        return studentId;
    }

    AttendanceStatus getStatus() {
        return status;
    }
}
