package com.akshara.preferences;

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

/** The language one person of the school chose for the app and for their messages. */
@Entity
@Table(schema = "preferences", name = "user_language")
class UserLanguage extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_account_id", nullable = false, updatable = false)
    private UUID userAccountId;

    @Column(nullable = false)
    private String language;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected UserLanguage() {
    }

    UserLanguage(UUID userAccountId, String language) {
        this.id = Ids.newId();
        this.userAccountId = userAccountId;
        this.language = language;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void change(String language) {
        this.language = language;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserAccountId() {
        return userAccountId;
    }

    String getLanguage() {
        return language;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
