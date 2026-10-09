package com.akshara.students;

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

/** A parent or guardian. One record per person, shared by siblings, so brothers and sisters are found through it. */
@Entity
@Table(schema = "students", name = "guardian")
class Guardian extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GuardianRelation relation;

    /** Ten-digit Indian mobile number without +91. */
    @Column(nullable = false)
    private String phone;

    private String email;

    private String occupation;

    private UUID userAccountId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Guardian() {
    }

    Guardian(String name, GuardianRelation relation, String phone, String email, String occupation) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(name, relation, phone, email, occupation);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, GuardianRelation relation, String phone, String email, String occupation) {
        this.name = name;
        this.relation = relation;
        this.phone = phone;
        this.email = email;
        this.occupation = occupation;
    }

    /** Fills in optional details that an existing record is missing, without overwriting anything. */
    void fillGaps(String email, String occupation) {
        if (this.email == null && email != null) {
            this.email = email;
        }
        if (this.occupation == null && occupation != null) {
            this.occupation = occupation;
        }
    }

    void linkUser(UUID userId) {
        this.userAccountId = userId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    GuardianRelation getRelation() {
        return relation;
    }

    String getPhone() {
        return phone;
    }

    String getEmail() {
        return email;
    }

    String getOccupation() {
        return occupation;
    }

    UUID getUserAccountId() {
        return userAccountId;
    }
}
