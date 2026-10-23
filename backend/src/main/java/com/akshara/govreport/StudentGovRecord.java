package com.akshara.govreport;

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

import com.akshara.audit.AuditService.Actor;
import com.akshara.govreport.GovReportTypes.UpdatedVia;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** The government fields of one student. Aadhaar is never stored here. */
@Entity
@Table(schema = "govreport", name = "student_record")
class StudentGovRecord extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    private String apaarId;

    @Column(nullable = false)
    private boolean apaarLocked;

    private String pen;
    private String socialCategory;
    private String religion;
    private String motherTongue;
    private Boolean bpl;
    private String cwsnType;
    private Boolean rteAdmitted;
    private String previousSchoolUdise;

    private UUID updatedById;
    private String updatedByName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UpdatedVia updatedVia;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StudentGovRecord() {
    }

    StudentGovRecord(UUID studentId) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.updatedVia = UpdatedVia.OFFICE;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** The office (or an import) sets every field; setting an APAAR ID locks it against the family app. */
    void updateByOffice(String apaarId, Boolean apaarLocked, String pen, String socialCategory, String religion,
            String motherTongue, Boolean bpl, String cwsnType, Boolean rteAdmitted, String previousSchoolUdise,
            Actor by, UpdatedVia via) {
        this.apaarId = apaarId;
        this.apaarLocked = apaarLocked != null ? apaarLocked : apaarId != null;
        this.pen = pen;
        this.socialCategory = socialCategory;
        this.religion = religion;
        this.motherTongue = motherTongue;
        this.bpl = bpl;
        this.cwsnType = cwsnType;
        this.rteAdmitted = rteAdmitted;
        this.previousSchoolUdise = previousSchoolUdise;
        touchedBy(by, via);
    }

    /** A parent fills the mother tongue, and the APAAR ID once (while it is empty and not locked). */
    void updateByFamily(String motherTongue, String apaarId, Actor by) {
        this.motherTongue = motherTongue;
        if (apaarId != null && this.apaarId == null && !apaarLocked) {
            this.apaarId = apaarId;
        }
        touchedBy(by, UpdatedVia.FAMILY);
    }

    void lockApaar(boolean locked, Actor by) {
        this.apaarLocked = locked;
        touchedBy(by, UpdatedVia.OFFICE);
    }

    private void touchedBy(Actor by, UpdatedVia via) {
        this.updatedById = by.id();
        this.updatedByName = by.name();
        this.updatedVia = via;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    String getApaarId() {
        return apaarId;
    }

    boolean isApaarLocked() {
        return apaarLocked;
    }

    String getPen() {
        return pen;
    }

    String getSocialCategory() {
        return socialCategory;
    }

    String getReligion() {
        return religion;
    }

    String getMotherTongue() {
        return motherTongue;
    }

    Boolean getBpl() {
        return bpl;
    }

    String getCwsnType() {
        return cwsnType;
    }

    Boolean getRteAdmitted() {
        return rteAdmitted;
    }

    String getPreviousSchoolUdise() {
        return previousSchoolUdise;
    }

    String getUpdatedByName() {
        return updatedByName;
    }

    UpdatedVia getUpdatedVia() {
        return updatedVia;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
