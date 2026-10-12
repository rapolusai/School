package com.akshara.accounts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** The balance of a bank account (or of cash in hand when bankAccountId is null) on 1 April of a financial year. */
@Entity
@Table(schema = "accounts", name = "opening_balance")
class OpeningBalance extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String financialYear;

    @Column(updatable = false)
    private UUID bankAccountId;

    @Column(nullable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OpeningBalance() {
    }

    OpeningBalance(String financialYear, UUID bankAccountId, long amountPaise) {
        this.id = Ids.newId();
        this.financialYear = financialYear;
        this.bankAccountId = bankAccountId;
        this.amountPaise = amountPaise;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void setAmountPaise(long amountPaise) {
        this.amountPaise = amountPaise;
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

    UUID getBankAccountId() {
        return bankAccountId;
    }

    long getAmountPaise() {
        return amountPaise;
    }
}
