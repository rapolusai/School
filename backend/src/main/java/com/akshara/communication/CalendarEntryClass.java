package com.akshara.communication;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A class whose families see a calendar entry meant for some classes only. */
@Entity
@Table(schema = "communication", name = "calendar_entry_class")
class CalendarEntryClass extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID entryId;

    @Column(nullable = false, updatable = false)
    private UUID classId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected CalendarEntryClass() {
    }

    CalendarEntryClass(UUID entryId, UUID classId) {
        this.id = Ids.newId();
        this.entryId = entryId;
        this.classId = classId;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getEntryId() {
        return entryId;
    }

    UUID getClassId() {
        return classId;
    }
}
