package com.akshara.payroll;

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
 * One line of a payslip: an earning, a deduction or an employer contribution. {@code fullPaise} is the component's
 * full monthly amount before proration (null for calculated and adjusted lines). Replaced as a set on recalculation.
 */
@Entity
@Table(schema = "payroll", name = "payslip_line")
class PayslipLine extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID payslipId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LineKind kind;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(updatable = false)
    private Long fullPaise;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false)
    private int displayOrder;

    protected PayslipLine() {
    }

    PayslipLine(UUID payslipId, PayslipCalculator.Line line, int displayOrder) {
        this.id = Ids.newId();
        this.payslipId = payslipId;
        this.kind = line.kind();
        this.code = line.code();
        this.name = line.name();
        this.fullPaise = line.fullPaise();
        this.amountPaise = line.amountPaise();
        this.displayOrder = displayOrder;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getPayslipId() {
        return payslipId;
    }

    LineKind getKind() {
        return kind;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    Long getFullPaise() {
        return fullPaise;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    int getDisplayOrder() {
        return displayOrder;
    }
}
