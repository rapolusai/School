package com.akshara.payroll;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/** One income tax slab of a {@link TaxRule}. Statutory reference data, read-only for the API. */
@Entity
@Immutable
@Table(schema = "payroll", name = "tax_slab")
class TaxSlab {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID taxRuleId;

    @Column(nullable = false)
    private long fromPaise;

    private Long toPaise;

    @Column(nullable = false)
    private BigDecimal ratePercent;

    protected TaxSlab() {
    }

    UUID getTaxRuleId() {
        return taxRuleId;
    }

    long getFromPaise() {
        return fromPaise;
    }

    Long getToPaise() {
        return toPaise;
    }

    BigDecimal getRatePercent() {
        return ratePercent;
    }
}
