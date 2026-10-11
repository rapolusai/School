package com.akshara.chat;

import java.time.Instant;
import java.time.LocalTime;
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

/** One meeting slot of a teacher at a PTM. Blocked slots cannot be booked. */
@Entity
@Table(schema = "chat", name = "ptm_slot")
class PtmSlot extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false)
    private UUID ptmTeacherId;

    @Column(nullable = false, updatable = false)
    private UUID teacherUserId;

    @Column(nullable = false, updatable = false)
    private LocalTime startsAt;

    @Column(nullable = false, updatable = false)
    private LocalTime endsAt;

    @Column(nullable = false)
    private boolean blocked;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PtmSlot() {
    }

    PtmSlot(UUID eventId, UUID ptmTeacherId, UUID teacherUserId, LocalTime startsAt, LocalTime endsAt, Instant at) {
        this.id = Ids.newId();
        this.eventId = eventId;
        this.ptmTeacherId = ptmTeacherId;
        this.teacherUserId = teacherUserId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void block(boolean blocked) {
        this.blocked = blocked;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getEventId() {
        return eventId;
    }

    UUID getPtmTeacherId() {
        return ptmTeacherId;
    }

    UUID getTeacherUserId() {
        return teacherUserId;
    }

    LocalTime getStartsAt() {
        return startsAt;
    }

    LocalTime getEndsAt() {
        return endsAt;
    }

    boolean isBlocked() {
        return blocked;
    }
}
