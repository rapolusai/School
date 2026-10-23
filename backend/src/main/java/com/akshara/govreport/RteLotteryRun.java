package com.akshara.govreport;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One lottery run of an intake: its seed, time, witness and reason. Never changed once written. */
@Entity
@Table(schema = "govreport", name = "rte_lottery_run")
class RteLotteryRun extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID intakeId;

    @Column(nullable = false, updatable = false)
    private int runNo;

    @Column(nullable = false, updatable = false)
    private long seed;

    @Column(nullable = false, updatable = false)
    private int seats;

    @Column(nullable = false, updatable = false)
    private int applicants;

    @Column(nullable = false, updatable = false)
    private String witnessName;

    @Column(updatable = false)
    private String reason;

    @Column(updatable = false)
    private UUID ranById;

    @Column(updatable = false)
    private String ranByName;

    @Column(nullable = false, updatable = false)
    private Instant ranAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected RteLotteryRun() {
    }

    RteLotteryRun(UUID intakeId, int runNo, long seed, int seats, int applicants, String witnessName, String reason,
            Actor by, Instant at) {
        this.id = Ids.newId();
        this.intakeId = intakeId;
        this.runNo = runNo;
        this.seed = seed;
        this.seats = seats;
        this.applicants = applicants;
        this.witnessName = witnessName;
        this.reason = reason;
        this.ranById = by.id();
        this.ranByName = by.name();
        this.ranAt = at;
        this.createdAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getIntakeId() {
        return intakeId;
    }

    int getRunNo() {
        return runNo;
    }

    long getSeed() {
        return seed;
    }

    int getSeats() {
        return seats;
    }

    int getApplicants() {
        return applicants;
    }

    String getWitnessName() {
        return witnessName;
    }

    String getReason() {
        return reason;
    }

    String getRanByName() {
        return ranByName;
    }

    Instant getRanAt() {
        return ranAt;
    }
}
