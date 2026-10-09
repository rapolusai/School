package com.akshara.privacy;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One published version of a school's privacy notice, in English and Hindi, with the grievance officer as they were
 * when it was published. Published versions never change; a new text is a new version.
 */
@Entity
@Immutable
@Table(schema = "privacy", name = "privacy_notice")
class PrivacyNotice extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** 1, 2, 3 … per school. */
    @Column(name = "version", nullable = false)
    private int number;

    @Column(nullable = false)
    private String bodyEn;

    @Column(nullable = false)
    private String bodyHi;

    private String changeSummary;

    @Column(nullable = false)
    private String grievanceName;

    @Column(nullable = false)
    private String grievanceEmail;

    @Column(nullable = false)
    private String grievancePhone;

    @Column(nullable = false)
    private Instant publishedAt;

    private UUID publishedBy;

    private String publishedByName;

    @Column(nullable = false)
    private Instant createdAt;

    protected PrivacyNotice() {
    }

    PrivacyNotice(int number, String bodyEn, String bodyHi, String changeSummary, GrievanceOfficer officer,
            UUID publishedBy, String publishedByName) {
        this.id = Ids.newId();
        this.number = number;
        this.bodyEn = bodyEn;
        this.bodyHi = bodyHi;
        this.changeSummary = changeSummary;
        this.grievanceName = officer.getName();
        this.grievanceEmail = officer.getEmail();
        this.grievancePhone = officer.getPhone();
        this.publishedAt = Instant.now();
        this.publishedBy = publishedBy;
        this.publishedByName = publishedByName;
        this.createdAt = publishedAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    int getNumber() {
        return number;
    }

    String getBodyEn() {
        return bodyEn;
    }

    String getBodyHi() {
        return bodyHi;
    }

    String getChangeSummary() {
        return changeSummary;
    }

    String getGrievanceName() {
        return grievanceName;
    }

    String getGrievanceEmail() {
        return grievanceEmail;
    }

    String getGrievancePhone() {
        return grievancePhone;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    String getPublishedByName() {
        return publishedByName;
    }
}
