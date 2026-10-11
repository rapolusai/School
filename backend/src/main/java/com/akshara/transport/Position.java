package com.akshara.transport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One position received while a trip ran: the trip log. Never changed or deleted. */
@Entity
@Immutable
@Table(schema = "transport", name = "position")
class Position extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID tripId;

    private UUID vehicleId;

    @Column(nullable = false)
    private Instant recordedAt;

    @Column(nullable = false)
    private Instant receivedAt;

    @Column(nullable = false)
    private BigDecimal latitude;

    @Column(nullable = false)
    private BigDecimal longitude;

    @Column(name = "accuracy_m")
    private Integer accuracyM;

    private BigDecimal speedKmh;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PositionSource source;

    protected Position() {
    }

    Position(UUID tripId, UUID vehicleId, Instant recordedAt, Instant receivedAt, double latitude, double longitude,
            Integer accuracyM, Double speedKmh, PositionSource source) {
        this.id = Ids.newId();
        this.tripId = tripId;
        this.vehicleId = vehicleId;
        this.recordedAt = recordedAt;
        this.receivedAt = receivedAt;
        this.latitude = Trip.coordinate(latitude);
        this.longitude = Trip.coordinate(longitude);
        this.accuracyM = accuracyM;
        this.speedKmh = speedKmh == null ? null : BigDecimal.valueOf(speedKmh).setScale(1, RoundingMode.HALF_UP);
        this.source = source;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTripId() {
        return tripId;
    }

    Instant getRecordedAt() {
        return recordedAt;
    }

    BigDecimal getLatitude() {
        return latitude;
    }

    BigDecimal getLongitude() {
        return longitude;
    }

    PositionSource getSource() {
        return source;
    }
}
