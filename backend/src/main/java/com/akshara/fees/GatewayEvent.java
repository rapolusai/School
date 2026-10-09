package com.akshara.fees;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A verified webhook delivery from a payment gateway and what it led to. Unique per event id. */
@Entity
@Immutable
@Table(schema = "fees", name = "gateway_event")
class GatewayEvent extends AssignedIdEntity {

    static final String RECORDED = "RECORDED";
    static final String ALREADY_RECORDED = "ALREADY_RECORDED";
    static final String FAILED = "FAILED";
    static final String IGNORED = "IGNORED";

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String gateway;

    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private UUID paymentOrderId;

    private String gatewayPaymentId;

    @Column(nullable = false)
    private String outcome;

    @Column(nullable = false)
    private Instant receivedAt;

    protected GatewayEvent() {
    }

    GatewayEvent(String gateway, String eventId, String eventType, UUID paymentOrderId, String gatewayPaymentId,
            String outcome) {
        this.id = Ids.newId();
        this.gateway = gateway;
        this.eventId = eventId;
        this.eventType = eventType;
        this.paymentOrderId = paymentOrderId;
        this.gatewayPaymentId = gatewayPaymentId;
        this.outcome = outcome;
        this.receivedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getOutcome() {
        return outcome;
    }
}
