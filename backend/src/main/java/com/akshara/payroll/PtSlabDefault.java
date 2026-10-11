package com.akshara.payroll;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/** A published professional tax slab of a state. Statutory reference data, read-only for the API. */
@Entity
@Immutable
@Table(schema = "payroll", name = "pt_slab_default")
class PtSlabDefault implements PtSlabRow {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String stateCode;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PtAppliesTo appliesTo;

    @Column(nullable = false)
    private long fromPaise;

    private Long toPaise;

    @Column(nullable = false)
    private long amountPaise;

    private String months;

    private String note;

    protected PtSlabDefault() {
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
