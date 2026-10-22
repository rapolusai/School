package com.akshara.hostel;

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

/** One night's roll call of a hostel, with its counts; the marks are {@link RollCallEntry} rows. */
@Entity
@Table(schema = "hostel", name = "roll_call")
class RollCall extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID hostelId;

    @Column(nullable = false, updatable = false)
    private LocalDate night;

    private String takenByName;

    @Column(nullable = false)
    private Instant takenAt;

    @Column(nullable = false)
    private int present;

    @Column(nullable = false)
    private int onLeave;

    @Column(nullable = false)
    private int absent;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected RollCall() {
    }

    RollCall(UUID hostelId, LocalDate night, String takenByName, Instant now) {
        this.id = Ids.newId();
        this.hostelId = hostelId;
        this.night = night;
        this.takenByName = takenByName;
        this.takenAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void taken(String byName, Instant now, int present, int onLeave, int absent) {
        this.takenByName = byName;
        this.takenAt = now;
        this.present = present;
        this.onLeave = onLeave;
        this.absent = absent;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getHostelId() {
        return hostelId;
    }

    LocalDate getNight() {
        return night;
    }

    String getTakenByName() {
        return takenByName;
    }

    Instant getTakenAt() {
        return takenAt;
    }

    int getPresent() {
        return present;
    }

    int getOnLeave() {
        return onLeave;
    }

    int getAbsent() {
        return absent;
    }
}
