package com.akshara.timetable;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A school's working days and whether Saturdays follow their own bell schedule. */
@Entity
@Table(schema = "timetable", name = "settings")
class TimetableSettings extends AssignedIdEntity {

    static final Set<DayOfWeek> DEFAULT_DAYS = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY);

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String workingDays;

    @Column(nullable = false)
    private boolean saturdaySchedule;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TimetableSettings() {
    }

    TimetableSettings(Collection<DayOfWeek> workingDays, boolean saturdaySchedule) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(workingDays, saturdaySchedule);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(Collection<DayOfWeek> days, boolean saturdaySchedule) {
        this.workingDays = encode(days);
        this.saturdaySchedule = saturdaySchedule;
    }

    @Override
    public UUID getId() {
        return id;
    }

    List<DayOfWeek> getWorkingDays() {
        return decode(workingDays);
    }

    boolean isSaturdaySchedule() {
        return saturdaySchedule;
    }

    /** "MON,TUE,…" in week order. */
    static String encode(Collection<DayOfWeek> days) {
        return EnumSet.copyOf(days).stream()
                .map(d -> d.name().substring(0, 3))
                .collect(Collectors.joining(","));
    }

    static List<DayOfWeek> decode(String value) {
        return Arrays.stream(value.split(","))
                .map(s -> Arrays.stream(DayOfWeek.values())
                        .filter(d -> d.name().startsWith(s.trim().toUpperCase(Locale.ROOT)))
                        .findFirst().orElseThrow())
                .sorted()
                .toList();
    }
}
