package com.akshara.govreport;

import java.time.Instant;
import java.time.LocalDate;
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

import com.akshara.govreport.GovReportTypes.SchemeStatus;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A student's enrolment in a central or state scheme for a year. Amounts are in paise. */
@Entity
@Table(schema = "govreport", name = "scheme_enrolment")
class SchemeEnrolment extends AssignedIdEntity {

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
    private String schemeCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SchemeStatus status;

    private Long amountPaise;
    private String referenceNo;
    private LocalDate appliedOn;
    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SchemeEnrolment() {
    }

    SchemeEnrolment(UUID studentId, UUID academicYearId, String schemeCode) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.academicYearId = academicYearId;
        this.schemeCode = schemeCode;
        this.status = SchemeStatus.APPLIED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(SchemeStatus status, Long amountPaise, String referenceNo, LocalDate appliedOn, String notes) {
        this.status = status;
        this.amountPaise = amountPaise;
        this.referenceNo = referenceNo;
        this.appliedOn = appliedOn;
        this.notes = notes;
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

    String getSchemeCode() {
        return schemeCode;
    }

    SchemeStatus getStatus() {
        return status;
    }

    Long getAmountPaise() {
        return amountPaise;
    }

    String getReferenceNo() {
        return referenceNo;
    }

    LocalDate getAppliedOn() {
        return appliedOn;
    }

    String getNotes() {
        return notes;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
