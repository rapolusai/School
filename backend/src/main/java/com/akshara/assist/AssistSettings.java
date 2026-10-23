package com.akshara.assist;

import java.time.Instant;
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
 * The school's assistance settings: on or off (off for a new school), which features are on, the monthly draft
 * limit, the language of drafts and the early warning weights. One row per school, created on first save.
 */
@Entity
@Table(schema = "assist", name = "settings")
class AssistSettings extends AssignedIdEntity {

    /** The weights of the early warning score, as points each signal can add (0 to 100). */
    record Weights(int attendance30, int attendance90, int marksTrend, int feeDues, int homework, int threshold) {

        static final Weights DEFAULTS = new Weights(25, 15, 25, 10, 25, 30);
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private boolean remarksEnabled = true;

    @Column(nullable = false)
    private boolean earlyWarningEnabled = true;

    @Column(nullable = false)
    private boolean papersEnabled = true;

    @Column(nullable = false)
    private boolean writingEnabled = true;

    @Column(nullable = false)
    private int monthlyLimit = 500;

    @Column(nullable = false, length = 2)
    private String language = "en";

    @Column(name = "weight_attendance_30", nullable = false)
    private int weightAttendance30 = 25;

    @Column(name = "weight_attendance_90", nullable = false)
    private int weightAttendance90 = 15;

    @Column(nullable = false)
    private int weightMarksTrend = 25;

    @Column(nullable = false)
    private int weightFeeDues = 10;

    @Column(nullable = false)
    private int weightHomework = 25;

    @Column(nullable = false)
    private int watchThreshold = 30;

    private Instant lastRunAt;

    private Integer lastRunStudents;

    private Integer lastRunListed;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AssistSettings() {
    }

    AssistSettings(boolean enabled) {
        this.id = Ids.newId();
        this.enabled = enabled;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void update(boolean enabled, boolean remarks, boolean earlyWarning, boolean papers, boolean writing,
            int monthlyLimit, String language, Weights weights) {
        this.enabled = enabled;
        this.remarksEnabled = remarks;
        this.earlyWarningEnabled = earlyWarning;
        this.papersEnabled = papers;
        this.writingEnabled = writing;
        this.monthlyLimit = monthlyLimit;
        this.language = language;
        this.weightAttendance30 = weights.attendance30();
        this.weightAttendance90 = weights.attendance90();
        this.weightMarksTrend = weights.marksTrend();
        this.weightFeeDues = weights.feeDues();
        this.weightHomework = weights.homework();
        this.watchThreshold = weights.threshold();
    }

    void recordRun(Instant at, int students, int listed) {
        this.lastRunAt = at;
        this.lastRunStudents = students;
        this.lastRunListed = listed;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean isRemarksEnabled() {
        return remarksEnabled;
    }

    boolean isEarlyWarningEnabled() {
        return earlyWarningEnabled;
    }

    boolean isPapersEnabled() {
        return papersEnabled;
    }

    boolean isWritingEnabled() {
        return writingEnabled;
    }

    int getMonthlyLimit() {
        return monthlyLimit;
    }

    String getLanguage() {
        return language;
    }

    Weights weights() {
        return new Weights(weightAttendance30, weightAttendance90, weightMarksTrend, weightFeeDues, weightHomework,
                watchThreshold);
    }

    Instant getLastRunAt() {
        return lastRunAt;
    }

    Integer getLastRunStudents() {
        return lastRunStudents;
    }

    Integer getLastRunListed() {
        return lastRunListed;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
