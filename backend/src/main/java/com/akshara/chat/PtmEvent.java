package com.akshara.chat;

import java.time.Instant;
import java.time.LocalDate;
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

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A parent-teacher meeting day for some sections, with its hours, slot length and breaks. */
@Entity
@Table(schema = "chat", name = "ptm_event")
class PtmEvent extends AssignedIdEntity {

    enum Status {
        SCHEDULED, CANCELLED
    }

    /** The parts of an event that can change after it is set up. Timing changes only while nobody has booked. */
    record Details(String title, LocalDate date, LocalTime startsAt, LocalTime endsAt, int slotMinutes, String breaks,
            MeetingMode mode, String location, String videoLink, boolean includeSubjectTeachers, int cutoffHours,
            String note) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private LocalDate meetingDate;

    @Column(nullable = false)
    private LocalTime startsAt;

    @Column(nullable = false)
    private LocalTime endsAt;

    @Column(nullable = false)
    private int slotMinutes;

    @Column(nullable = false)
    private String breaks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MeetingMode mode;

    private String location;

    private String videoLink;

    @Column(nullable = false)
    private boolean includeSubjectTeachers;

    @Column(nullable = false)
    private int cutoffHours;

    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private Instant cancelledAt;

    private String cancelledByName;

    private String cancelReason;

    @Column(updatable = false)
    private UUID createdById;

    @Column(updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PtmEvent() {
    }

    PtmEvent(UUID academicYearId, Details details, Actor by, Instant at) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.status = Status.SCHEDULED;
        this.createdById = by.id();
        this.createdByName = by.name();
        this.createdAt = at;
        this.updatedAt = at;
        apply(details);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.title = d.title();
        this.meetingDate = d.date();
        this.startsAt = d.startsAt();
        this.endsAt = d.endsAt();
        this.slotMinutes = d.slotMinutes();
        this.breaks = d.breaks();
        this.mode = d.mode();
        this.location = d.location();
        this.videoLink = d.videoLink();
        this.includeSubjectTeachers = d.includeSubjectTeachers();
        this.cutoffHours = d.cutoffHours();
        this.note = d.note();
    }

    Details details() {
        return new Details(title, meetingDate, startsAt, endsAt, slotMinutes, breaks, mode, location, videoLink,
                includeSubjectTeachers, cutoffHours, note);
    }

    void cancel(String byName, String reason, Instant at) {
        status = Status.CANCELLED;
        cancelledAt = at;
        cancelledByName = byName;
        cancelReason = reason;
    }

    boolean isCancelled() {
        return status == Status.CANCELLED;
    }

    /** When a slot starting at {@code start} on the meeting day begins, in India. */
    Instant at(LocalTime start) {
        return meetingDate.atTime(start).atZone(ChatRules.INDIA).toInstant();
    }

    /** Parents can book, cancel or change a slot starting at {@code start} until this moment. */
    Instant cutoffFor(LocalTime start) {
        return at(start).minusSeconds(cutoffHours * 3600L);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    String getTitle() {
        return title;
    }

    LocalDate getMeetingDate() {
        return meetingDate;
    }

    LocalTime getStartsAt() {
        return startsAt;
    }

    LocalTime getEndsAt() {
        return endsAt;
    }

    int getSlotMinutes() {
        return slotMinutes;
    }

    String getBreaks() {
        return breaks;
    }

    MeetingMode getMode() {
        return mode;
    }

    String getLocation() {
        return location;
    }

    String getVideoLink() {
        return videoLink;
    }

    boolean isIncludeSubjectTeachers() {
        return includeSubjectTeachers;
    }

    int getCutoffHours() {
        return cutoffHours;
    }

    String getNote() {
        return note;
    }

    Status getStatus() {
        return status;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    String getCancelledByName() {
        return cancelledByName;
    }

    String getCancelReason() {
        return cancelReason;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
