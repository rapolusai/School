package com.akshara.payroll;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * An earning (Basic, DA, HRA, ...) or a fixed monthly deduction (loan recovery, other) that salary structures are made
 * of. The flags say whether it counts towards PF and ESI wages, is taxable and is prorated. PF, ESI, professional tax
 * and TDS are calculated, not components. A component in use is switched off rather than deleted.
 */
@Entity
@Table(schema = "payroll", name = "salary_component")
class SalaryComponent extends AssignedIdEntity {

    /** The editable part, already cleaned. */
    record Fields(String name, boolean pfWage, boolean esiWage, boolean taxable, boolean prorated, int displayOrder,
            boolean active) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ComponentKind kind;

    @Column(nullable = false)
    private boolean pfWage;

    @Column(nullable = false)
    private boolean esiWage;

    @Column(nullable = false)
    private boolean taxable;

    @Column(nullable = false)
    private boolean prorated;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SalaryComponent() {
    }

    SalaryComponent(String code, ComponentKind kind, Fields fields) {
        this.id = Ids.newId();
        this.code = code;
        this.kind = kind;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        apply(fields);
    }

    void apply(Fields f) {
        this.name = f.name();
        // Only earnings count towards wages or tax; a deduction is a fixed amount.
        boolean earning = kind == ComponentKind.EARNING;
        this.pfWage = earning && f.pfWage();
        this.esiWage = earning && f.esiWage();
        this.taxable = earning && f.taxable();
        this.prorated = earning && f.prorated();
        this.displayOrder = f.displayOrder();
        this.active = f.active();
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    ComponentKind getKind() {
        return kind;
    }

    boolean isPfWage() {
        return pfWage;
    }

    boolean isEsiWage() {
        return esiWage;
    }

    boolean isTaxable() {
        return taxable;
    }

    boolean isProrated() {
        return prorated;
    }

    int getDisplayOrder() {
        return displayOrder;
    }

    boolean isActive() {
        return active;
    }
}
