package com.akshara.notifications;

import java.time.Instant;
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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A school's message settings. Schools without a row use {@link MessageSettings#DEFAULTS}. */
@Entity
@Table(schema = "notifications", name = "settings")
class NotificationSettings extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private boolean absenceAlertsEnabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertChannel absenceAlertChannel;

    @Column(nullable = false)
    private String alertLanguage;

    @Column(nullable = false)
    private boolean quietHoursEnabled;

    @Column(nullable = false)
    private LocalTime quietHoursStart;

    @Column(nullable = false)
    private LocalTime quietHoursEnd;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected NotificationSettings() {
    }

    NotificationSettings(MessageSettings values) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        apply(values);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(MessageSettings v) {
        this.absenceAlertsEnabled = v.absenceAlertsEnabled();
        this.absenceAlertChannel = v.absenceAlertChannel();
        this.alertLanguage = v.alertLanguage();
        this.quietHoursEnabled = v.quietHoursEnabled();
        this.quietHoursStart = v.quietHoursStart();
        this.quietHoursEnd = v.quietHoursEnd();
    }

    MessageSettings values() {
        return new MessageSettings(absenceAlertsEnabled, absenceAlertChannel, alertLanguage, quietHoursEnabled,
                quietHoursStart, quietHoursEnd);
    }

    @Override
    public UUID getId() {
        return id;
    }
}
