package com.akshara.timetable;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One row of a bell schedule: a teaching period (numbered 1, 2, 3… in time order) or a break (no number). The
 * schedule is replaced as a whole when the school changes it.
 */
@Entity
@Table(schema = "timetable", name = "period")
class Period extends AssignedIdEntity {

    enum Schedule {
        WEEKDAY, SATURDAY
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Schedule schedule;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(updatable = false)
    private Integer number;

    @Column(nullable = false, updatable = false)
    private String label;

    @Column(nullable = false, updatable = false)
    private LocalTime startsAt;

    @Column(nullable = false, updatable = false)
    private LocalTime endsAt;

    @Column(nullable = false, updatable = false)
    private boolean isBreak;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Period() {
    }

    Period(Schedule schedule, int position, Integer number, String label, LocalTime startsAt, LocalTime endsAt,
            boolean isBreak) {
        this.id = Ids.newId();
        this.schedule = schedule;
        this.position = position;
        this.number = number;
        this.label = label;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.isBreak = isBreak;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Schedule getSchedule() {
        return schedule;
    }

    int getPosition() {
        return position;
    }

    Integer getNumber() {
        return number;
    }

    String getLabel() {
        return label;
    }

    LocalTime getStartsAt() {
        return startsAt;
    }

    LocalTime getEndsAt() {
        return endsAt;
    }

    boolean isBreak() {
        return isBreak;
    }
}
