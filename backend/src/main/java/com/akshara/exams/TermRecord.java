package com.akshara.exams;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A student's co-scholastic grades for a term (work education, art education, health and physical education,
 * discipline) and the class teacher's remark.
 */
@Entity
@Table(schema = "exams", name = "term_record")
class TermRecord extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID termId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    private String workEducation;

    private String artEducation;

    private String healthPhysical;

    private String discipline;

    private String remark;

    private String updatedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TermRecord() {
    }

    TermRecord(UUID termId, UUID studentId, Instant at) {
        this.id = Ids.newId();
        this.termId = termId;
        this.studentId = studentId;
        this.createdAt = at;
        this.updatedAt = at;
    }

    /** Returns true when anything changed. */
    boolean set(String workEducation, String artEducation, String healthPhysical, String discipline, String remark,
            String by, Instant at) {
        boolean changed = !Objects.equals(this.workEducation, workEducation)
                || !Objects.equals(this.artEducation, artEducation)
                || !Objects.equals(this.healthPhysical, healthPhysical)
                || !Objects.equals(this.discipline, discipline) || !Objects.equals(this.remark, remark);
        if (changed) {
            this.workEducation = workEducation;
            this.artEducation = artEducation;
            this.healthPhysical = healthPhysical;
            this.discipline = discipline;
            this.remark = remark;
            this.updatedByName = by;
            this.updatedAt = at;
        }
        return changed;
    }

    void eraseRemark(Instant at) {
        this.remark = null;
        this.updatedAt = at;
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

    String getWorkEducation() {
        return workEducation;
    }

    String getArtEducation() {
        return artEducation;
    }

    String getHealthPhysical() {
        return healthPhysical;
    }

    String getDiscipline() {
        return discipline;
    }

    String getRemark() {
        return remark;
    }
}
