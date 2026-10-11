package com.akshara.transport;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * An entry in a trip's boarding log: a student boarded, was dropped or was absent (the latest entry for a student is
 * their mark), or a delay was reported. Never changed or deleted.
 */
@Entity
@Immutable
@Table(schema = "transport", name = "trip_event")
class TripEvent extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID tripId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripEventKind kind;

    private UUID studentId;

    private UUID stopId;

    private String stopName;

    @Column(nullable = false)
    private Instant recordedAt;

    private Integer minutes;

    private String note;

    private UUID byId;

    private String byName;

    protected TripEvent() {
    }

    private TripEvent(UUID tripId, TripEventKind kind, UUID studentId, Stop stop, Integer minutes, String note,
            Actor actor, Instant at) {
        this.id = Ids.newId();
        this.tripId = tripId;
        this.kind = kind;
        this.studentId = studentId;
        this.stopId = stop == null ? null : stop.getId();
        this.stopName = stop == null ? null : stop.getName();
        this.minutes = minutes;
        this.note = note;
        this.byId = actor.id();
        this.byName = actor.name();
        this.recordedAt = at;
    }

    static TripEvent mark(UUID tripId, UUID studentId, BoardingStatus status, Stop stop, Actor actor, Instant at) {
        return new TripEvent(tripId, TripEventKind.of(status), studentId, stop, null, null, actor, at);
    }

    static TripEvent delay(UUID tripId, int minutes, String reason, Actor actor, Instant at) {
        return new TripEvent(tripId, TripEventKind.DELAYED, null, null, minutes, reason, actor, at);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTripId() {
        return tripId;
    }

    TripEventKind getKind() {
        return kind;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getStopId() {
        return stopId;
    }

    String getStopName() {
        return stopName;
    }

    Instant getRecordedAt() {
        return recordedAt;
    }

    Integer getMinutes() {
        return minutes;
    }

    String getNote() {
        return note;
    }

    String getByName() {
        return byName;
    }
}
