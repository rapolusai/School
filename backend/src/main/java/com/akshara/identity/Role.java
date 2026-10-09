package com.akshara.identity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A named bundle of permissions inside one school. */
@Entity
@Table(schema = "identity", name = "role")
public class Role extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> permissions = new ArrayList<>();

    @Column(name = "system_role", nullable = false)
    private boolean systemRole;

    @Column(nullable = false)
    private Instant createdAt;

    protected Role() {
    }

    public Role(String code, String name, List<String> permissions, boolean systemRole) {
        this.id = Ids.newId();
        this.code = code;
        this.name = name;
        this.permissions = new ArrayList<>(permissions);
        this.systemRole = systemRole;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public List<String> getPermissions() {
        return List.copyOf(permissions);
    }
}
