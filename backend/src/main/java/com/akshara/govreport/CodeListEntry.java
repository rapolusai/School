package com.akshara.govreport;

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

import com.akshara.govreport.GovReportTypes.CodeListType;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One code of a code list (a UDISE social category, a religion, a scheme, a state's RTE income limit), in force
 * between two dates and marked "verify" until the school checks it against the current portal template.
 */
@Entity
@Table(schema = "govreport", name = "code_list")
class CodeListEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "list_code", nullable = false, updatable = false)
    private CodeListType list;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String label;

    private String value;

    private String stateCode;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    @Column(nullable = false)
    private boolean verify;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CodeListEntry() {
    }

    CodeListEntry(CodeListType list, String code, String label, String value, String stateCode,
            LocalDate effectiveFrom, LocalDate effectiveTo, boolean verify, int displayOrder) {
        this.id = Ids.newId();
        this.list = list;
        this.code = code;
        this.label = label;
        this.value = value;
        this.stateCode = stateCode;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.verify = verify;
        this.active = true;
        this.displayOrder = displayOrder;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String label, String value, String stateCode, LocalDate effectiveFrom, LocalDate effectiveTo,
            boolean verify, boolean active, int displayOrder) {
        this.label = label;
        this.value = value;
        this.stateCode = stateCode;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.verify = verify;
        this.active = active;
        this.displayOrder = displayOrder;
    }

    /** In force on the day: active, started, and not yet ended. */
    boolean inForce(LocalDate day) {
        return active && !effectiveFrom.isAfter(day) && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    @Override
    public UUID getId() {
        return id;
    }

    CodeListType getList() {
        return list;
    }

    String getCode() {
        return code;
    }

    String getLabel() {
        return label;
    }

    String getValue() {
        return value;
    }

    String getStateCode() {
        return stateCode;
    }

    LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    boolean isVerify() {
        return verify;
    }

    boolean isActive() {
        return active;
    }

    int getDisplayOrder() {
        return displayOrder;
    }
}
