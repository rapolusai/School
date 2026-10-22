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
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A boarder's stay in one bed. Rows are never deleted: a student's allotments are their room history. */
@Entity
@Table(schema = "hostel", name = "allotment")
class Allotment extends AssignedIdEntity {

    /** The vacate reason recorded on the old bed when a boarder changes room. */
    static final String ROOM_CHANGE = "ROOM_CHANGE";

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private UUID hostelId;

    @Column(nullable = false, updatable = false)
    private UUID roomId;

    @Column(nullable = false, updatable = false)
    private UUID bedId;

    @Column(nullable = false, updatable = false)
    private LocalDate fromDate;

    private LocalDate toDate;

    @Column(nullable = false)
    private boolean guardianConsent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllotmentStatus status;

    private String vacateReason;

    @Column(updatable = false)
    private String allottedByName;

    private String vacatedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Allotment() {
    }

    Allotment(UUID studentId, UUID hostelId, UUID roomId, UUID bedId, LocalDate fromDate, boolean guardianConsent,
            Actor by, Instant now) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.hostelId = hostelId;
        this.roomId = roomId;
        this.bedId = bedId;
        this.fromDate = fromDate;
        this.guardianConsent = guardianConsent;
        this.status = AllotmentStatus.ACTIVE;
        this.allottedByName = by.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void vacate(LocalDate toDate, String reason, Actor by) {
        this.status = AllotmentStatus.VACATED;
        this.toDate = toDate;
        this.vacateReason = reason;
        this.vacatedByName = by.name();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getHostelId() {
        return hostelId;
    }

    UUID getRoomId() {
        return roomId;
    }

    UUID getBedId() {
        return bedId;
    }

    LocalDate getFromDate() {
        return fromDate;
    }

    LocalDate getToDate() {
        return toDate;
    }

    boolean isGuardianConsent() {
        return guardianConsent;
    }

    AllotmentStatus getStatus() {
        return status;
    }

    String getVacateReason() {
        return vacateReason;
    }

    String getAllottedByName() {
        return allottedByName;
    }

    String getVacatedByName() {
        return vacatedByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
