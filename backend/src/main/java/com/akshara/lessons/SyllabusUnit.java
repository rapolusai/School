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

/** A unit (chapter) of a syllabus, in order. */
@Entity
@Table(schema = "lessons", name = "syllabus_unit")
class SyllabusUnit extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID syllabusId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected SyllabusUnit() {
    }

    SyllabusUnit(UUID syllabusId, int position, String title) {
        this.id = Ids.newId();
        this.syllabusId = syllabusId;
        this.createdAt = Instant.now();
        update(position, title);
    }

    void update(int position, String title) {
        this.position = position;
        this.title = title;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getSyllabusId() {
        return syllabusId;
    }

    int getPosition() {
        return position;
    }

    String getTitle() {
        return title;
    }
}
