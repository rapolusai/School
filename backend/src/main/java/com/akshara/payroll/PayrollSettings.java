package com.akshara.payroll;

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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A school's payroll settings: the pay day, how part months are prorated, which statutory deductions apply (with the
 * PF wage ceiling choice and the professional tax state), the school's registration numbers and the bank account
 * salaries are paid from. One row per school, created when the settings are first saved.
 */
@Entity
@Table(schema = "payroll", name = "settings")
class PayrollSettings extends AssignedIdEntity {

    /** The editable values, already cleaned (upper case codes, blanks as null). */
    record Values(int payDay, ProrationBasis prorationBasis, boolean pfEnabled, boolean pfOnFullWages,
            boolean esiEnabled, boolean ptEnabled, String ptState, String pfEstablishmentCode, String esiEmployerCode,
            String ptRegistration, String tan, String bankName, String bankBranch, String bankAccountNumber,
            String bankIfsc) {

        /** What a school starts with before saving anything. */
        static final Values DEFAULTS = new Values(1, ProrationBasis.CALENDAR_DAYS, true, false, true, true, null, null,
                null, null, null, null, null, null, null);
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private int payDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProrationBasis prorationBasis;

    @Column(nullable = false)
    private boolean pfEnabled;

    @Column(nullable = false)
    private boolean pfOnFullWages;

    @Column(nullable = false)
    private boolean esiEnabled;

    @Column(nullable = false)
    private boolean ptEnabled;

    private String ptState;

    private String pfEstablishmentCode;

    private String esiEmployerCode;

    private String ptRegistration;

    private String tan;

    private String bankName;

    private String bankBranch;

    private String bankAccountNumber;

    private String bankIfsc;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PayrollSettings() {
    }

    PayrollSettings(Values values) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        apply(values);
    }

    void apply(Values v) {
        this.payDay = v.payDay();
        this.prorationBasis = v.prorationBasis();
        this.pfEnabled = v.pfEnabled();
        this.pfOnFullWages = v.pfOnFullWages();
        this.esiEnabled = v.esiEnabled();
        this.ptEnabled = v.ptEnabled();
        this.ptState = v.ptState();
        this.pfEstablishmentCode = v.pfEstablishmentCode();
        this.esiEmployerCode = v.esiEmployerCode();
        this.ptRegistration = v.ptRegistration();
        this.tan = v.tan();
        this.bankName = v.bankName();
        this.bankBranch = v.bankBranch();
        this.bankAccountNumber = v.bankAccountNumber();
        this.bankIfsc = v.bankIfsc();
    }

    Values values() {
        return new Values(payDay, prorationBasis, pfEnabled, pfOnFullWages, esiEnabled, ptEnabled, ptState,
                pfEstablishmentCode, esiEmployerCode, ptRegistration, tan, bankName, bankBranch, bankAccountNumber,
                bankIfsc);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
