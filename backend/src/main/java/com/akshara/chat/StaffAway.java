package com.akshara.chat;

import java.time.Instant;
import java.time.LocalDate;
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

/** A member of staff who is away until {@code returnsOn}, with a note parents see in their conversations. */
@Entity
@Table(schema = "chat", name = "staff_away")
class StaffAway extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private LocalDate returnsOn;

    private String note;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StaffAway() {
    }

    StaffAway(UUID userId, LocalDate returnsOn, String note, Instant at) {
        this.id = Ids.newId();
        this.userId = userId;
        this.returnsOn = returnsOn;
        this.note = note;
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void change(LocalDate returnsOn, String note) {
        this.returnsOn = returnsOn;
        this.note = note;
    }

    /** Away on {@code today}: they come back on a later day. */
    boolean awayOn(LocalDate today) {
        return returnsOn.isAfter(today);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    LocalDate getReturnsOn() {
        return returnsOn;
    }

    String getNote() {
        return note;
    }
}
