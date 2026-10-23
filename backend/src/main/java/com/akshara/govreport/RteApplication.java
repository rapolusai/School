package com.akshara.govreport;

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

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.govreport.GovReportTypes.Eligibility;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** An admissions application in an RTE intake, with the family's declaration and the screening outcome. */
@Entity
@Table(schema = "govreport", name = "rte_application")
class RteApplication extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID intakeId;

    @Column(nullable = false, updatable = false)
    private UUID applicationId;

    private String categoryCode;
    private Long annualIncomePaise;

    @Column(nullable = false)
    private boolean incomeDeclared;

    private BigDecimal distanceKm;

    /** The documents seen, as a JSON object of document code to true/false. */
    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String documents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Eligibility eligibility;

    private String ineligibleReason;
    private UUID screenedById;
    private String screenedByName;
    private Instant screenedAt;

    @Column(nullable = false)
    private boolean withdrawn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected RteApplication() {
    }

    RteApplication(UUID intakeId, UUID applicationId) {
        this.id = Ids.newId();
        this.intakeId = intakeId;
        this.applicationId = applicationId;
        this.documents = "{}";
        this.eligibility = Eligibility.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void screen(String categoryCode, Long annualIncomePaise, boolean incomeDeclared, BigDecimal distanceKm,
            String documentsJson, Eligibility eligibility, String ineligibleReason, Actor by) {
        this.categoryCode = categoryCode;
        this.annualIncomePaise = annualIncomePaise;
        this.incomeDeclared = incomeDeclared;
        this.distanceKm = distanceKm;
        this.documents = documentsJson;
        this.eligibility = eligibility;
        this.ineligibleReason = ineligibleReason;
        this.screenedById = by.id();
        this.screenedByName = by.name();
        this.screenedAt = Instant.now();
    }

    void withdraw() {
        this.withdrawn = true;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getIntakeId() {
        return intakeId;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    String getCategoryCode() {
        return categoryCode;
    }

    Long getAnnualIncomePaise() {
        return annualIncomePaise;
    }

    boolean isIncomeDeclared() {
        return incomeDeclared;
    }

    BigDecimal getDistanceKm() {
        return distanceKm;
    }

    String getDocuments() {
        return documents;
    }

    Eligibility getEligibility() {
        return eligibility;
    }

    String getIneligibleReason() {
        return ineligibleReason;
    }

    String getScreenedByName() {
        return screenedByName;
    }

    Instant getScreenedAt() {
        return screenedAt;
    }

    boolean isWithdrawn() {
        return withdrawn;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
