package com.akshara.health;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** Height and weight measured on a day (one row per student per day). Replaced, never edited. */
@Entity
@Immutable
@Table(schema = "health", name = "growth_measurement")
class GrowthMeasurement extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID studentId;

    @Column(nullable = false)
    private LocalDate measuredOn;

    private BigDecimal heightCm;

    private BigDecimal weightKg;

    private String recordedByName;

    @Column(nullable = false)
    private Instant createdAt;

    protected GrowthMeasurement() {
    }

    GrowthMeasurement(UUID studentId, LocalDate measuredOn, BigDecimal heightCm, BigDecimal weightKg,
            String recordedByName, Instant now) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.measuredOn = measuredOn;
        this.heightCm = heightCm;
        this.weightKg = weightKg;
        this.recordedByName = recordedByName;
        this.createdAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    LocalDate getMeasuredOn() {
        return measuredOn;
    }

    BigDecimal getHeightCm() {
        return heightCm;
    }

    BigDecimal getWeightKg() {
        return weightKg;
    }

    String getRecordedByName() {
        return recordedByName;
    }
}
