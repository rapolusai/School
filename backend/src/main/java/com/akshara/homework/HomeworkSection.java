package com.akshara.homework;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A section that a homework is set for. */
@Entity
@Table(schema = "homework", name = "homework_section")
class HomeworkSection extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID homeworkId;

    @Column(nullable = false, updatable = false)
    private UUID sectionId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected HomeworkSection() {
    }

    HomeworkSection(UUID homeworkId, UUID sectionId) {
        this.id = Ids.newId();
        this.homeworkId = homeworkId;
        this.sectionId = sectionId;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getHomeworkId() {
        return homeworkId;
    }

    UUID getSectionId() {
        return sectionId;
    }
}
