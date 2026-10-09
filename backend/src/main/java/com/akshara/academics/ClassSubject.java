package com.akshara.academics;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One subject that one class studies. */
@Entity
@Table(schema = "academics", name = "class_subject")
class ClassSubject extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "class_id", nullable = false, updatable = false)
    private UUID classId;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ClassSubject() {
    }

    ClassSubject(UUID classId, UUID subjectId) {
        this.id = Ids.newId();
        this.classId = classId;
        this.subjectId = subjectId;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getClassId() {
        return classId;
    }

    UUID getSubjectId() {
        return subjectId;
    }
}
