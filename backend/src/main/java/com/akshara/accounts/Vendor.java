package com.akshara.accounts;

import java.math.BigDecimal;
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
import com.akshara.shared.Ids;

/** A supplier. PAN, phone and bank details are shown masked; the TDS section and rate are editable data. */
@Entity
@Table(schema = "accounts", name = "vendor")
class Vendor extends AssignedIdEntity {

    /** The editable fields. */
    record Fields(String name, String gstin, String pan, String phone, String email, String address,
            String bankAccountNumber, String bankIfsc, String tdsSection, BigDecimal tdsRatePercent, boolean active) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private String gstin;

    private String pan;

    private String phone;

    private String email;

    private String address;

    private String bankAccountNumber;

    private String bankIfsc;

    private String tdsSection;

    private BigDecimal tdsRatePercent;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Vendor() {
    }

    Vendor(Fields f) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(f);
    }

    void update(Fields f) {
        this.name = f.name();
        this.gstin = f.gstin();
        this.pan = f.pan();
        this.phone = f.phone();
        this.email = f.email();
        this.address = f.address();
        this.bankAccountNumber = f.bankAccountNumber();
        this.bankIfsc = f.bankIfsc();
        this.tdsSection = f.tdsSection();
        this.tdsRatePercent = f.tdsRatePercent();
        this.active = f.active();
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getGstin() {
        return gstin;
    }

    String getPan() {
        return pan;
    }

    String getPhone() {
        return phone;
    }

    String getEmail() {
        return email;
    }

    String getAddress() {
        return address;
    }

    String getBankAccountNumber() {
        return bankAccountNumber;
    }

    String getBankIfsc() {
        return bankIfsc;
    }

    String getTdsSection() {
        return tdsSection;
    }

    BigDecimal getTdsRatePercent() {
        return tdsRatePercent;
    }

    boolean isActive() {
        return active;
    }

    long getVersion() {
        return version;
    }
}
