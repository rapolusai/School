package com.akshara.lessons;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A topic of a syllabus unit, in order: the periods planned for it and the month it should be taught by
 * ({@code targetMonth} is the first day of that month).
 */
@Entity
@Table(schema = "lessons", name = "syllabus_topic")
class SyllabusTopic extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID syllabusId;

    @Column(nullable = false)
    private UUID unitId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int plannedPeriods;

    private LocalDate targetMonth;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SyllabusTopic() {
    }

    SyllabusTopic(UUID syllabusId, UUID unitId, int position, String title, int plannedPeriods,
            LocalDate targetMonth) {
        this.id = Ids.newId();
        this.syllabusId = syllabusId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(unitId, position, title, plannedPeriods, targetMonth);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(UUID unitId, int position, String title, int plannedPeriods, LocalDate targetMonth) {
        this.unitId = unitId;
        this.position = position;
        this.title = title;
        this.plannedPeriods = plannedPeriods;
        this.targetMonth = targetMonth == null ? null : targetMonth.withDayOfMonth(1);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getSyllabusId() {
        return syllabusId;
    }

    UUID getUnitId() {
        return unitId;
    }

    int getPosition() {
        return position;
    }

    String getTitle() {
        return title;
    }

    int getPlannedPeriods() {
        return plannedPeriods;
    }

    LocalDate getTargetMonth() {
        return targetMonth;
    }
}
