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

/** One instalment of a fee structure ("Quarter 1, due 10 Jun"). Numbered 1 to 12 within its structure. */
@Entity
@Table(schema = "fees", name = "fee_instalment")
class FeeInstalment extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected FeeInstalment() {
    }

    FeeInstalment(UUID structureId, int seq, String label, LocalDate dueDate) {
        this.id = Ids.newId();
        this.structureId = structureId;
        this.seq = seq;
        this.label = label;
        this.dueDate = dueDate;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String label, LocalDate dueDate) {
        this.label = label;
        this.dueDate = dueDate;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStructureId() {
        return structureId;
    }

    int getSeq() {
        return seq;
    }

    String getLabel() {
        return label;
    }

    LocalDate getDueDate() {
        return dueDate;
    }
}
