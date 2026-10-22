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

/** One finding of the safety audit checklist, open until it is closed with a date and a note. */
@Entity
@Table(schema = "health", name = "safety_audit")
class SafetyAudit extends AssignedIdEntity {

    record Details(AuditArea area, LocalDate auditDate, String finding, AuditSeverity severity, LocalDate dueOn) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditArea area;

    @Column(nullable = false)
    private LocalDate auditDate;

    @Column(nullable = false)
    private String finding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditStatus status;

    private LocalDate dueOn;

    private LocalDate closedOn;

    private String closureNote;

    @Column(updatable = false)
    private String auditedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SafetyAudit() {
    }

    SafetyAudit(Details d, String auditedByName, Instant now) {
        this.id = Ids.newId();
        this.status = AuditStatus.OPEN;
        this.auditedByName = auditedByName;
        this.createdAt = now;
        this.updatedAt = now;
        apply(d);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.area = d.area();
        this.auditDate = d.auditDate();
        this.finding = d.finding();
        this.severity = d.severity();
        this.dueOn = d.dueOn();
    }

    void close(LocalDate on, String note) {
        this.status = AuditStatus.CLOSED;
        this.closedOn = on;
        this.closureNote = note;
    }

    void reopen() {
        this.status = AuditStatus.OPEN;
        this.closedOn = null;
        this.closureNote = null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    AuditArea getArea() {
        return area;
    }

    LocalDate getAuditDate() {
        return auditDate;
    }

    String getFinding() {
        return finding;
    }

    AuditSeverity getSeverity() {
        return severity;
    }

    AuditStatus getStatus() {
        return status;
    }

    LocalDate getDueOn() {
        return dueOn;
    }

    LocalDate getClosedOn() {
        return closedOn;
    }

    String getClosureNote() {
        return closureNote;
    }

    String getAuditedByName() {
        return auditedByName;
    }
}
