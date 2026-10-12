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
 * One visit: a person who came to the school (IN until they leave, then OUT), or a visitor staff expect on a day
 * (EXPECTED until the gate checks them in). Rows are the school's security record and are never deleted.
 */
@Entity
@Table(schema = "frontoffice", name = "visitor")
class Visitor extends AssignedIdEntity {

    /** What a visitor's details look like, as the office or the gate types them (already cleaned). */
    record Details(String name, String phone, VisitorPurpose purpose, String purposeNote, UUID toMeetUserId,
            String toMeetName, int peopleCount, IdType idType) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VisitorPurpose purpose;

    private String purposeNote;

    private UUID toMeetUserId;

    private String toMeetName;

    @Column(nullable = false)
    private int peopleCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdType idType;

    private String badgeNo;

    private UUID photoFileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VisitorStatus status;

    private LocalDate expectedOn;

    private Instant checkedInAt;

    private Instant checkedOutAt;

    @Column(updatable = false)
    private String addedByName;

    private String checkedInByName;

    private String checkedOutByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Visitor() {
    }

    /** A visitor who is here now (checked in as they arrive). */
    static Visitor arriving(Details d, String badgeNo, Actor by, Instant now) {
        Visitor v = new Visitor(d, by, now);
        v.status = VisitorStatus.IN;
        v.badgeNo = badgeNo;
        v.checkedInAt = now;
        v.checkedInByName = by.name();
        return v;
    }

    /** A visitor staff expect on a day. */
    static Visitor expected(Details d, LocalDate expectedOn, Actor by, Instant now) {
        Visitor v = new Visitor(d, by, now);
        v.status = VisitorStatus.EXPECTED;
        v.expectedOn = expectedOn;
        return v;
    }

    private Visitor(Details d, Actor by, Instant now) {
        this.id = Ids.newId();
        apply(d);
        this.addedByName = by.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.name = d.name();
        this.phone = d.phone();
        this.purpose = d.purpose();
        this.purposeNote = d.purposeNote();
        this.toMeetUserId = d.toMeetUserId();
        this.toMeetName = d.toMeetName();
        this.peopleCount = d.peopleCount();
        this.idType = d.idType();
    }

    /** An expected visitor arrives. */
    void checkIn(String badgeNo, Actor by, Instant now) {
        this.status = VisitorStatus.IN;
        this.badgeNo = badgeNo;
        this.checkedInAt = now;
        this.checkedInByName = by.name();
        this.expectedOn = null;
    }

    /** An expected visitor is now expected on another day. */
    void reschedule(LocalDate day) {
        this.expectedOn = day;
    }

    void checkOut(Actor by, Instant now) {
        this.status = VisitorStatus.OUT;
        this.checkedOutAt = now.isBefore(checkedInAt) ? checkedInAt : now;
        this.checkedOutByName = by.name();
    }

    void setPhotoFileId(UUID photoFileId) {
        this.photoFileId = photoFileId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getPhone() {
        return phone;
    }

    VisitorPurpose getPurpose() {
        return purpose;
    }

    String getPurposeNote() {
        return purposeNote;
    }

    UUID getToMeetUserId() {
        return toMeetUserId;
    }

    String getToMeetName() {
        return toMeetName;
    }

    int getPeopleCount() {
        return peopleCount;
    }

    IdType getIdType() {
        return idType;
    }

    String getBadgeNo() {
        return badgeNo;
    }

    UUID getPhotoFileId() {
        return photoFileId;
    }

    VisitorStatus getStatus() {
        return status;
    }

    LocalDate getExpectedOn() {
        return expectedOn;
    }

    Instant getCheckedInAt() {
        return checkedInAt;
    }

    Instant getCheckedOutAt() {
        return checkedOutAt;
    }

    String getAddedByName() {
        return addedByName;
    }

    String getCheckedInByName() {
        return checkedInByName;
    }

    String getCheckedOutByName() {
        return checkedOutByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
