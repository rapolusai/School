package com.akshara.accounts;

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

/** A head's budget for a financial year, with its 12 months in {@link BudgetMonth}. */
@Entity
@Table(schema = "accounts", name = "budget")
class Budget extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String financialYear;

    @Column(nullable = false, updatable = false)
    private UUID headId;

    @Column(nullable = false)
    private long annualPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BudgetSpread spread;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Budget() {
    }

    Budget(String financialYear, UUID headId, long annualPaise, BudgetSpread spread) {
        this.id = Ids.newId();
        this.financialYear = financialYear;
        this.headId = headId;
        this.annualPaise = annualPaise;
        this.spread = spread;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void update(long annualPaise, BudgetSpread spread) {
        this.annualPaise = annualPaise;
        this.spread = spread;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getFinancialYear() {
        return financialYear;
    }

    UUID getHeadId() {
        return headId;
    }

    long getAnnualPaise() {
        return annualPaise;
    }

    BudgetSpread getSpread() {
        return spread;
    }
}
