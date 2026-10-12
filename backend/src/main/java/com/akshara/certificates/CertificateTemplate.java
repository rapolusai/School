package com.akshara.certificates;

import java.time.Instant;
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

/** A school's wording for one certificate type: the title, the body with placeholders and the serial prefix. */
@Entity
@Table(schema = "certificates", name = "template")
class CertificateTemplate extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cert_type", nullable = false, updatable = false)
    private CertificateType type;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private String serialPrefix;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CertificateTemplate() {
    }

    CertificateTemplate(CertificateType type, String title, String body, String serialPrefix) {
        this.id = Ids.newId();
        this.type = type;
        this.title = title;
        this.body = body;
        this.serialPrefix = serialPrefix;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String title, String body, String serialPrefix) {
        this.title = title;
        this.body = body;
        this.serialPrefix = serialPrefix;
    }

    @Override
    public UUID getId() {
        return id;
    }

    CertificateType getType() {
        return type;
    }

    String getTitle() {
        return title;
    }

    String getBody() {
        return body;
    }

    String getSerialPrefix() {
        return serialPrefix;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
