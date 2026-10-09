package com.akshara.timetable;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One cell of a section's weekly timetable: on this day, in this period, this subject with this teacher. */
@Entity
@Table(schema = "timetable", name = "slot")
class Slot extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, updatable = false)
    private UUID sectionId;

    @Column(nullable = false, updatable = false)
    private int dayOfWeek;

    @Column(nullable = false, updatable = false)
    private int periodNo;

    @Column(nullable = false)
    private UUID subjectId;

    private UUID teacherId;

    private String room;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Slot() {
    }

    Slot(UUID academicYearId, UUID sectionId, DayOfWeek day, int periodNo, UUID subjectId, UUID teacherId,
            String room) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.sectionId = sectionId;
        this.dayOfWeek = day.getValue();
        this.periodNo = periodNo;
        this.subjectId = subjectId;
        this.teacherId = teacherId;
        this.room = room;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Returns true when something changed. */
    boolean change(UUID subjectId, UUID teacherId, String room) {
        if (Objects.equals(this.subjectId, subjectId) && Objects.equals(this.teacherId, teacherId)
                && Objects.equals(this.room, room)) {
            return false;
        }
        this.subjectId = subjectId;
        this.teacherId = teacherId;
        this.room = room;
        return true;
    }

    void reassign(UUID teacherId) {
        this.teacherId = teacherId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    UUID getSectionId() {
        return sectionId;
    }

    DayOfWeek getDay() {
        return DayOfWeek.of(dayOfWeek);
    }

    int getPeriodNo() {
        return periodNo;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    UUID getTeacherId() {
        return teacherId;
    }

    String getRoom() {
        return room;
    }
}
