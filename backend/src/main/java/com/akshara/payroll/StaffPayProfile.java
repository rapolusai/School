package com.akshara.payroll;

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

/**
 * What payroll needs to pay one staff member: the bank account (number and IFSC), PAN for TDS, UAN for PF, the ESI
 * insurance number, whether they are a PF member and whether the women's professional tax slab applies. Never
 * Aadhaar. Account numbers and PAN are shown masked; only payroll managers see them in full, and that is audited.
 */
@Entity
@Table(schema = "payroll", name = "staff_pay_profile")
class StaffPayProfile extends AssignedIdEntity {

    /** The values, already cleaned (upper case, digits only, blanks as null). */
    record Values(String bankAccountNumber, String bankIfsc, String accountHolderName, String pan, String uan,
            String esiNumber, boolean pfMember, boolean ptWomenSlab) {

        static final Values EMPTY = new Values(null, null, null, null, null, null, true, false);
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    private String bankAccountNumber;

    private String bankIfsc;

    private String accountHolderName;

    private String pan;

    private String uan;

    private String esiNumber;

    @Column(nullable = false)
    private boolean pfMember;

    @Column(nullable = false)
    private boolean ptWomenSlab;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StaffPayProfile() {
    }

    StaffPayProfile(UUID userId, Values values) {
        this.id = Ids.newId();
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        apply(values);
    }

    void apply(Values v) {
        this.bankAccountNumber = v.bankAccountNumber();
        this.bankIfsc = v.bankIfsc();
        this.accountHolderName = v.accountHolderName();
        this.pan = v.pan();
        this.uan = v.uan();
        this.esiNumber = v.esiNumber();
        this.pfMember = v.pfMember();
        this.ptWomenSlab = v.ptWomenSlab();
    }

    Values values() {
        return new Values(bankAccountNumber, bankIfsc, accountHolderName, pan, uan, esiNumber, pfMember, ptWomenSlab);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    String getBankAccountNumber() {
        return bankAccountNumber;
    }

    String getBankIfsc() {
        return bankIfsc;
    }

    String getAccountHolderName() {
        return accountHolderName;
    }

    String getPan() {
        return pan;
    }

    String getUan() {
        return uan;
    }

    String getEsiNumber() {
        return esiNumber;
    }

    boolean isPfMember() {
        return pfMember;
    }

    boolean isPtWomenSlab() {
        return ptWomenSlab;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
