package com.akshara.accounts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A line in the petty cash book: a top-up from the cash book, or a small purchase under a head. Append only. */
@Entity
@Table(schema = "accounts", name = "petty_cash_entry")
class PettyCashEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private LocalDate entryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PettyKind kind;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(updatable = false)
    private UUID headId;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(updatable = false)
    private UUID recordedById;

    @Column(updatable = false)
    private String recordedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PettyCashEntry() {
    }

    PettyCashEntry(LocalDate entryDate, PettyKind kind, long amountPaise, UUID headId, String description,
            Actor by) {
        this.id = Ids.newId();
        this.entryDate = entryDate;
        this.kind = kind;
        this.amountPaise = amountPaise;
        this.headId = headId;
        this.description = description;
        this.recordedById = by.id();
        this.recordedByName = by.name();
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    LocalDate getEntryDate() {
        return entryDate;
    }

    PettyKind getKind() {
        return kind;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    UUID getHeadId() {
        return headId;
    }

    String getDescription() {
        return description;
    }

    String getRecordedByName() {
        return recordedByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
