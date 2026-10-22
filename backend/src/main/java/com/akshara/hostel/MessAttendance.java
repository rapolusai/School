package com.akshara.hostel;

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

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** How many boarders of a hostel ate one meal on one day, as the warden counted. */
@Entity
@Table(schema = "hostel", name = "mess_attendance")
class MessAttendance extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID hostelId;

    @Column(nullable = false, updatable = false)
    private LocalDate day;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Meal meal;

    @Column(nullable = false)
    private int headCount;

    private String markedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected MessAttendance() {
    }

    MessAttendance(UUID hostelId, LocalDate day, Meal meal, int headCount, String markedByName, Instant now) {
        this.id = Ids.newId();
        this.hostelId = hostelId;
        this.day = day;
        this.meal = meal;
        this.headCount = headCount;
        this.markedByName = markedByName;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(int headCount, String markedByName) {
        this.headCount = headCount;
        this.markedByName = markedByName;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getHostelId() {
        return hostelId;
    }

    LocalDate getDay() {
        return day;
    }

    Meal getMeal() {
        return meal;
    }

    int getHeadCount() {
        return headCount;
    }

    String getMarkedByName() {
        return markedByName;
    }
}
