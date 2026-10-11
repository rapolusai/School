package com.akshara.transport;

import java.math.BigDecimal;
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

/** A stop of a route, in pickup order ({@code seq} 1 is the first pickup in the morning). */
@Entity
@Table(schema = "transport", name = "stop")
class Stop extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID routeId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private String name;

    private String landmark;

    private LocalTime pickupTime;

    private LocalTime dropTime;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private UUID feeZoneId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Stop() {
    }

    record Details(int seq, String name, String landmark, LocalTime pickupTime, LocalTime dropTime,
            BigDecimal latitude, BigDecimal longitude, UUID feeZoneId) {
    }

    Stop(UUID routeId, Details details) {
        this.id = Ids.newId();
        this.routeId = routeId;
        update(details);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(Details d) {
        this.seq = d.seq();
        this.name = d.name();
        this.landmark = d.landmark();
        this.pickupTime = d.pickupTime();
        this.dropTime = d.dropTime();
        this.latitude = d.latitude();
        this.longitude = d.longitude();
        this.feeZoneId = d.feeZoneId();
    }

    /** The stop as the route arithmetic sees it. */
    RouteMath.StopPoint point() {
        return new RouteMath.StopPoint(latitude == null ? null : latitude.doubleValue(),
                longitude == null ? null : longitude.doubleValue());
    }

    /** The time the bus is due at this stop on a trip of this kind. */
    LocalTime timeFor(TripKind kind) {
        return kind == TripKind.MORNING ? pickupTime : dropTime;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getRouteId() {
        return routeId;
    }

    int getSeq() {
        return seq;
    }

    String getName() {
        return name;
    }

    String getLandmark() {
        return landmark;
    }

    LocalTime getPickupTime() {
        return pickupTime;
    }

    LocalTime getDropTime() {
        return dropTime;
    }

    BigDecimal getLatitude() {
        return latitude;
    }

    BigDecimal getLongitude() {
        return longitude;
    }

    UUID getFeeZoneId() {
        return feeZoneId;
    }
}
