package com.akshara.payroll;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * Income tax under the new regime for one financial year: standard deduction, the section 87A rebate and the cess.
 * Its slabs are {@link TaxSlab} rows. Statutory reference data, read-only for the API.
 */
@Entity
@Immutable
@Table(schema = "payroll", name = "tax_rule")
class TaxRule {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String financialYear;

    @Column(nullable = false)
    private LocalDate startsOn;

    @Column(nullable = false)
    private long standardDeductionPaise;

    @Column(nullable = false)
    private long rebateIncomeLimitPaise;

    @Column(nullable = false)
    private long rebateMaxPaise;

    @Column(nullable = false)
    private java.math.BigDecimal cessPercent;

    @Column(nullable = false)
    private String note;

    protected TaxRule() {
    }

    UUID getId() {
        return id;
    }

    String getFinancialYear() {
        return financialYear;
    }

    LocalDate getStartsOn() {
        return startsOn;
    }

    long getStandardDeductionPaise() {
        return standardDeductionPaise;
    }

    long getRebateIncomeLimitPaise() {
        return rebateIncomeLimitPaise;
    }

    long getRebateMaxPaise() {
        return rebateMaxPaise;
    }

    java.math.BigDecimal getCessPercent() {
        return cessPercent;
    }

    String getNote() {
        return note;
    }
}
