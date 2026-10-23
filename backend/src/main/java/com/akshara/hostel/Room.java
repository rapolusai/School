package com.akshara.hostel;

import java.time.Instant;
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

/** A room in a block: its number, floor, capacity, type and monthly fee (paise). */
@Entity
@Table(schema = "hostel", name = "room")
class Room extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID hostelId;

    @Column(nullable = false, updatable = false)
    private UUID blockId;

    @Column(nullable = false)
    private int floor;

    @Column(nullable = false)
    private String number;

    @Column(nullable = false)
    private int capacity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomType type;

    @Column(nullable = false)
    private long monthlyFeePaise;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Room() {
    }

    Room(UUID hostelId, UUID blockId, int floor, String number, int capacity, RoomType type, long monthlyFeePaise,
            Instant now) {
        this.id = Ids.newId();
        this.hostelId = hostelId;
        this.blockId = blockId;
        this.floor = floor;
        this.number = number;
        this.capacity = capacity;
        this.type = type;
        this.monthlyFeePaise = monthlyFeePaise;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(int floor, String number, int capacity, RoomType type, long monthlyFeePaise, boolean active) {
        this.floor = floor;
        this.number = number;
        this.capacity = capacity;
        this.type = type;
        this.monthlyFeePaise = monthlyFeePaise;
        this.active = active;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getHostelId() {
        return hostelId;
    }

    UUID getBlockId() {
        return blockId;
    }

    int getFloor() {
        return floor;
    }

    String getNumber() {
        return number;
    }

    int getCapacity() {
        return capacity;
    }

    RoomType getType() {
        return type;
    }

    long getMonthlyFeePaise() {
        return monthlyFeePaise;
    }

    boolean isActive() {
        return active;
    }
}
