package com.akshara.fees;

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

/** The school's late-fee rule. At most one per school; no row means no late fee. */
@Entity
@Table(schema = "fees", name = "late_fee_rule")
class LateFeeRuleEntity extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LateFeeMode mode;

    @Column(nullable = false)
    private int graceDays;

    @Column(nullable = false)
    private long flatPaise;

    @Column(nullable = false)
    private long perDayPaise;

    @Column(nullable = false)
    private long capPaise;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LateFeeRuleEntity() {
    }

    LateFeeRuleEntity(FeeMath.LateFeeRule rule) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        apply(rule);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(FeeMath.LateFeeRule rule) {
        this.mode = rule.mode();
        this.graceDays = rule.graceDays();
        this.flatPaise = rule.flatPaise();
        this.perDayPaise = rule.perDayPaise();
        this.capPaise = rule.capPaise();
    }

    FeeMath.LateFeeRule toRule() {
        return new FeeMath.LateFeeRule(mode, graceDays, flatPaise, perDayPaise, capPaise);
    }

    @Override
    public UUID getId() {
        return id;
    }
}
