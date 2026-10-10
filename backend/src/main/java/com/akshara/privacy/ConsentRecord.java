package com.akshara.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One consent decision for one child and purpose, against one notice version: who gave, declined or withdrew it, when,
 * how (online or on paper) and who entered it. Never changed or deleted, so the history proves what was agreed.
 */
@Entity
@Immutable
@Table(schema = "privacy", name = "consent_record")
class ConsentRecord extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PrivacyPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentMethod method;

    @Column(nullable = false)
    private UUID noticeId;

    @Column(nullable = false)
    private int noticeVersion;

    /** The parent's sign-in for online decisions; null on paper. */
    private UUID givenById;

    @Column(nullable = false)
    private String givenByName;

    /** The staff member who entered a paper form. */
    private UUID recordedById;

    private String recordedByName;

    private String paperReference;

    private LocalDate signedOn;

    @Column(nullable = false)
    private Instant at;

    protected ConsentRecord() {
    }

    private ConsentRecord(UUID studentId, PrivacyPurpose purpose, ConsentAction action, ConsentMethod method,
            PrivacyNotice notice, UUID givenById, String givenByName, Instant at) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.purpose = purpose;
        this.action = action;
        this.method = method;
        this.noticeId = notice.getId();
        this.noticeVersion = notice.getNumber();
        this.givenById = givenById;
        this.givenByName = givenByName;
        this.at = at;
    }

    /** A parent's decision in the app. */
    static ConsentRecord online(UUID studentId, PrivacyPurpose purpose, ConsentAction action, PrivacyNotice notice,
            UUID parentId, String parentName, Instant at) {
        return new ConsentRecord(studentId, purpose, action, ConsentMethod.ONLINE, notice, parentId, parentName, at);
    }

    /** A signed paper form, entered by a staff member. */
    static ConsentRecord paper(UUID studentId, PrivacyPurpose purpose, ConsentAction action, PrivacyNotice notice,
            String signedBy, LocalDate signedOn, String reference, UUID staffId, String staffName, Instant at) {
        ConsentRecord r = new ConsentRecord(studentId, purpose, action, ConsentMethod.PAPER, notice, null, signedBy,
                at);
        r.signedOn = signedOn;
        r.paperReference = reference;
        r.recordedById = staffId;
        r.recordedByName = staffName;
        return r;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    PrivacyPurpose getPurpose() {
        return purpose;
    }

    ConsentAction getAction() {
        return action;
    }

    ConsentMethod getMethod() {
        return method;
    }

    int getNoticeVersion() {
        return noticeVersion;
    }

    UUID getGivenById() {
        return givenById;
    }

    String getGivenByName() {
        return givenByName;
    }

    String getRecordedByName() {
        return recordedByName;
    }

    String getPaperReference() {
        return paperReference;
    }

    LocalDate getSignedOn() {
        return signedOn;
    }

    Instant getAt() {
        return at;
    }
}
