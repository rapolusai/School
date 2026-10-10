package com.akshara.platform;

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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A school using the platform. Each school's data is isolated by its id. */
@Entity
@Table(schema = "platform", name = "tenant")
public class Tenant extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Board board;

    private String city;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    private Instant trialEndsAt;

    private String address;

    private String phone;

    private String contactEmail;

    private String udiseCode;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Tenant() {
    }

    public Tenant(String code, String name, Board board, String city, Plan plan, TenantStatus status,
            Instant trialEndsAt) {
        this.id = Ids.newId();
        this.code = code;
        this.name = name;
        this.board = board;
        this.city = city;
        this.plan = plan;
        this.status = status;
        this.trialEndsAt = trialEndsAt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
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

    public Board getBoard() {
        return board;
    }

    public String getCity() {
        return city;
    }

    public Plan getPlan() {
        return plan;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public Instant getTrialEndsAt() {
        return trialEndsAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getAddress() {
        return address;
    }

    public String getPhone() {
        return phone;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getUdiseCode() {
        return udiseCode;
    }

    /** Set by the Super Admin only (billing): converting a trial, marking past due, suspending, reactivating. */
    void changeStatus(TenantStatus status) {
        this.status = status;
    }

    /** Set by the Super Admin only, when a school starts paying or changes plan. */
    void changePlan(Plan plan) {
        this.plan = plan;
    }

    /** Contact details a school admin maintains. Name, code, board and plan are not changed here. */
    void updateProfile(String address, String phone, String contactEmail, String udiseCode) {
        this.address = address;
        this.phone = phone;
        this.contactEmail = contactEmail;
        this.udiseCode = udiseCode;
    }
}
