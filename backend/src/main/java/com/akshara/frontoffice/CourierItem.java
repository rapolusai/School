package com.akshara.frontoffice;

import java.time.Instant;
import java.time.LocalDate;
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

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One entry in the postal and courier register: an inward or outward item and who signed for it (typed name). */
@Entity
@Table(schema = "frontoffice", name = "courier_item")
class CourierItem extends AssignedIdEntity {

    record Details(CourierDirection direction, String item, String sender, String receiver, LocalDate itemDate,
            String carrier, String trackingNo, String receivedBy, String note) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourierDirection direction;

    @Column(nullable = false)
    private String item;

    @Column(nullable = false)
    private String sender;

    @Column(nullable = false)
    private String receiver;

    @Column(nullable = false)
    private LocalDate itemDate;

    private String carrier;

    private String trackingNo;

    private String receivedBy;

    private String note;

    @Column(updatable = false)
    private String loggedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CourierItem() {
    }

    CourierItem(Details d, Actor by, Instant now) {
        this.id = Ids.newId();
        apply(d);
        this.loggedByName = by.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.direction = d.direction();
        this.item = d.item();
        this.sender = d.sender();
        this.receiver = d.receiver();
        this.itemDate = d.itemDate();
        this.carrier = d.carrier();
        this.trackingNo = d.trackingNo();
        this.receivedBy = d.receivedBy();
        this.note = d.note();
    }

    @Override
    public UUID getId() {
        return id;
    }

    CourierDirection getDirection() {
        return direction;
    }

    String getItem() {
        return item;
    }

    String getSender() {
        return sender;
    }

    String getReceiver() {
        return receiver;
    }

    LocalDate getItemDate() {
        return itemDate;
    }

    String getCarrier() {
        return carrier;
    }

    String getTrackingNo() {
        return trackingNo;
    }

    String getReceivedBy() {
        return receivedBy;
    }

    String getNote() {
        return note;
    }

    String getLoggedByName() {
        return loggedByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
