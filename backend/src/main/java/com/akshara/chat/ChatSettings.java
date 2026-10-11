package com.akshara.chat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

/** A school's message and PTM settings, made on first change. Schools without a row use the defaults. */
@Entity
@Table(schema = "chat", name = "settings")
class ChatSettings extends AssignedIdEntity {

    /** Monday to Saturday. */
    static final int DEFAULT_DAYS = 63;
    static final LocalTime DEFAULT_FROM = LocalTime.of(8, 0);
    static final LocalTime DEFAULT_TO = LocalTime.of(18, 0);

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private int availableDays;

    @Column(nullable = false)
    private LocalTime availableFrom;

    @Column(nullable = false)
    private LocalTime availableTo;

    @Column(nullable = false)
    private boolean notifyNewMessages;

    @Column(nullable = false)
    private boolean ptmRemindersEnabled;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ChatSettings() {
    }

    ChatSettings(Instant at) {
        this.id = Ids.newId();
        this.availableDays = DEFAULT_DAYS;
        this.availableFrom = DEFAULT_FROM;
        this.availableTo = DEFAULT_TO;
        this.notifyNewMessages = true;
        this.ptmRemindersEnabled = false;
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void availability(Collection<DayOfWeek> days, LocalTime from, LocalTime to, boolean notify) {
        this.availableDays = toBits(days);
        this.availableFrom = from;
        this.availableTo = to;
        this.notifyNewMessages = notify;
    }

    void ptmReminders(boolean enabled) {
        this.ptmRemindersEnabled = enabled;
    }

    static int toBits(Collection<DayOfWeek> days) {
        int bits = 0;
        for (DayOfWeek d : days) {
            bits |= 1 << (d.getValue() - 1);
        }
        return bits;
    }

    static List<DayOfWeek> fromBits(int bits) {
        List<DayOfWeek> days = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            if ((bits & (1 << (d.getValue() - 1))) != 0) {
                days.add(d);
            }
        }
        return days;
    }

    @Override
    public UUID getId() {
        return id;
    }

    List<DayOfWeek> getAvailableDays() {
        return fromBits(availableDays);
    }

    LocalTime getAvailableFrom() {
        return availableFrom;
    }

    LocalTime getAvailableTo() {
        return availableTo;
    }

    boolean isNotifyNewMessages() {
        return notifyNewMessages;
    }

    boolean isPtmRemindersEnabled() {
        return ptmRemindersEnabled;
    }
}
