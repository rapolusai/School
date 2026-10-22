package com.akshara.certificates;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;

/**
 * A school's certificate settings, made on first use: the printed header (name, address, affiliation number, logo
 * text), the signatory, whether a transfer certificate warns about fees due, and the ID card validity and lost-card
 * fee.
 */
@Entity
@Table(schema = "certificates", name = "settings")
class CertificateSettings extends AssignedIdEntity {

    static final int DEFAULT_CARD_MONTHS = 12;

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    private String headerName;

    private String headerAddress;

    private String affiliationNo;

    private String logoText;

    private String signatoryName;

    private String signatoryDesignation;

    @Column(nullable = false)
    private boolean tcDuesCheck;

    @Column(nullable = false)
    private int cardValidMonths;

    @Column(nullable = false)
    private long lostCardFeePaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CertificateSettings() {
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String headerName, String headerAddress, String affiliationNo, String logoText, String signatoryName,
            String signatoryDesignation, boolean tcDuesCheck, int cardValidMonths, long lostCardFeePaise) {
        this.headerName = headerName;
        this.headerAddress = headerAddress;
        this.affiliationNo = affiliationNo;
        this.logoText = logoText;
        this.signatoryName = signatoryName;
        this.signatoryDesignation = signatoryDesignation;
        this.tcDuesCheck = tcDuesCheck;
        this.cardValidMonths = cardValidMonths;
        this.lostCardFeePaise = lostCardFeePaise;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getHeaderName() {
        return headerName;
    }

    String getHeaderAddress() {
        return headerAddress;
    }

    String getAffiliationNo() {
        return affiliationNo;
    }

    String getLogoText() {
        return logoText;
    }

    String getSignatoryName() {
        return signatoryName;
    }

    String getSignatoryDesignation() {
        return signatoryDesignation;
    }

    boolean isTcDuesCheck() {
        return tcDuesCheck;
    }

    int getCardValidMonths() {
        return cardValidMonths;
    }

    long getLostCardFeePaise() {
        return lostCardFeePaise;
    }
}
