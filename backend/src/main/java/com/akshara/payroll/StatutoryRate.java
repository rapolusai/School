package com.akshara.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * PF and ESI rates and limits from a date on. Statutory reference data, the same for every school, added by migrations
 * and read-only for the API (see V14__payroll.sql).
 */
@Entity
@Immutable
@Table(schema = "payroll", name = "statutory_rate")
class StatutoryRate {

    @Id
    private UUID id;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    @Column(nullable = false)
    private BigDecimal pfEmployeePercent;

    @Column(nullable = false)
    private BigDecimal pfEmployerPercent;

    @Column(nullable = false)
    private long pfWageCeilingPaise;

    @Column(nullable = false)
    private BigDecimal epsPercent;

    @Column(nullable = false)
    private long epsMaxPaise;

    @Column(nullable = false)
    private BigDecimal edliPercent;

    @Column(nullable = false)
    private long edliMaxPaise;

    @Column(nullable = false)
    private BigDecimal pfAdminPercent;

    @Column(nullable = false)
    private long pfAdminMinPaise;

    @Column(nullable = false)
    private BigDecimal esiEmployeePercent;

    @Column(nullable = false)
    private BigDecimal esiEmployerPercent;

    @Column(nullable = false)
    private long esiWageCeilingPaise;

    @Column(nullable = false)
    private String note;

    protected StatutoryRate() {
    }

    PayrollMath.PfRates pfRates() {
        return new PayrollMath.PfRates(pfEmployeePercent, pfEmployerPercent, pfWageCeilingPaise, epsPercent,
                epsMaxPaise, edliPercent, edliMaxPaise, pfAdminPercent);
    }

    UUID getId() {
        return id;
    }

    LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    BigDecimal getPfEmployeePercent() {
        return pfEmployeePercent;
    }

    BigDecimal getPfEmployerPercent() {
        return pfEmployerPercent;
    }

    long getPfWageCeilingPaise() {
        return pfWageCeilingPaise;
    }

    BigDecimal getEpsPercent() {
        return epsPercent;
    }

    long getEpsMaxPaise() {
        return epsMaxPaise;
    }

    BigDecimal getEdliPercent() {
        return edliPercent;
    }

    long getEdliMaxPaise() {
        return edliMaxPaise;
    }

    BigDecimal getPfAdminPercent() {
        return pfAdminPercent;
    }

    long getPfAdminMinPaise() {
        return pfAdminMinPaise;
    }

    BigDecimal getEsiEmployeePercent() {
        return esiEmployeePercent;
    }

    BigDecimal getEsiEmployerPercent() {
        return esiEmployerPercent;
    }

    long getEsiWageCeilingPaise() {
        return esiWageCeilingPaise;
    }

    String getNote() {
        return note;
    }
}
