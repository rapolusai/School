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

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A certificate for a student or a member of staff. Requested, prepared (its placeholder values kept as a JSON
 * object, editable), signed and so issued (serial number, frozen wording, verification token), or cancelled with a
 * reason. A correction cancels it and makes the next version, which points back here. Never deleted.
 */
@Entity
@Table(schema = "certificates", name = "certificate")
class Certificate extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cert_type", nullable = false, updatable = false)
    private CertificateType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CertificateStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SubjectType subjectType;

    @Column(updatable = false)
    private UUID studentId;

    @Column(updatable = false)
    private UUID staffUserId;

    @Column(nullable = false)
    private String title;

    private String purpose;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String fields;

    private String body;

    private String serialNo;

    private String academicYear;

    @Column(nullable = false, updatable = false)
    private int versionNo;

    @Column(updatable = false)
    private UUID supersedesId;

    private String verifyToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RequestedVia requestedVia;

    @Column(updatable = false)
    private UUID requestedById;

    @Column(updatable = false)
    private String requestedByName;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    private UUID preparedById;

    private String preparedByName;

    private Instant preparedAt;

    private UUID signedById;

    private String signedByName;

    private Instant signedAt;

    private LocalDate issuedOn;

    private UUID cancelledById;

    private String cancelledByName;

    private Instant cancelledAt;

    private String cancelReason;

    private Long duesPaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Certificate() {
    }

    Certificate(CertificateType type, CertificateStatus status, SubjectType subjectType, UUID studentId,
            UUID staffUserId, String title, String purpose, String fieldsJson, RequestedVia via, Actor by,
            int versionNo, UUID supersedesId, Instant at) {
        this.id = Ids.newId();
        this.type = type;
        this.status = status;
        this.subjectType = subjectType;
        this.studentId = studentId;
        this.staffUserId = staffUserId;
        this.title = title;
        this.purpose = purpose;
        this.fields = fieldsJson;
        this.requestedVia = via;
        this.requestedById = by.id();
        this.requestedByName = by.name();
        this.requestedAt = at;
        this.versionNo = versionNo;
        this.supersedesId = supersedesId;
        this.createdAt = at;
        this.updatedAt = at;
        if (status == CertificateStatus.PREPARED) {
            prepared(by, at);
        }
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void prepared(Actor by, Instant at) {
        this.status = CertificateStatus.PREPARED;
        this.preparedById = by.id();
        this.preparedByName = by.name();
        this.preparedAt = at;
    }

    void edit(String title, String purpose, String fieldsJson, Long duesPaise) {
        this.title = title;
        this.purpose = purpose;
        this.fields = fieldsJson;
        this.duesPaise = duesPaise;
    }

    void signed(String serialNo, String academicYear, String body, String token, Actor by, Instant at,
            LocalDate issuedOn) {
        this.status = CertificateStatus.ISSUED;
        this.serialNo = serialNo;
        this.academicYear = academicYear;
        this.body = body;
        this.verifyToken = token;
        this.signedById = by.id();
        this.signedByName = by.name();
        this.signedAt = at;
        this.issuedOn = issuedOn;
    }

    void cancelled(String reason, Actor by, Instant at) {
        this.status = CertificateStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledById = by.id();
        this.cancelledByName = by.name();
        this.cancelledAt = at;
    }

    boolean isEditable() {
        return status == CertificateStatus.REQUESTED || status == CertificateStatus.PREPARED;
    }

    UUID subjectId() {
        return subjectType == SubjectType.STUDENT ? studentId : staffUserId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    CertificateType getType() {
        return type;
    }

    CertificateStatus getStatus() {
        return status;
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

    String getTitle() {
        return title;
    }

    String getPurpose() {
        return purpose;
    }

    String getFields() {
        return fields;
    }

    String getBody() {
        return body;
    }

    String getSerialNo() {
        return serialNo;
    }

    String getAcademicYear() {
        return academicYear;
    }

    int getVersionNo() {
        return versionNo;
    }

    UUID getSupersedesId() {
        return supersedesId;
    }

    String getVerifyToken() {
        return verifyToken;
    }

    RequestedVia getRequestedVia() {
        return requestedVia;
    }

    String getRequestedByName() {
        return requestedByName;
    }

    UUID getRequestedById() {
        return requestedById;
    }

    Instant getRequestedAt() {
        return requestedAt;
    }

    String getPreparedByName() {
        return preparedByName;
    }

    Instant getPreparedAt() {
        return preparedAt;
    }

    String getSignedByName() {
        return signedByName;
    }

    Instant getSignedAt() {
        return signedAt;
    }

    LocalDate getIssuedOn() {
        return issuedOn;
    }

    String getCancelledByName() {
        return cancelledByName;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    String getCancelReason() {
        return cancelReason;
    }

    Long getDuesPaise() {
        return duesPaise;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
