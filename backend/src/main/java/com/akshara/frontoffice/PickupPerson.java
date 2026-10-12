package com.akshara.frontoffice;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A person a parent authorises to collect their child routinely, with a photo if the parent gave one. */
@Entity
@Table(schema = "frontoffice", name = "pickup_person")
class PickupPerson extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String relation;

    @Column(nullable = false)
    private String phone;

    private UUID photoFileId;

    @Column(updatable = false)
    private UUID addedByUserId;

    @Column(updatable = false)
    private String addedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PickupPerson() {
    }

    PickupPerson(UUID studentId, String name, String relation, String phone, Actor by, Instant now) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.name = name;
        this.relation = relation;
        this.phone = phone;
        this.addedByUserId = by.id();
        this.addedByName = by.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, String relation, String phone) {
        this.name = name;
        this.relation = relation;
        this.phone = phone;
    }

    void setPhotoFileId(UUID photoFileId) {
        this.photoFileId = photoFileId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    String getName() {
        return name;
    }

    String getRelation() {
        return relation;
    }

    String getPhone() {
        return phone;
    }

    UUID getPhotoFileId() {
        return photoFileId;
    }

    String getAddedByName() {
        return addedByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
