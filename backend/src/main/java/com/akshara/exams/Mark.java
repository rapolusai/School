package com.akshara.exams;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
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

/** One student's marks on a sheet, by component, or a code (AB, ML, EX) instead. */
@Entity
@Table(schema = "exams", name = "mark")
class Mark extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID sheetId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    private BigDecimal theory;

    private BigDecimal practical;

    private BigDecimal internal;

    @Enumerated(EnumType.STRING)
    private MarkCode code;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Mark() {
    }

    Mark(UUID sheetId, UUID studentId, Instant at) {
        this.id = Ids.newId();
        this.sheetId = sheetId;
        this.studentId = studentId;
        this.createdAt = at;
        this.updatedAt = at;
    }

    /** Sets the values; returns true when anything changed. */
    boolean set(BigDecimal theory, BigDecimal practical, BigDecimal internal, MarkCode code, Instant at) {
        boolean changed = !same(this.theory, theory) || !same(this.practical, practical)
                || !same(this.internal, internal) || this.code != code;
        if (changed) {
            this.theory = theory;
            this.practical = practical;
            this.internal = internal;
            this.code = code;
            this.updatedAt = at;
        }
        return changed;
    }

    boolean blank() {
        return theory == null && practical == null && internal == null && code == null;
    }

    GradeMath.Entry entry() {
        return new GradeMath.Entry(theory, practical, internal, code);
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getSheetId() {
        return sheetId;
    }

    UUID getStudentId() {
        return studentId;
    }

    BigDecimal getTheory() {
        return theory;
    }

    BigDecimal getPractical() {
        return practical;
    }

    BigDecimal getInternal() {
        return internal;
    }

    MarkCode getCode() {
        return code;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Mark other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
