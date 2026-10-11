package com.akshara.chat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A teacher meeting parents at a PTM, with the hours their slots are made from. */
@Entity
@Table(schema = "chat", name = "ptm_teacher")
class PtmTeacher extends AssignedIdEntity {

    enum Kind {
        CLASS_TEACHER, SUBJECT_TEACHER
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false)
    private UUID teacherUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    private String subjects;

    @Column(nullable = false)
    private LocalTime availableFrom;

    @Column(nullable = false)
    private LocalTime availableTo;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PtmTeacher() {
    }

    PtmTeacher(UUID eventId, UUID teacherUserId, Kind kind, String subjects, LocalTime from, LocalTime to,
            Instant at) {
        this.id = Ids.newId();
        this.eventId = eventId;
        this.teacherUserId = teacherUserId;
        this.kind = kind;
        this.subjects = subjects;
        this.availableFrom = from;
        this.availableTo = to;
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void describe(Kind kind, String subjects) {
        this.kind = kind;
        this.subjects = subjects;
    }

    void hours(LocalTime from, LocalTime to) {
        this.availableFrom = from;
        this.availableTo = to;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getEventId() {
        return eventId;
    }

    UUID getTeacherUserId() {
        return teacherUserId;
    }

    Kind getKind() {
        return kind;
    }

    String getSubjects() {
        return subjects;
    }

    LocalTime getAvailableFrom() {
        return availableFrom;
    }

    LocalTime getAvailableTo() {
        return availableTo;
    }
}
