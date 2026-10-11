package com.akshara.transport;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * One run of a route on a day. Started from the attendant's phone; keeps the latest position, the furthest stop
 * reached and the latest delay reported. Changed only under a row lock ({@link TripRepository#lockById}), since the
 * phone and the vehicle's GPS device may both post at once.
 */
@Entity
@Table(schema = "transport", name = "trip")
class Trip extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID routeId;

    @Column(nullable = false, updatable = false)
    private LocalDate tripDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TripKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripStatus status;

    @Column(updatable = false)
    private UUID vehicleId;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    @Column(updatable = false)
    private UUID startedById;

    @Column(updatable = false)
    private String startedByName;

    private Instant endedAt;

    private String endedByName;

    private UUID reachedStopId;

    private BigDecimal lastLatitude;

    private BigDecimal lastLongitude;

    @Column(name = "last_accuracy_m")
    private Integer lastAccuracyM;

    private Instant lastPositionAt;

    @Enumerated(EnumType.STRING)
    private PositionSource lastPositionSource;

    private Integer delayMinutes;

    private String delayReason;

    private Instant delayedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Trip() {
    }

    Trip(UUID routeId, LocalDate tripDate, TripKind kind, UUID vehicleId, Actor actor, Instant at) {
        this.id = Ids.newId();
        this.routeId = routeId;
        this.tripDate = tripDate;
        this.kind = kind;
        this.status = TripStatus.RUNNING;
        this.vehicleId = vehicleId;
        this.startedAt = at;
        this.startedById = actor.id();
        this.startedByName = actor.name();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Keeps a position as the latest one, unless a newer one is already kept. */
    void position(double latitude, double longitude, Integer accuracyM, Instant at, PositionSource source) {
        if (lastPositionAt != null && at.isBefore(lastPositionAt)) {
            return;
        }
        this.lastLatitude = coordinate(latitude);
        this.lastLongitude = coordinate(longitude);
        this.lastAccuracyM = accuracyM;
        this.lastPositionAt = at;
        this.lastPositionSource = source;
    }

    void reach(UUID stopId) {
        this.reachedStopId = stopId;
    }

    void delay(int minutes, String reason, Instant at) {
        this.delayMinutes = minutes;
        this.delayReason = reason;
        this.delayedAt = at;
    }

    void end(Actor actor, Instant at) {
        this.status = TripStatus.COMPLETED;
        this.endedAt = at;
        this.endedByName = actor.name();
    }

    boolean isRunning() {
        return status == TripStatus.RUNNING;
    }

    static BigDecimal coordinate(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getRouteId() {
        return routeId;
    }

    LocalDate getTripDate() {
        return tripDate;
    }

    TripKind getKind() {
        return kind;
    }

    TripStatus getStatus() {
        return status;
    }

    UUID getVehicleId() {
        return vehicleId;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    String getStartedByName() {
        return startedByName;
    }

    Instant getEndedAt() {
        return endedAt;
    }

    String getEndedByName() {
        return endedByName;
    }

    UUID getReachedStopId() {
        return reachedStopId;
    }

    BigDecimal getLastLatitude() {
        return lastLatitude;
    }

    BigDecimal getLastLongitude() {
        return lastLongitude;
    }

    Integer getLastAccuracyM() {
        return lastAccuracyM;
    }

    Instant getLastPositionAt() {
        return lastPositionAt;
    }

    PositionSource getLastPositionSource() {
        return lastPositionSource;
    }

    Integer getDelayMinutes() {
        return delayMinutes;
    }

    String getDelayReason() {
        return delayReason;
    }

    Instant getDelayedAt() {
        return delayedAt;
    }
}
