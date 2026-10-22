package com.akshara.health;

import java.math.BigDecimal;
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
 * One visit to the infirmary: open while the person is there (no time out), closed with an outcome. Rows are the
 * school's health record and are never deleted.
 */
@Entity
@Table(schema = "health", name = "infirmary_visit")
class InfirmaryVisit extends AssignedIdEntity {

    /** Who the visit is for and what is wrong, already checked. */
    record Details(PersonKind personKind, UUID studentId, UUID staffUserId, String personName, String className,
            UUID sectionId, Instant timeIn, String complaint, BigDecimal temperatureC, String treatment) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PersonKind personKind;

    private UUID studentId;

    private UUID staffUserId;

    @Column(nullable = false)
    private String personName;

    private String className;

    private UUID sectionId;

    @Column(nullable = false)
    private Instant timeIn;

    private Instant timeOut;

    @Column(nullable = false)
    private String complaint;

    @Column(name = "temperature_c")
    private BigDecimal temperatureC;

    private String treatment;

    @Enumerated(EnumType.STRING)
    private VisitOutcome outcome;

    @Column(nullable = false)
    private boolean parentInformed;

    @Column(updatable = false)
    private String recordedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected InfirmaryVisit() {
    }

    InfirmaryVisit(Details d, String recordedByName, Instant now) {
        this.id = Ids.newId();
        this.recordedByName = recordedByName;
        this.createdAt = now;
        this.updatedAt = now;
        apply(d);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.personKind = d.personKind();
        this.studentId = d.studentId();
        this.staffUserId = d.staffUserId();
        this.personName = d.personName();
        this.className = d.className();
        this.sectionId = d.sectionId();
        this.timeIn = d.timeIn();
        this.complaint = d.complaint();
        this.temperatureC = d.temperatureC();
        this.treatment = d.treatment();
    }

    void close(VisitOutcome outcome, Instant timeOut, String treatment, boolean parentInformed) {
        this.outcome = outcome;
        this.timeOut = timeOut.isBefore(timeIn) ? timeIn : timeOut;
        if (treatment != null) {
            this.treatment = treatment;
        }
        this.parentInformed = parentInformed;
    }

    void markParentInformed() {
        this.parentInformed = true;
    }

    boolean isOpen() {
        return timeOut == null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    PersonKind getPersonKind() {
        return personKind;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getStaffUserId() {
        return staffUserId;
    }

    String getPersonName() {
        return personName;
    }

    String getClassName() {
        return className;
    }

    UUID getSectionId() {
        return sectionId;
    }

    Instant getTimeIn() {
        return timeIn;
    }

    Instant getTimeOut() {
        return timeOut;
    }

    String getComplaint() {
        return complaint;
    }

    BigDecimal getTemperatureC() {
        return temperatureC;
    }

    String getTreatment() {
        return treatment;
    }

    VisitOutcome getOutcome() {
        return outcome;
    }

    boolean isParentInformed() {
        return parentInformed;
    }

    String getRecordedByName() {
        return recordedByName;
    }
}
