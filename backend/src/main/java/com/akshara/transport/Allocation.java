package com.akshara.transport;

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

/**
 * A student's place on a route and stop from a start date (to an end date when set). ACTIVE until the student leaves
 * transport (ENDED); never deleted, so the history of who travelled when stays.
 */
@Entity
@Table(schema = "transport", name = "allocation")
class Allocation extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private UUID routeId;

    private UUID stopId;

    @Column(nullable = false)
    private String stopName;

    @Column(nullable = false, updatable = false)
    private LocalDate startDate;

    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllocationStatus status;

    private String overCapacityReason;

    private String endReason;

    @Column(updatable = false)
    private UUID createdById;

    @Column(updatable = false)
    private String createdByName;

    private UUID endedById;

    private String endedByName;

    private Instant endedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Allocation() {
    }

    Allocation(UUID studentId, UUID routeId, Stop stop, LocalDate startDate, LocalDate endDate,
            String overCapacityReason, Actor actor) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.routeId = routeId;
        this.stopId = stop.getId();
        this.stopName = stop.getName();
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = AllocationStatus.ACTIVE;
        this.overCapacityReason = overCapacityReason;
        this.createdById = actor.id();
        this.createdByName = actor.name();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void change(Stop stop, LocalDate endDate) {
        this.stopId = stop.getId();
        this.stopName = stop.getName();
        this.endDate = endDate;
    }

    void renameStop(String name) {
        this.stopName = name;
    }

    void end(LocalDate endDate, String reason, Actor actor) {
        this.status = AllocationStatus.ENDED;
        this.endDate = endDate;
        this.endReason = reason;
        this.endedById = actor.id();
        this.endedByName = actor.name();
        this.endedAt = Instant.now();
    }

    TransportRules.Period period() {
        return new TransportRules.Period(startDate, endDate);
    }

    boolean isActive() {
        return status == AllocationStatus.ACTIVE;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getRouteId() {
        return routeId;
    }

    UUID getStopId() {
        return stopId;
    }

    String getStopName() {
        return stopName;
    }

    LocalDate getStartDate() {
        return startDate;
    }

    LocalDate getEndDate() {
        return endDate;
    }

    AllocationStatus getStatus() {
        return status;
    }

    String getOverCapacityReason() {
        return overCapacityReason;
    }

    String getEndReason() {
        return endReason;
    }

    String getCreatedByName() {
        return createdByName;
    }

    String getEndedByName() {
        return endedByName;
    }

    Instant getEndedAt() {
        return endedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
