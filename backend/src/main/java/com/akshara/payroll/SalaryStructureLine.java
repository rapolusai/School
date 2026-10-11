package com.akshara.payroll;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One component of a salary structure and its full monthly amount in paise. */
@Entity
@Table(schema = "payroll", name = "salary_structure_line")
class SalaryStructureLine extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false, updatable = false)
    private UUID componentId;

    @Column(nullable = false, updatable = false)
    private long monthlyPaise;

    protected SalaryStructureLine() {
    }

    SalaryStructureLine(UUID structureId, UUID componentId, long monthlyPaise) {
        this.id = Ids.newId();
        this.structureId = structureId;
        this.componentId = componentId;
        this.monthlyPaise = monthlyPaise;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStructureId() {
        return structureId;
    }

    UUID getComponentId() {
        return componentId;
    }

    long getMonthlyPaise() {
        return monthlyPaise;
    }
}
