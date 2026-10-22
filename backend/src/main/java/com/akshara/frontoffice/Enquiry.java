package com.akshara.frontoffice;

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

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A walk-in or phone enquiry about admissions, fees or anything else, with its follow-up and outcome. An admission
 * enquiry may be converted into an application in the admissions module, which then owns the funnel.
 */
@Entity
@Table(schema = "frontoffice", name = "enquiry")
class Enquiry extends AssignedIdEntity {

    record Details(EnquirySource source, EnquiryTopic topic, String name, String phone, String childName,
            String className, String details, LocalDate followUpOn, UUID assignedToUserId, String assignedToName,
            Instant enquiredAt) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EnquirySource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EnquiryTopic topic;

    @Column(nullable = false)
    private String name;

    private String phone;

    private String childName;

    private String className;

    @Column(nullable = false)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EnquiryStatus status;

    private String outcome;

    private LocalDate followUpOn;

    private UUID assignedToUserId;

    private String assignedToName;

    private UUID applicationId;

    @Column(updatable = false)
    private String loggedByName;

    @Column(nullable = false)
    private Instant enquiredAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Enquiry() {
    }

    Enquiry(Details d, Actor by, Instant now) {
        this.id = Ids.newId();
        apply(d);
        this.status = d.followUpOn() == null ? EnquiryStatus.OPEN : EnquiryStatus.FOLLOW_UP;
        this.loggedByName = by.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.source = d.source();
        this.topic = d.topic();
        this.name = d.name();
        this.phone = d.phone();
        this.childName = d.childName();
        this.className = d.className();
        this.details = d.details();
        this.followUpOn = d.followUpOn();
        this.assignedToUserId = d.assignedToUserId();
        this.assignedToName = d.assignedToName();
        this.enquiredAt = d.enquiredAt();
        if (status != null && status.open()) {
            this.status = d.followUpOn() == null ? EnquiryStatus.OPEN : EnquiryStatus.FOLLOW_UP;
        }
    }

    void close(String outcome) {
        this.status = EnquiryStatus.CLOSED;
        this.outcome = outcome;
    }

    void reopen(LocalDate followUpOn) {
        this.followUpOn = followUpOn;
        this.status = followUpOn == null ? EnquiryStatus.OPEN : EnquiryStatus.FOLLOW_UP;
    }

    void convert(UUID applicationId) {
        this.status = EnquiryStatus.CONVERTED;
        this.applicationId = applicationId;
        this.outcome = "Converted to an admission application";
    }

    @Override
    public UUID getId() {
        return id;
    }

    EnquirySource getSource() {
        return source;
    }

    EnquiryTopic getTopic() {
        return topic;
    }

    String getName() {
        return name;
    }

    String getPhone() {
        return phone;
    }

    String getChildName() {
        return childName;
    }

    String getClassName() {
        return className;
    }

    String getDetails() {
        return details;
    }

    EnquiryStatus getStatus() {
        return status;
    }

    String getOutcome() {
        return outcome;
    }

    LocalDate getFollowUpOn() {
        return followUpOn;
    }

    UUID getAssignedToUserId() {
        return assignedToUserId;
    }

    String getAssignedToName() {
        return assignedToName;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    String getLoggedByName() {
        return loggedByName;
    }

    Instant getEnquiredAt() {
        return enquiredAt;
    }
}
