package com.akshara.communication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
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
import com.akshara.communication.CommunicationTypes.CalendarAudience;
import com.akshara.communication.CommunicationTypes.EntryKind;
import com.akshara.communication.CommunicationTypes.ReminderChannel;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A holiday, event, exam, PTM or other entry of the school calendar on a day or a range of days. */
@Entity
@Table(schema = "communication", name = "calendar_entry")
class CalendarEntry extends AssignedIdEntity {

    /** The editable fields of an entry. */
    record Details(EntryKind kind, String title, String description, LocalDate startsOn, LocalDate endsOn,
            LocalTime startTime, LocalTime endTime, CalendarAudience audience, Integer reminderDays,
            Set<ReminderChannel> reminderChannels, UUID academicYearId) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    private UUID academicYearId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryKind kind;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(nullable = false)
    private LocalDate startsOn;

    @Column(nullable = false)
    private LocalDate endsOn;

    private LocalTime startTime;

    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CalendarAudience audience;

    private Integer reminderDays;

    @Column(nullable = false)
    private boolean remindSms;

    @Column(nullable = false)
    private boolean remindWhatsapp;

    private Instant reminderSentAt;

    private UUID createdById;

    private String createdByName;

    private String updatedByName;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CalendarEntry() {
    }

    CalendarEntry(Details details, Actor author) {
        this.id = Ids.newId();
        this.createdById = author.id();
        this.createdByName = author.name();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        apply(details);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Changes the entry. A reminder that was already sent is sent again when the start date or the lead time moves. */
    void update(Details d, Actor by) {
        boolean reminderMoved = !d.startsOn().equals(startsOn) || !Objects.equals(d.reminderDays(),
                reminderDays);
        apply(d);
        this.updatedByName = by.name();
        if (reminderMoved) {
            reminderSentAt = null;
        }
    }

    private void apply(Details d) {
        this.kind = d.kind();
        this.title = d.title();
        this.description = d.description();
        this.startsOn = d.startsOn();
        this.endsOn = d.endsOn();
        this.startTime = d.startTime();
        this.endTime = d.endTime();
        this.audience = d.audience();
        this.reminderDays = d.reminderDays();
        this.remindSms = d.reminderDays() != null && d.reminderChannels().contains(ReminderChannel.SMS);
        this.remindWhatsapp = d.reminderDays() != null && d.reminderChannels().contains(ReminderChannel.WHATSAPP);
        this.academicYearId = d.academicYearId();
    }

    void reminderSent(Instant at) {
        this.reminderSentAt = at;
    }

    boolean covers(LocalDate date) {
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }

    Set<ReminderChannel> reminderChannels() {
        Set<ReminderChannel> channels = EnumSet.noneOf(ReminderChannel.class);
        if (remindSms) {
            channels.add(ReminderChannel.SMS);
        }
        if (remindWhatsapp) {
            channels.add(ReminderChannel.WHATSAPP);
        }
        return channels;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    EntryKind getKind() {
        return kind;
    }

    String getTitle() {
        return title;
    }

    String getDescription() {
        return description;
    }

    LocalDate getStartsOn() {
        return startsOn;
    }

    LocalDate getEndsOn() {
        return endsOn;
    }

    LocalTime getStartTime() {
        return startTime;
    }

    LocalTime getEndTime() {
        return endTime;
    }

    CalendarAudience getAudience() {
        return audience;
    }

    Integer getReminderDays() {
        return reminderDays;
    }

    Instant getReminderSentAt() {
        return reminderSentAt;
    }

    String getCreatedByName() {
        return createdByName;
    }

    String getUpdatedByName() {
        return updatedByName;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
