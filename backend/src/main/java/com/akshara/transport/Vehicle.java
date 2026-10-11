package com.akshara.transport;

import java.math.BigDecimal;
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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A school vehicle with its document expiry dates and, when a GPS device is fitted, the hash of the device's token.
 * The latest position is written by {@link VehicleRepository#recordPosition} only (it changes every few seconds and
 * must not conflict with edits), so it is read-only here.
 */
@Entity
@Table(schema = "transport", name = "vehicle")
class Vehicle extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String registrationNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleType vehicleType;

    @Column(nullable = false)
    private int seats;

    private String gpsDeviceId;

    private LocalDate insuranceExpiry;

    private LocalDate fitnessExpiry;

    private LocalDate permitExpiry;

    private LocalDate pollutionExpiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleStatus status;

    private String deviceTokenHash;

    private String deviceTokenHint;

    private Instant deviceTokenIssuedAt;

    @Column(insertable = false, updatable = false)
    private BigDecimal lastLatitude;

    @Column(insertable = false, updatable = false)
    private BigDecimal lastLongitude;

    @Column(insertable = false, updatable = false)
    private Instant lastPositionAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Vehicle() {
    }

    /** The details a school edits. */
    record Details(String registrationNo, VehicleType type, int seats, String gpsDeviceId, LocalDate insuranceExpiry,
            LocalDate fitnessExpiry, LocalDate permitExpiry, LocalDate pollutionExpiry, VehicleStatus status) {
    }

    Vehicle(Details details) {
        this.id = Ids.newId();
        update(details);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(Details d) {
        this.registrationNo = d.registrationNo();
        this.vehicleType = d.type();
        this.seats = d.seats();
        this.gpsDeviceId = d.gpsDeviceId();
        this.insuranceExpiry = d.insuranceExpiry();
        this.fitnessExpiry = d.fitnessExpiry();
        this.permitExpiry = d.permitExpiry();
        this.pollutionExpiry = d.pollutionExpiry();
        this.status = d.status();
    }

    void issueDeviceToken(String hash, String hint, Instant at) {
        this.deviceTokenHash = hash;
        this.deviceTokenHint = hint;
        this.deviceTokenIssuedAt = at;
    }

    void revokeDeviceToken() {
        this.deviceTokenHash = null;
        this.deviceTokenHint = null;
        this.deviceTokenIssuedAt = null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getRegistrationNo() {
        return registrationNo;
    }

    VehicleType getVehicleType() {
        return vehicleType;
    }

    int getSeats() {
        return seats;
    }

    String getGpsDeviceId() {
        return gpsDeviceId;
    }

    LocalDate getInsuranceExpiry() {
        return insuranceExpiry;
    }

    LocalDate getFitnessExpiry() {
        return fitnessExpiry;
    }

    LocalDate getPermitExpiry() {
        return permitExpiry;
    }

    LocalDate getPollutionExpiry() {
        return pollutionExpiry;
    }

    VehicleStatus getStatus() {
        return status;
    }

    boolean hasDeviceToken() {
        return deviceTokenHash != null;
    }

    String getDeviceTokenHint() {
        return deviceTokenHint;
    }

    Instant getDeviceTokenIssuedAt() {
        return deviceTokenIssuedAt;
    }

    BigDecimal getLastLatitude() {
        return lastLatitude;
    }

    BigDecimal getLastLongitude() {
        return lastLongitude;
    }

    Instant getLastPositionAt() {
        return lastPositionAt;
    }
}
