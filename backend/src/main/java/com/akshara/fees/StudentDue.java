package com.akshara.fees;

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

/**
 * What one student owes for one head of one instalment: the amount from the structure (gross), the concession, and
 * what has been paid so far. Net and balance are derived. {@code paidPaise} only changes through payments and their
 * cancellation, never through edits to the structure.
 */
@Entity
@Table(schema = "fees", name = "student_due")
class StudentDue extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false, updatable = false)
    private UUID instalmentId;

    @Column(nullable = false, updatable = false)
    private UUID headId;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false)
    private long grossPaise;

    @Column(nullable = false)
    private long concessionPaise;

    @Column(nullable = false)
    private long paidPaise;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StudentDue() {
    }

    StudentDue(UUID studentId, UUID academicYearId, UUID structureId, UUID instalmentId, UUID headId,
            LocalDate dueDate, long grossPaise, long concessionPaise) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.academicYearId = academicYearId;
        this.structureId = structureId;
        this.instalmentId = instalmentId;
        this.headId = headId;
        this.dueDate = dueDate;
        this.grossPaise = grossPaise;
        this.concessionPaise = concessionPaise;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** New amounts from the structure. Callers make sure the new net is not below what is already paid. */
    void reprice(long grossPaise, long concessionPaise, LocalDate dueDate) {
        this.grossPaise = grossPaise;
        this.concessionPaise = concessionPaise;
        this.dueDate = dueDate;
    }

    void moveDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    void pay(long amount) {
        if (amount <= 0 || amount > balance()) {
            throw new IllegalStateException("Payment does not fit the balance");
        }
        paidPaise += amount;
    }

    void reverse(long amount) {
        if (amount <= 0 || amount > paidPaise) {
            throw new IllegalStateException("Reversal exceeds what was paid");
        }
        paidPaise -= amount;
    }

    long net() {
        return grossPaise - concessionPaise;
    }

    long balance() {
        return net() - paidPaise;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    UUID getStructureId() {
        return structureId;
    }

    UUID getInstalmentId() {
        return instalmentId;
    }

    UUID getHeadId() {
        return headId;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    long getGrossPaise() {
        return grossPaise;
    }

    long getConcessionPaise() {
        return concessionPaise;
    }

    long getPaidPaise() {
        return paidPaise;
    }
}
