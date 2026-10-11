package com.akshara.exams;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * The NEP 2020 holistic progress card of a student in a foundational or preparatory class for a term: a level per
 * domain and the teacher's, the student's own and the parent's reflections.
 */
@Entity
@Table(schema = "exams", name = "hpc_entry")
class HpcEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID termId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    private HpcLevel physical;

    @Enumerated(EnumType.STRING)
    private HpcLevel socioEmotional;

    @Enumerated(EnumType.STRING)
    private HpcLevel cognitive;

    @Enumerated(EnumType.STRING)
    private HpcLevel language;

    @Enumerated(EnumType.STRING)
    private HpcLevel numeracy;

    @Enumerated(EnumType.STRING)
    private HpcLevel aesthetic;

    private String teacherReflection;

    private String selfReflection;

    private String parentReflection;

    private String updatedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected HpcEntry() {
    }

    HpcEntry(UUID termId, UUID studentId, Instant at) {
        this.id = Ids.newId();
        this.termId = termId;
        this.studentId = studentId;
        this.createdAt = at;
        this.updatedAt = at;
    }

    /** Returns true when anything changed. */
    boolean set(ExamsViews.Hpc v, String by, Instant at) {
        boolean changed = physical != v.physical() || socioEmotional != v.socioEmotional()
                || cognitive != v.cognitive() || language != v.language() || numeracy != v.numeracy()
                || aesthetic != v.aesthetic() || !Objects.equals(teacherReflection, v.teacherReflection())
                || !Objects.equals(selfReflection, v.selfReflection())
                || !Objects.equals(parentReflection, v.parentReflection());
        if (changed) {
            physical = v.physical();
            socioEmotional = v.socioEmotional();
            cognitive = v.cognitive();
            language = v.language();
            numeracy = v.numeracy();
            aesthetic = v.aesthetic();
            teacherReflection = v.teacherReflection();
            selfReflection = v.selfReflection();
            parentReflection = v.parentReflection();
            updatedByName = by;
            updatedAt = at;
        }
        return changed;
    }

    void eraseReflections(Instant at) {
        teacherReflection = null;
        selfReflection = null;
        parentReflection = null;
        updatedAt = at;
    }

    ExamsViews.Hpc view() {
        return new ExamsViews.Hpc(physical, socioEmotional, cognitive, language, numeracy, aesthetic,
                teacherReflection, selfReflection, parentReflection);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTermId() {
        return termId;
    }

    UUID getStudentId() {
        return studentId;
    }
}
