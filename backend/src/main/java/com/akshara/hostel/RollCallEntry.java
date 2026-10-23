package com.akshara.hostel;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One boarder's mark at one night's roll call. */
@Entity
@Table(schema = "hostel", name = "roll_call_entry")
class RollCallEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID rollCallId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RollCallStatus status;

    private String note;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RollCallEntry() {
    }

    RollCallEntry(UUID rollCallId, UUID studentId, RollCallStatus status, String note, Instant now) {
        this.id = Ids.newId();
        this.rollCallId = rollCallId;
        this.studentId = studentId;
        this.status = status;
        this.note = note;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void mark(RollCallStatus status, String note) {
        this.status = status;
        this.note = note;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getRollCallId() {
        return rollCallId;
    }

    UUID getStudentId() {
        return studentId;
    }

    RollCallStatus getStatus() {
        return status;
    }

    String getNote() {
        return note;
    }
}
