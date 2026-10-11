package com.akshara.exams;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One subject paper of an exam for a class: its maximum marks (theory, practical and internal assessment) and its
 * slot on the date sheet. {@code calendarEntryId} is the school calendar entry made when the date sheet was published.
 */
@Entity
@Table(schema = "exams", name = "paper")
class Paper extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID examClassId;

    @Column(nullable = false, updatable = false)
    private UUID subjectId;

    @Column(nullable = false)
    private BigDecimal maxTheory;

    @Column(nullable = false)
    private BigDecimal maxPractical;

    @Column(nullable = false)
    private BigDecimal maxInternal;

    private LocalDate paperDate;

    private LocalTime startTime;

    private Integer durationMinutes;

    private String room;

    private UUID calendarEntryId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Paper() {
    }

    Paper(UUID examClassId, UUID subjectId) {
        this.id = Ids.newId();
        this.examClassId = examClassId;
        this.subjectId = subjectId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(BigDecimal maxTheory, BigDecimal maxPractical, BigDecimal maxInternal, LocalDate paperDate,
            LocalTime startTime, Integer durationMinutes, String room) {
        this.maxTheory = maxTheory;
        this.maxPractical = maxPractical;
        this.maxInternal = maxInternal;
        this.paperDate = paperDate;
        this.startTime = startTime;
        this.durationMinutes = durationMinutes;
        this.room = room;
    }

    void linkCalendar(UUID calendarEntryId) {
        this.calendarEntryId = calendarEntryId;
    }

    GradeMath.Max max() {
        return new GradeMath.Max(maxTheory, maxPractical, maxInternal);
    }

    /** When the paper ends, from its start time and duration; null without both. */
    LocalTime endTime() {
        return startTime == null || durationMinutes == null ? null : startTime.plusMinutes(durationMinutes);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getExamClassId() {
        return examClassId;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    BigDecimal getMaxTheory() {
        return maxTheory;
    }

    BigDecimal getMaxPractical() {
        return maxPractical;
    }

    BigDecimal getMaxInternal() {
        return maxInternal;
    }

    LocalDate getPaperDate() {
        return paperDate;
    }

    LocalTime getStartTime() {
        return startTime;
    }

    Integer getDurationMinutes() {
        return durationMinutes;
    }

    String getRoom() {
        return room;
    }

    UUID getCalendarEntryId() {
        return calendarEntryId;
    }
}
