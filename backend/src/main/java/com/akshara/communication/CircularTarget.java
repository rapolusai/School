package com.akshara.communication;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One chosen part of a circular's audience: a class, a section or a role. */
@Entity
@Table(schema = "communication", name = "circular_target")
class CircularTarget extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID circularId;

    @Column(updatable = false)
    private UUID classId;

    @Column(updatable = false)
    private UUID sectionId;

    @Column(updatable = false)
    private String roleCode;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected CircularTarget() {
    }

    private CircularTarget(UUID circularId, UUID classId, UUID sectionId, String roleCode) {
        this.id = Ids.newId();
        this.circularId = circularId;
        this.classId = classId;
        this.sectionId = sectionId;
        this.roleCode = roleCode;
        this.createdAt = Instant.now();
    }

    static CircularTarget ofClass(UUID circularId, UUID classId) {
        return new CircularTarget(circularId, classId, null, null);
    }

    static CircularTarget ofSection(UUID circularId, UUID sectionId) {
        return new CircularTarget(circularId, null, sectionId, null);
    }

    static CircularTarget ofRole(UUID circularId, String roleCode) {
        return new CircularTarget(circularId, null, null, roleCode);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getCircularId() {
        return circularId;
    }

    UUID getClassId() {
        return classId;
    }

    UUID getSectionId() {
        return sectionId;
    }

    String getRoleCode() {
        return roleCode;
    }
}
