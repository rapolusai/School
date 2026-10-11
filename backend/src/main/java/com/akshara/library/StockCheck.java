package com.akshara.library;

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

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * Stock verification: staff scan every copy on the shelves while it is OPEN; the copies that should be there but were
 * not seen are then listed. One check is open at a time.
 */
@Entity
@Table(schema = "library", name = "stock_check")
class StockCheck extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockCheckStatus status;

    @Column(updatable = false)
    private String note;

    @Column(updatable = false)
    private UUID startedById;

    @Column(updatable = false)
    private String startedByName;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    private String closedByName;

    private Instant closedAt;

    private Integer expectedCount;

    private Integer missingCount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StockCheck() {
    }

    StockCheck(String note, Actor by, Instant at) {
        this.id = Ids.newId();
        this.status = StockCheckStatus.OPEN;
        this.note = note;
        this.startedById = by.id();
        this.startedByName = by.name();
        this.startedAt = at;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    boolean isOpen() {
        return status == StockCheckStatus.OPEN;
    }

    void close(int expected, int missing, Actor by, Instant at) {
        this.status = StockCheckStatus.CLOSED;
        this.expectedCount = expected;
        this.missingCount = missing;
        this.closedByName = by.name();
        this.closedAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    StockCheckStatus getStatus() {
        return status;
    }

    String getNote() {
        return note;
    }

    String getStartedByName() {
        return startedByName;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    String getClosedByName() {
        return closedByName;
    }

    Instant getClosedAt() {
        return closedAt;
    }

    Integer getExpectedCount() {
        return expectedCount;
    }

    Integer getMissingCount() {
        return missingCount;
    }
}
