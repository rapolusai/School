package com.akshara.health;

import java.time.Instant;
import java.time.LocalDate;
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

/** One line of a medical record: an allergy (with its severity), a condition, a medication or a vaccination. */
@Entity
@Table(schema = "health", name = "record_item")
class RecordItem extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ItemKind kind;

    @Column(nullable = false)
    private String name;

    private String detail;

    @Enumerated(EnumType.STRING)
    private AllergySeverity severity;

    private LocalDate itemDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemSource source;

    private String addedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected RecordItem() {
    }

    RecordItem(UUID studentId, ItemKind kind, ItemSource source, String addedByName, Instant now) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.kind = kind;
        this.source = source;
        this.addedByName = addedByName;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(String name, String detail, AllergySeverity severity, LocalDate itemDate, ItemSource source,
            String byName) {
        this.name = name;
        this.detail = detail;
        this.severity = kind == ItemKind.ALLERGY ? severity : null;
        this.itemDate = itemDate;
        this.source = source;
        this.addedByName = byName;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    ItemKind getKind() {
        return kind;
    }

    String getName() {
        return name;
    }

    String getDetail() {
        return detail;
    }

    AllergySeverity getSeverity() {
        return severity;
    }

    LocalDate getItemDate() {
        return itemDate;
    }

    ItemSource getSource() {
        return source;
    }

    String getAddedByName() {
        return addedByName;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
