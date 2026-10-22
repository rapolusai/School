package com.akshara.accounts;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One month of a budget: 1 is April, 12 is March. */
@Entity
@Table(schema = "accounts", name = "budget_month")
class BudgetMonth extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID budgetId;

    @Column(nullable = false, updatable = false)
    private int monthIndex;

    @Column(nullable = false)
    private long amountPaise;

    protected BudgetMonth() {
    }

    BudgetMonth(UUID budgetId, int monthIndex, long amountPaise) {
        this.id = Ids.newId();
        this.budgetId = budgetId;
        this.monthIndex = monthIndex;
        this.amountPaise = amountPaise;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getBudgetId() {
        return budgetId;
    }

    int getMonthIndex() {
        return monthIndex;
    }

    long getAmountPaise() {
        return amountPaise;
    }
}
