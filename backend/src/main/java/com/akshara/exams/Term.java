package com.akshara.exams;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A term of an academic year. {@code weight} is its share of the year's final result; a year's terms total 100. */
@Entity
@Table(schema = "exams", name = "term")
class Term extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDate startsOn;

    @Column(nullable = false)
    private LocalDate endsOn;

    @Column(nullable = false)
    private BigDecimal weight;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Term() {
    }

    Term(UUID academicYearId, String name, LocalDate startsOn, LocalDate endsOn) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.weight = BigDecimal.ZERO;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(name, startsOn, endsOn);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, LocalDate startsOn, LocalDate endsOn) {
        this.name = name;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    void weigh(BigDecimal weight) {
        this.weight = weight;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    String getName() {
        return name;
    }

    LocalDate getStartsOn() {
        return startsOn;
    }

    LocalDate getEndsOn() {
        return endsOn;
    }

    BigDecimal getWeight() {
        return weight;
    }
}
