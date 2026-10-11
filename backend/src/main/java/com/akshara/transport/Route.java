package com.akshara.transport;

import java.time.Instant;
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

/** A bus route: its vehicle, driver and attendant, which trips it runs, and (in {@link Stop}) its stops. */
@Entity
@Table(schema = "transport", name = "route")
class Route extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private UUID vehicleId;

    private UUID driverId;

    private UUID attendantId;

    @Column(nullable = false)
    private boolean runsMorning;

    @Column(nullable = false)
    private boolean runsAfternoon;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Route() {
    }

    record Details(String name, UUID vehicleId, UUID driverId, UUID attendantId, boolean runsMorning,
            boolean runsAfternoon, boolean active) {
    }

    Route(Details details) {
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
        this.name = d.name();
        this.vehicleId = d.vehicleId();
        this.driverId = d.driverId();
        this.attendantId = d.attendantId();
        this.runsMorning = d.runsMorning();
        this.runsAfternoon = d.runsAfternoon();
        this.active = d.active();
    }

    boolean runs(TripKind kind) {
        return kind == TripKind.MORNING ? runsMorning : runsAfternoon;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    UUID getVehicleId() {
        return vehicleId;
    }

    UUID getDriverId() {
        return driverId;
    }

    UUID getAttendantId() {
        return attendantId;
    }

    boolean isRunsMorning() {
        return runsMorning;
    }

    boolean isRunsAfternoon() {
        return runsAfternoon;
    }

    boolean isActive() {
        return active;
    }
}
