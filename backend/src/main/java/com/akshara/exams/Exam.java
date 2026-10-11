package com.akshara.exams;

import java.math.BigDecimal;
import java.time.Instant;
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

/**
 * An exam in a term (Periodic Test 1, Half-yearly). {@code weight} is its share of the term result; a term's exams
 * total 100. {@code instructions} are printed on hall tickets.
 */
@Entity
@Table(schema = "exams", name = "exam")
class Exam extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, updatable = false)
    private UUID termId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal weight;

    private String instructions;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Exam() {
    }

    Exam(UUID academicYearId, UUID termId, String name, String instructions) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.termId = termId;
        this.weight = BigDecimal.ZERO;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(name, instructions);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, String instructions) {
        this.name = name;
        this.instructions = instructions;
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

    UUID getTermId() {
        return termId;
    }

    String getName() {
        return name;
    }

    BigDecimal getWeight() {
        return weight;
    }

    String getInstructions() {
        return instructions;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
