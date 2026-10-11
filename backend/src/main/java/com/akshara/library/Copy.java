package com.akshara.library;

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

/**
 * One physical book of a {@link Title}. Its accession number is the school's running number for the copy (prefix and
 * sequence, see {@link LibrarySetupService#allocateAccessionNumbers}) and is also the value of its barcode label.
 */
@Entity
@Table(schema = "library", name = "copy")
class Copy extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID titleId;

    @Column(nullable = false, updatable = false)
    private String accessionNo;

    private Long pricePaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CopySource source;

    private LocalDate acquiredOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CopyStatus status;

    private String statusNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Copy() {
    }

    Copy(UUID titleId, String accessionNo, Long pricePaise, CopySource source, LocalDate acquiredOn) {
        this.id = Ids.newId();
        this.titleId = titleId;
        this.accessionNo = accessionNo;
        this.pricePaise = pricePaise;
        this.source = source;
        this.acquiredOn = acquiredOn;
        this.status = CopyStatus.AVAILABLE;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void updateDetails(Long pricePaise, CopySource source, LocalDate acquiredOn) {
        this.pricePaise = pricePaise;
        this.source = source;
        this.acquiredOn = acquiredOn;
    }

    void setStatus(CopyStatus status, String note) {
        this.status = status;
        this.statusNote = note;
    }

    /** A copy that may be lent: on the shelf, or on the hold shelf for the member it is kept for. */
    boolean isOnShelf() {
        return status == CopyStatus.AVAILABLE || status == CopyStatus.RESERVED;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTitleId() {
        return titleId;
    }

    String getAccessionNo() {
        return accessionNo;
    }

    Long getPricePaise() {
        return pricePaise;
    }

    CopySource getSource() {
        return source;
    }

    LocalDate getAcquiredOn() {
        return acquiredOn;
    }

    CopyStatus getStatus() {
        return status;
    }

    String getStatusNote() {
        return statusNote;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
