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

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One dish of the weekly menu: a hostel, a weekday (1 = Monday) and a meal. */
@Entity
@Table(schema = "hostel", name = "menu_item")
class MenuItem extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID hostelId;

    @Column(nullable = false, updatable = false)
    private int dayOfWeek;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Meal meal;

    @Column(nullable = false)
    private String dish;

    @Column(nullable = false)
    private boolean veg;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected MenuItem() {
    }

    MenuItem(UUID hostelId, int dayOfWeek, Meal meal, String dish, boolean veg, Instant now) {
        this.id = Ids.newId();
        this.hostelId = hostelId;
        this.dayOfWeek = dayOfWeek;
        this.meal = meal;
        this.dish = dish;
        this.veg = veg;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String dish, boolean veg) {
        this.dish = dish;
        this.veg = veg;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getHostelId() {
        return hostelId;
    }

    int getDayOfWeek() {
        return dayOfWeek;
    }

    Meal getMeal() {
        return meal;
    }

    String getDish() {
        return dish;
    }

    boolean isVeg() {
        return veg;
    }
}
