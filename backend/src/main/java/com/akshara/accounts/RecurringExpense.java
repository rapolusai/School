package com.akshara.accounts;

import java.time.Instant;
import java.time.LocalDate;
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

/** A monthly expense (electricity, rent, internet) that is generated as a draft each month on request. */
@Entity
@Table(schema = "accounts", name = "recurring_expense")
class RecurringExpense extends AssignedIdEntity {

    record Fields(String name, UUID headId, UUID costCentreId, UUID vendorId, long amountPaise, long gstPaise,
            int dayOfMonth, String description, boolean active) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private UUID headId;

    private UUID costCentreId;

    private UUID vendorId;

    @Column(nullable = false)
    private long amountPaise;

    @Column(nullable = false)
    private long gstPaise;

    @Column(nullable = false)
    private int dayOfMonth;

    private String description;

    @Column(nullable = false)
    private boolean active = true;

    private LocalDate lastGeneratedMonth;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected RecurringExpense() {
    }

    RecurringExpense(Fields f) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(f);
    }

    void update(Fields f) {
        this.name = f.name();
        this.headId = f.headId();
        this.costCentreId = f.costCentreId();
        this.vendorId = f.vendorId();
        this.amountPaise = f.amountPaise();
        this.gstPaise = f.gstPaise();
        this.dayOfMonth = f.dayOfMonth();
        this.description = f.description();
        this.active = f.active();
    }

    void generated(LocalDate month) {
        if (lastGeneratedMonth == null || month.isAfter(lastGeneratedMonth)) {
            lastGeneratedMonth = month;
        }
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    UUID getHeadId() {
        return headId;
    }

    UUID getCostCentreId() {
        return costCentreId;
    }

    UUID getVendorId() {
        return vendorId;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    long getGstPaise() {
        return gstPaise;
    }

    int getDayOfMonth() {
        return dayOfMonth;
    }

    String getDescription() {
        return description;
    }

    boolean isActive() {
        return active;
    }

    LocalDate getLastGeneratedMonth() {
        return lastGeneratedMonth;
    }
}
