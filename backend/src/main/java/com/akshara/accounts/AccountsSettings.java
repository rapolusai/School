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

/** The school's accounts settings: budget mode, 80G registration for donations and the petty cash float. */
@Entity
@Table(schema = "accounts", name = "settings")
class AccountsSettings extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BudgetMode budgetMode = BudgetMode.WARN;

    @Column(name = "donation_80g_registered", nullable = false)
    private boolean donation80gRegistered;

    @Column(name = "donation_80g_number")
    private String donation80gNumber;

    @Column(nullable = false)
    private long pettyCashFloatPaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AccountsSettings() {
    }

    AccountsSettings(BudgetMode mode, boolean registered, String number, long floatPaise) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(mode, registered, number, floatPaise);
    }

    void update(BudgetMode mode, boolean registered, String number, long floatPaise) {
        this.budgetMode = mode;
        this.donation80gRegistered = registered;
        this.donation80gNumber = registered ? number : null;
        this.pettyCashFloatPaise = floatPaise;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    BudgetMode getBudgetMode() {
        return budgetMode;
    }

    boolean isDonation80gRegistered() {
        return donation80gRegistered;
    }

    String getDonation80gNumber() {
        return donation80gNumber;
    }

    long getPettyCashFloatPaise() {
        return pettyCashFloatPaise;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
