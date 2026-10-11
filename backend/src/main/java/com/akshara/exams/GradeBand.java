package com.akshara.exams;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One band of a grading scheme: a percentage at or above {@code minPercent} (below the next band) gets the grade. */
@Entity
@Table(schema = "exams", name = "grade_band")
class GradeBand extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID schemeId;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(nullable = false, updatable = false)
    private BigDecimal minPercent;

    @Column(nullable = false, updatable = false)
    private String grade;

    @Column(updatable = false)
    private BigDecimal gradePoint;

    @Column(updatable = false)
    private String remark;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected GradeBand() {
    }

    GradeBand(UUID schemeId, int position, BigDecimal minPercent, String grade, BigDecimal gradePoint, String remark) {
        this.id = Ids.newId();
        this.schemeId = schemeId;
        this.position = position;
        this.minPercent = minPercent;
        this.grade = grade;
        this.gradePoint = gradePoint;
        this.remark = remark;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getSchemeId() {
        return schemeId;
    }

    int getPosition() {
        return position;
    }

    BigDecimal getMinPercent() {
        return minPercent;
    }

    String getGrade() {
        return grade;
    }

    BigDecimal getGradePoint() {
        return gradePoint;
    }

    String getRemark() {
        return remark;
    }
}
