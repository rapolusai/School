package com.akshara.certificates;

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

/**
 * One ID card in the register, for a student or a member of staff: its number, issue number (2 for the first
 * reissue), what the card shows that is kept only here (blood group, emergency number, validity) and, for a
 * reissued card, why and the fee noted for a lost card. Never deleted.
 */
@Entity
@Table(schema = "certificates", name = "id_card")
class IdCard extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SubjectType subjectType;

    @Column(updatable = false)
    private UUID studentId;

    @Column(updatable = false)
    private UUID staffUserId;

    @Column(nullable = false, updatable = false)
    private String cardNo;

    @Column(nullable = false, updatable = false)
    private int issueNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardStatus status;

    @Column(updatable = false)
    private String bloodGroup;

    @Column(updatable = false)
    private String emergencyPhone;

    @Column(nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(nullable = false, updatable = false)
    private LocalDate validUntil;

    @Column(updatable = false)
    private UUID replacesId;

    @Column(updatable = false)
    private String reissueReason;

    @Column(updatable = false)
    private Long lostFeePaise;

    @Column(updatable = false)
    private String feeNote;

    @Column(updatable = false)
    private UUID issuedById;

    @Column(updatable = false)
    private String issuedByName;

    private Instant cancelledAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected IdCard() {
    }

    IdCard(SubjectType subjectType, UUID studentId, UUID staffUserId, String cardNo, int issueNo, String bloodGroup,
            String emergencyPhone, LocalDate validFrom, LocalDate validUntil, UUID replacesId, String reissueReason,
            Long lostFeePaise, String feeNote, Actor by, Instant at) {
        this.id = Ids.newId();
        this.subjectType = subjectType;
        this.studentId = studentId;
        this.staffUserId = staffUserId;
        this.cardNo = cardNo;
        this.issueNo = issueNo;
        this.status = CardStatus.ACTIVE;
        this.bloodGroup = bloodGroup;
        this.emergencyPhone = emergencyPhone;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.replacesId = replacesId;
        this.reissueReason = reissueReason;
        this.lostFeePaise = lostFeePaise;
        this.feeNote = feeNote;
        this.issuedById = by.id();
        this.issuedByName = by.name();
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void cancelled(Instant at) {
        this.status = CardStatus.CANCELLED;
        this.cancelledAt = at;
    }

    UUID subjectId() {
        return subjectType == SubjectType.STUDENT ? studentId : staffUserId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    SubjectType getSubjectType() {
        return subjectType;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getStaffUserId() {
        return staffUserId;
    }

    String getCardNo() {
        return cardNo;
    }

    int getIssueNo() {
        return issueNo;
    }

    CardStatus getStatus() {
        return status;
    }

    String getBloodGroup() {
        return bloodGroup;
    }

    String getEmergencyPhone() {
        return emergencyPhone;
    }

    LocalDate getValidFrom() {
        return validFrom;
    }

    LocalDate getValidUntil() {
        return validUntil;
    }

    UUID getReplacesId() {
        return replacesId;
    }

    String getReissueReason() {
        return reissueReason;
    }

    Long getLostFeePaise() {
        return lostFeePaise;
    }

    String getFeeNote() {
        return feeNote;
    }

    String getIssuedByName() {
        return issuedByName;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
