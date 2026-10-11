package com.akshara.transport;

import java.math.BigDecimal;
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

/** A distance slab with its annual bus fee. Each stop belongs to one zone (or none, for no fee). */
@Entity
@Table(schema = "transport", name = "fee_zone")
class FeeZone extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal minKm;

    private BigDecimal maxKm;

    @Column(nullable = false)
    private long annualFeePaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected FeeZone() {
    }

    FeeZone(String name, BigDecimal minKm, BigDecimal maxKm, long annualFeePaise) {
        this.id = Ids.newId();
        update(name, minKm, maxKm, annualFeePaise);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, BigDecimal minKm, BigDecimal maxKm, long annualFeePaise) {
        this.name = name;
        this.minKm = minKm;
        this.maxKm = maxKm;
        this.annualFeePaise = annualFeePaise;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    BigDecimal getMinKm() {
        return minKm;
    }

    BigDecimal getMaxKm() {
        return maxKm;
    }

    long getAnnualFeePaise() {
        return annualFeePaise;
    }
}
