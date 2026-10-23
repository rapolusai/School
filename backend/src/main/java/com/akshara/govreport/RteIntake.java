package com.akshara.govreport;

import java.math.BigDecimal;
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

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.govreport.GovReportTypes.IntakeStatus;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** The RTE 25% intake of an academic year for one entry class. */
@Entity
@Table(schema = "govreport", name = "rte_intake")
class RteIntake extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, updatable = false)
    private UUID entryClassId;

    @Column(nullable = false)
    private int totalSeats;

    @Column(nullable = false)
    private int rteSeats;

    private String stateCode;

    @Column(nullable = false)
    private long incomeLimitPaise;

    @Column(name = "distance_band_1_km", nullable = false)
    private BigDecimal distanceBand1Km;

    @Column(name = "distance_band_2_km", nullable = false)
    private BigDecimal distanceBand2Km;

    @Column(name = "distance_band_3_km", nullable = false)
    private BigDecimal distanceBand3Km;

    /** The document codes a family must bring, as a JSON array of strings. */
    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String documents;

    private LocalDate applicationsCloseOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntakeStatus status;

    private UUID currentRunId;

    private UUID createdById;
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected RteIntake() {
    }

    RteIntake(UUID academicYearId, UUID entryClassId, Actor by) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.entryClassId = entryClassId;
        this.status = IntakeStatus.OPEN;
        this.documents = "[]";
        this.createdById = by.id();
        this.createdByName = by.name();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(int totalSeats, int rteSeats, String stateCode, long incomeLimitPaise, BigDecimal band1,
            BigDecimal band2, BigDecimal band3, String documentsJson, LocalDate applicationsCloseOn) {
        this.totalSeats = totalSeats;
        this.rteSeats = rteSeats;
        this.stateCode = stateCode;
        this.incomeLimitPaise = incomeLimitPaise;
        this.distanceBand1Km = band1;
        this.distanceBand2Km = band2;
        this.distanceBand3Km = band3;
        this.documents = documentsJson;
        this.applicationsCloseOn = applicationsCloseOn;
    }

    void lotteryDone(UUID runId) {
        this.currentRunId = runId;
        this.status = IntakeStatus.LOTTERY_DONE;
    }

    void close() {
        this.status = IntakeStatus.CLOSED;
    }

    /** 1 within the first band, 2 within the second, 3 within the third, 4 beyond or unknown. */
    int distanceBand(BigDecimal km) {
        if (km == null) {
            return 4;
        }
        if (km.compareTo(distanceBand1Km) <= 0) {
            return 1;
        }
        if (km.compareTo(distanceBand2Km) <= 0) {
            return 2;
        }
        if (km.compareTo(distanceBand3Km) <= 0) {
            return 3;
        }
        return 4;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    UUID getEntryClassId() {
        return entryClassId;
    }

    int getTotalSeats() {
        return totalSeats;
    }

    int getRteSeats() {
        return rteSeats;
    }

    String getStateCode() {
        return stateCode;
    }

    long getIncomeLimitPaise() {
        return incomeLimitPaise;
    }

    BigDecimal getDistanceBand1Km() {
        return distanceBand1Km;
    }

    BigDecimal getDistanceBand2Km() {
        return distanceBand2Km;
    }

    BigDecimal getDistanceBand3Km() {
        return distanceBand3Km;
    }

    String getDocuments() {
        return documents;
    }

    LocalDate getApplicationsCloseOn() {
        return applicationsCloseOn;
    }

    IntakeStatus getStatus() {
        return status;
    }

    UUID getCurrentRunId() {
        return currentRunId;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
