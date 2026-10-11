package com.akshara.payroll;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A school's own professional tax slab for a state. When a school has slabs for a state they replace the defaults for
 * it; the set is saved as a whole (old rows deleted, new ones inserted), so a row never changes.
 */
@Entity
@Table(schema = "payroll", name = "pt_slab")
class PtSlab extends AssignedIdEntity implements PtSlabRow {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String stateCode;

    @Column(nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PtAppliesTo appliesTo;

    @Column(nullable = false, updatable = false)
    private long fromPaise;

    @Column(updatable = false)
    private Long toPaise;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(updatable = false)
    private String months;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PtSlab() {
    }

    PtSlab(String stateCode, LocalDate effectiveFrom, PtAppliesTo appliesTo, long fromPaise, Long toPaise,
            long amountPaise, String months) {
        this.id = Ids.newId();
        this.stateCode = stateCode;
        this.effectiveFrom = effectiveFrom;
        this.appliesTo = appliesTo;
        this.fromPaise = fromPaise;
        this.toPaise = toPaise;
        this.amountPaise = amountPaise;
        this.months = months;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public String getStateCode() {
        return stateCode;
    }

    @Override
    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    @Override
    public PtAppliesTo getAppliesTo() {
        return appliesTo;
    }

    @Override
    public long getFromPaise() {
        return fromPaise;
    }

    @Override
    public Long getToPaise() {
        return toPaise;
    }

    @Override
    public long getAmountPaise() {
        return amountPaise;
    }

    @Override
    public String getMonths() {
        return months;
    }
}
