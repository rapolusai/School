package com.akshara.lessons;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A section an online test is for. */
@Entity
@Table(schema = "lessons", name = "test_section")
class TestSection extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID testId;

    @Column(nullable = false, updatable = false)
    private UUID sectionId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected TestSection() {
    }

    TestSection(UUID testId, UUID sectionId) {
        this.id = Ids.newId();
        this.testId = testId;
        this.sectionId = sectionId;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTestId() {
        return testId;
    }

    UUID getSectionId() {
        return sectionId;
    }
}
