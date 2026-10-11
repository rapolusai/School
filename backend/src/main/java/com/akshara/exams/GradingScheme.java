package com.akshara.exams;

import java.math.BigDecimal;
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
 * A grading scheme: grade bands (in {@link GradeBand}) and the pass marks per subject and overall. The built-in
 * schemes carry a code (CBSE_1_8, CBSE_9_10, CBSE_CO, ICSE, STATE) so they are added only once; schools edit them like
 * their own.
 */
@Entity
@Table(schema = "exams", name = "grading_scheme")
class GradingScheme extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SchemeKind kind;

    private String description;

    @Column(nullable = false)
    private BigDecimal passPercent;

    @Column(nullable = false)
    private BigDecimal overallPassPercent;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected GradingScheme() {
    }

    GradingScheme(String name, SchemeKind kind, String description, BigDecimal passPercent,
            BigDecimal overallPassPercent) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.kind = kind;
        update(name, description, passPercent, overallPassPercent);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, String description, BigDecimal passPercent, BigDecimal overallPassPercent) {
        this.name = name;
        this.description = description;
        this.passPercent = passPercent;
        this.overallPassPercent = overallPassPercent;
    }

    /** Marks a change of the bands only, so the version and updated time move. */
    void bandsChanged() {
        this.updatedAt = Instant.now();
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

    SchemeKind getKind() {
        return kind;
    }

    String getDescription() {
        return description;
    }

    BigDecimal getPassPercent() {
        return passPercent;
    }

    BigDecimal getOverallPassPercent() {
        return overallPassPercent;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getVersion() {
        return version;
    }
}
