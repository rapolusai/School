package com.akshara.notifications;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A guardian's phone that must not get WhatsApp because WhatsApp consent for one of their children is not given. */
@Entity
@Table(schema = "notifications", name = "whatsapp_opt_out")
class WhatsAppOptOut extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private String address;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected WhatsAppOptOut() {
    }

    WhatsAppOptOut(UUID studentId, String address, Instant at) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.address = address;
        this.createdAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getAddress() {
        return address;
    }
}
