package com.akshara.assist;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One call to a provider: who, which feature, which provider, a token estimate and a hash of the request. */
@Entity
@Table(schema = "assist", name = "usage")
class UsageEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AssistFeature feature;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(nullable = false, updatable = false)
    private int tokenEstimate;

    @Column(nullable = false, updatable = false)
    private String requestHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected UsageEntry() {
    }

    UsageEntry(UUID userId, String userName, AssistFeature feature, String provider, int tokenEstimate,
            String requestHash, Instant at) {
        this.id = Ids.newId();
        this.userId = userId;
        this.userName = userName;
        this.feature = feature;
        this.provider = provider;
        this.tokenEstimate = tokenEstimate;
        this.requestHash = requestHash;
        this.createdAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    String getUserName() {
        return userName;
    }

    AssistFeature getFeature() {
        return feature;
    }

    String getProvider() {
        return provider;
    }

    int getTokenEstimate() {
        return tokenEstimate;
    }

    String getRequestHash() {
        return requestHash;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
