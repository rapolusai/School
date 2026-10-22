package com.akshara.health;

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

/** One entry of the incident register (an injury, bullying or anything else). Never deleted. */
@Entity
@Table(schema = "health", name = "incident")
class Incident extends AssignedIdEntity {

    record Details(Instant incidentAt, IncidentKind kind, IncidentSeverity severity, UUID studentId,
            String personName, String place, String description, String actions, boolean parentInformed) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private Instant incidentAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentSeverity severity;

    private UUID studentId;

    private String personName;

    private String place;

    @Column(nullable = false)
    private String description;

    private String actions;

    @Column(nullable = false)
    private boolean parentInformed;

    @Column(updatable = false)
    private String reportedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Incident() {
    }

    Incident(Details d, String reportedByName, Instant now) {
        this.id = Ids.newId();
        this.reportedByName = reportedByName;
        this.createdAt = now;
        this.updatedAt = now;
        apply(d);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.incidentAt = d.incidentAt();
        this.kind = d.kind();
        this.severity = d.severity();
        this.studentId = d.studentId();
        this.personName = d.personName();
        this.place = d.place();
        this.description = d.description();
        this.actions = d.actions();
        this.parentInformed = d.parentInformed();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getIncidentAt() {
        return incidentAt;
    }

    IncidentKind getKind() {
        return kind;
    }

    IncidentSeverity getSeverity() {
        return severity;
    }

    UUID getStudentId() {
        return studentId;
    }

    String getPersonName() {
        return personName;
    }

    String getPlace() {
        return place;
    }

    String getDescription() {
        return description;
    }

    String getActions() {
        return actions;
    }

    boolean isParentInformed() {
        return parentInformed;
    }

    String getReportedByName() {
        return reportedByName;
    }
}
