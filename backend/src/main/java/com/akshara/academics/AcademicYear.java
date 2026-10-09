package com.akshara.academics;

import java.time.Instant;
import java.time.LocalDate;
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

/** A school year such as "2026-27". Exactly one year per school is current. */
@Entity
@Table(schema = "academics", name = "academic_year")
class AcademicYear extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDate startsOn;

    @Column(nullable = false)
    private LocalDate endsOn;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AcademicYear() {
    }

    AcademicYear(String name, LocalDate startsOn, LocalDate endsOn) {
        this.id = Ids.newId();
        this.name = name;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, LocalDate startsOn, LocalDate endsOn) {
        this.name = name;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    void setCurrent(boolean current) {
        this.current = current;
    }

    boolean overlaps(LocalDate start, LocalDate end) {
        return !start.isAfter(endsOn) && !end.isBefore(startsOn);
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    LocalDate getStartsOn() {
        return startsOn;
    }

    LocalDate getEndsOn() {
        return endsOn;
    }

    boolean isCurrent() {
        return current;
    }
}
