package com.akshara.platform;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A Super Admin who runs the platform. Not tied to any school. */
@Entity
@Table(schema = "platform", name = "platform_admin")
public class PlatformAdmin extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String status;

    private Instant lastLoginAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected PlatformAdmin() {
    }

    public PlatformAdmin(String email, String name, String passwordHash) {
        this.id = Ids.newId();
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public void recordLogin() {
        lastLoginAt = Instant.now();
    }
}
