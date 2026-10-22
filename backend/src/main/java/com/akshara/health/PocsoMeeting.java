package com.akshara.health;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** The minutes of one POCSO committee meeting, as text. */
@Entity
@Immutable
@Table(schema = "health", name = "pocso_meeting")
class PocsoMeeting extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private LocalDate meetingDate;

    @Column(nullable = false)
    private String minutes;

    private String recordedByName;

    @Column(nullable = false)
    private Instant createdAt;

    protected PocsoMeeting() {
    }

    PocsoMeeting(LocalDate meetingDate, String minutes, String recordedByName, Instant now) {
        this.id = Ids.newId();
        this.meetingDate = meetingDate;
        this.minutes = minutes;
        this.recordedByName = recordedByName;
        this.createdAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    LocalDate getMeetingDate() {
        return meetingDate;
    }

    String getMinutes() {
        return minutes;
    }

    String getRecordedByName() {
        return recordedByName;
    }
}
