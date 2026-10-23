package com.akshara.health;

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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One fire or evacuation drill in the register, with when the next one is due. */
@Entity
@Table(schema = "health", name = "fire_drill")
class FireDrill extends AssignedIdEntity {

    record Details(LocalDate drillDate, DrillKind kind, String block, int timeToClearSeconds, int attendance,
            String issues, LocalDate nextDue) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private LocalDate drillDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DrillKind kind;

    @Column(nullable = false)
    private String block;

    @Column(nullable = false)
    private int timeToClearSeconds;

    @Column(nullable = false)
    private int attendance;

    private String issues;

    @Column(nullable = false)
    private LocalDate nextDue;

    @Column(updatable = false)
    private String conductedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected FireDrill() {
    }

    FireDrill(Details d, String conductedByName, Instant now) {
        this.id = Ids.newId();
        this.conductedByName = conductedByName;
        this.createdAt = now;
        this.updatedAt = now;
        apply(d);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.drillDate = d.drillDate();
        this.kind = d.kind();
        this.block = d.block();
        this.timeToClearSeconds = d.timeToClearSeconds();
        this.attendance = d.attendance();
        this.issues = d.issues();
        this.nextDue = d.nextDue();
    }

    @Override
    public UUID getId() {
        return id;
    }

    LocalDate getDrillDate() {
        return drillDate;
    }

    DrillKind getKind() {
        return kind;
    }

    String getBlock() {
        return block;
    }

    int getTimeToClearSeconds() {
        return timeToClearSeconds;
    }

    int getAttendance() {
        return attendance;
    }

    String getIssues() {
        return issues;
    }

    LocalDate getNextDue() {
        return nextDue;
    }

    String getConductedByName() {
        return conductedByName;
    }
}
