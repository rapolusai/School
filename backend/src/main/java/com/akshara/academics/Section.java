package com.akshara.academics;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A section ("A", "B") of a class, with an optional capacity and class teacher. */
@Entity
@Table(schema = "academics", name = "section")
class Section extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "class_id", nullable = false, updatable = false)
    private UUID classId;

    @Column(nullable = false)
    private String name;

    private Integer capacity;

    private UUID classTeacherId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Section() {
    }

    Section(UUID classId, String name, Integer capacity, UUID classTeacherId) {
        this.id = Ids.newId();
        this.classId = classId;
        this.name = name;
        this.capacity = capacity;
        this.classTeacherId = classTeacherId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, Integer capacity, UUID classTeacherId) {
        this.name = name;
        this.capacity = capacity;
        this.classTeacherId = classTeacherId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getClassId() {
        return classId;
    }

    String getName() {
        return name;
    }

    Integer getCapacity() {
        return capacity;
    }

    UUID getClassTeacherId() {
        return classTeacherId;
    }
}
