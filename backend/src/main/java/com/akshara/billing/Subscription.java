package com.akshara.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.akshara.platform.TenantStatus;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A school's billing account: its billing details, its paid subscription (cycle, billed students, current period) once
 * it pays, and why it is suspended when it is. Platform data like the school row itself, so it carries the school's id
 * without being row-level secured; the plan stays on the school.
 */
@Entity
@Table(schema = "billing", name = "subscription")
class Subscription extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    private BillingCycle billingCycle;

    private Integer billedStudents;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    private LocalDate paidSince;

    private String legalName;

    private String billingAddress;

    private String stateCode;

    private String gstin;

    private Instant suspendedAt;

    private String suspensionReason;

    @Enumerated(EnumType.STRING)
    private TenantStatus statusBeforeSuspension;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Subscription() {
    }

    Subscription(UUID tenantId) {
        this.id = Ids.newId();
        this.tenantId = tenantId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    boolean isPaid() {
        return billingCycle != null;
    }

    void start(BillingCycle cycle, int students, LocalDate start) {
        this.billingCycle = cycle;
        this.billedStudents = students;
        this.periodStart = start;
        this.periodEnd = cycle.periodEnd(start);
        this.paidSince = start;
    }

    void change(BillingCycle cycle, int students) {
        this.billingCycle = cycle;
        this.billedStudents = students;
    }

    /** Moves to the next period, of the current cycle's length, starting the day after this one ends. */
    void renew() {
        this.periodStart = periodEnd.plusDays(1);
        this.periodEnd = billingCycle.periodEnd(periodStart);
    }

    /**
     * Fits the current period to the current cycle again, keeping its first day. Used when the period's invoice was
     * cancelled and is issued again, perhaps after the cycle was changed.
     */
    void refitPeriod() {
        this.periodEnd = billingCycle.periodEnd(periodStart);
    }

    void updateDetails(String legalName, String billingAddress, String stateCode, String gstin) {
        this.legalName = legalName;
        this.billingAddress = billingAddress;
        this.stateCode = stateCode;
        this.gstin = gstin;
    }

    void suspend(TenantStatus from, String reason) {
        this.statusBeforeSuspension = from;
        this.suspensionReason = reason;
        this.suspendedAt = Instant.now();
    }

    /** Clears the suspension and returns the status the school had before it. */
    TenantStatus lift() {
        TenantStatus before = statusBeforeSuspension;
        this.statusBeforeSuspension = null;
        this.suspensionReason = null;
        this.suspendedAt = null;
        return before;
    }

    /** The day the next period starts, or null before the school pays. */
    LocalDate nextRenewalOn() {
        return periodEnd == null ? null : periodEnd.plusDays(1);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    BillingCycle getBillingCycle() {
        return billingCycle;
    }

    Integer getBilledStudents() {
        return billedStudents;
    }

    LocalDate getPeriodStart() {
        return periodStart;
    }

    LocalDate getPeriodEnd() {
        return periodEnd;
    }

    LocalDate getPaidSince() {
        return paidSince;
    }

    String getLegalName() {
        return legalName;
    }

    String getBillingAddress() {
        return billingAddress;
    }

    String getStateCode() {
        return stateCode;
    }

    String getGstin() {
        return gstin;
    }

    Instant getSuspendedAt() {
        return suspendedAt;
    }

    String getSuspensionReason() {
        return suspensionReason;
    }

    TenantStatus getStatusBeforeSuspension() {
        return statusBeforeSuspension;
    }
}
