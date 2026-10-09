package com.akshara.admissions;

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

import com.akshara.admissions.AdmissionTypes.FeeStatus;
import com.akshara.admissions.AdmissionTypes.PaymentMethod;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;
import com.akshara.students.Gender;

/**
 * A family's enquiry or application for one child, from first contact until the child is admitted or the application
 * is closed. Holds only what admissions needs; it becomes a student record through the students module.
 */
@Entity
@Table(schema = "admissions", name = "application")
class Application extends AssignedIdEntity {

    /** The child and what they apply for, validated by the caller. */
    record Details(String firstName, String lastName, LocalDate dateOfBirth, Gender gender, String previousSchool,
            UUID classId, UUID academicYearId, ApplicationSource source, UUID assignedToId, LocalDate followUpOn) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStage stage;

    @Column(nullable = false)
    private Instant stageChangedAt;

    @Column(nullable = false)
    private String firstName;

    private String lastName;

    @Column(nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private String previousSchool;

    @Column(nullable = false)
    private UUID classId;

    @Column(nullable = false)
    private UUID academicYearId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationSource source;

    private UUID assignedToId;

    private LocalDate followUpOn;

    @Column(updatable = false)
    private String message;

    @Column(updatable = false)
    private String consentVersion;

    @Column(updatable = false)
    private Instant consentAt;

    @Enumerated(EnumType.STRING)
    private FeeStatus feeStatus;

    private Long feeAmountPaise;

    @Enumerated(EnumType.STRING)
    private PaymentMethod feeMethod;

    private String feeReference;

    private LocalDate feeOn;

    private LocalDate offeredOn;

    private LocalDate offerValidUntil;

    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Application() {
    }

    Application(ApplicationStage stage, Details details, String message, String consentVersion, Instant consentAt) {
        this.id = Ids.newId();
        this.stage = stage;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.stageChangedAt = createdAt;
        this.message = message;
        this.consentVersion = consentVersion;
        this.consentAt = consentAt;
        apply(details);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.firstName = d.firstName();
        this.lastName = d.lastName();
        this.dateOfBirth = d.dateOfBirth();
        this.gender = d.gender();
        this.previousSchool = d.previousSchool();
        this.classId = d.classId();
        this.academicYearId = d.academicYearId();
        this.source = d.source();
        this.assignedToId = d.assignedToId();
        this.followUpOn = d.followUpOn();
    }

    void moveTo(ApplicationStage target) {
        this.stage = target;
        this.stageChangedAt = Instant.now();
    }

    void recordFee(FeeStatus status, Long amountPaise, PaymentMethod method, String reference, LocalDate on) {
        this.feeStatus = status;
        this.feeAmountPaise = amountPaise;
        this.feeMethod = method;
        this.feeReference = reference;
        this.feeOn = on;
    }

    void offer(LocalDate on, LocalDate validUntil) {
        this.offeredOn = on;
        this.offerValidUntil = validUntil;
    }

    void setGender(Gender gender) {
        this.gender = gender;
    }

    void admitted(UUID studentId) {
        this.studentId = studentId;
        moveTo(ApplicationStage.ADMITTED);
    }

    String childName() {
        return lastName == null || lastName.isBlank() ? firstName : firstName + " " + lastName;
    }

    @Override
    public UUID getId() {
        return id;
    }

    ApplicationStage getStage() {
        return stage;
    }

    Instant getStageChangedAt() {
        return stageChangedAt;
    }

    String getFirstName() {
        return firstName;
    }

    String getLastName() {
        return lastName;
    }

    LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    Gender getGender() {
        return gender;
    }

    String getPreviousSchool() {
        return previousSchool;
    }

    UUID getClassId() {
        return classId;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    ApplicationSource getSource() {
        return source;
    }

    UUID getAssignedToId() {
        return assignedToId;
    }

    LocalDate getFollowUpOn() {
        return followUpOn;
    }

    String getMessage() {
        return message;
    }

    String getConsentVersion() {
        return consentVersion;
    }

    Instant getConsentAt() {
        return consentAt;
    }

    FeeStatus getFeeStatus() {
        return feeStatus;
    }

    Long getFeeAmountPaise() {
        return feeAmountPaise;
    }

    PaymentMethod getFeeMethod() {
        return feeMethod;
    }

    String getFeeReference() {
        return feeReference;
    }

    LocalDate getFeeOn() {
        return feeOn;
    }

    LocalDate getOfferedOn() {
        return offeredOn;
    }

    LocalDate getOfferValidUntil() {
        return offerValidUntil;
    }

    UUID getStudentId() {
        return studentId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
