package com.akshara.accounts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** Fee receipts of one day as the accounts module counted them (from FeePaymentReceived or a rebuild). */
@Entity
@Table(schema = "accounts", name = "fee_income_day")
class FeeIncomeDay extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private LocalDate day;

    @Column(nullable = false)
    private long amountPaise;

    @Column(nullable = false)
    private long cashPaise;

    @Column(nullable = false)
    private int receiptCount;

    @Column(nullable = false)
    private Instant updatedAt;

    protected FeeIncomeDay() {
    }

    FeeIncomeDay(LocalDate day) {
        this.id = Ids.newId();
        this.day = day;
        this.updatedAt = Instant.now();
    }

    void add(long paise, boolean cash) {
        this.amountPaise += paise;
        if (cash) {
            this.cashPaise += paise;
        }
        this.receiptCount++;
        this.updatedAt = Instant.now();
    }

    void set(long paise, long cash, int count) {
        this.amountPaise = paise;
        this.cashPaise = cash;
        this.receiptCount = count;
        this.updatedAt = Instant.now();
    }

    long getCashPaise() {
        return cashPaise;
    }

    @Override
    public UUID getId() {
        return id;
    }

    LocalDate getDay() {
        return day;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    int getReceiptCount() {
        return receiptCount;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
