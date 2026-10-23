package com.akshara.govreport;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.govreport.GovReportTypes.AllotmentOutcome;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One line of a run's ordered result. Immutable with the run. */
@Entity
@Table(schema = "govreport", name = "rte_allotment")
class RteAllotment extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID runId;

    @Column(nullable = false, updatable = false)
    private UUID rteApplicationId;

    @Column(nullable = false, updatable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AllotmentOutcome outcome;

    @Column(updatable = false)
    private Integer waitlistNo;

    @Column(nullable = false, updatable = false)
    private int distanceBand;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected RteAllotment() {
    }

    RteAllotment(UUID runId, UUID rteApplicationId, int position, AllotmentOutcome outcome, Integer waitlistNo,
            int distanceBand, Instant at) {
        this.id = Ids.newId();
        this.runId = runId;
        this.rteApplicationId = rteApplicationId;
        this.position = position;
        this.outcome = outcome;
        this.waitlistNo = waitlistNo;
        this.distanceBand = distanceBand;
        this.createdAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getRunId() {
        return runId;
    }

    UUID getRteApplicationId() {
        return rteApplicationId;
    }

    int getPosition() {
        return position;
    }

    AllotmentOutcome getOutcome() {
        return outcome;
    }

    Integer getWaitlistNo() {
        return waitlistNo;
    }

    int getDistanceBand() {
        return distanceBand;
    }
}
