package com.akshara.communication;

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

import com.akshara.communication.CommunicationTypes.AckChannel;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A school's communication settings. Schools without a row use {@link Values#DEFAULTS}. */
@Entity
@Table(schema = "communication", name = "settings")
class CommunicationSettings extends AssignedIdEntity {

    /**
     * Whether circulars from people without notices.approve wait for approval (default yes), and whether and how
     * families who enquire through the public admissions form are thanked (default: yes, by SMS).
     */
    record Values(boolean teacherCircularsNeedApproval, boolean enquiryAckEnabled, AckChannel enquiryAckChannel) {

        static final Values DEFAULTS = new Values(true, true, AckChannel.SMS);
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private boolean teacherCircularsNeedApproval;

    @Column(nullable = false)
    private boolean enquiryAckEnabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AckChannel enquiryAckChannel;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CommunicationSettings() {
    }

    CommunicationSettings(Values values) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        apply(values);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Values v) {
        this.teacherCircularsNeedApproval = v.teacherCircularsNeedApproval();
        this.enquiryAckEnabled = v.enquiryAckEnabled();
        this.enquiryAckChannel = v.enquiryAckChannel();
    }

    Values values() {
        return new Values(teacherCircularsNeedApproval, enquiryAckEnabled, enquiryAckChannel);
    }

    @Override
    public UUID getId() {
        return id;
    }
}
