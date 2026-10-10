package com.akshara.students;

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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A student of one school. Holds only what admissions and class lists need; Aadhaar numbers are never stored. */
@Entity
@Table(schema = "students", name = "student")
class Student extends AssignedIdEntity {

    /** The editable profile fields, validated by the caller. */
    record Profile(String admissionNo, String firstName, String lastName, LocalDate dateOfBirth, Gender gender,
            LocalDate admissionDate, String bloodGroup, String address, String previousSchool, String apaarId) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String admissionNo;

    @Column(nullable = false)
    private String firstName;

    private String lastName;

    @Column(nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;

    @Column(nullable = false)
    private LocalDate admissionDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StudentStatus status;

    private String bloodGroup;

    private String address;

    private String previousSchool;

    private String apaarId;

    private UUID userAccountId;

    private LocalDate leftOn;

    private String leavingReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Student() {
    }

    Student(Profile profile) {
        this.id = Ids.newId();
        this.status = StudentStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        apply(profile);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Profile p) {
        this.admissionNo = p.admissionNo();
        this.firstName = p.firstName();
        this.lastName = p.lastName();
        this.dateOfBirth = p.dateOfBirth();
        this.gender = p.gender();
        this.admissionDate = p.admissionDate();
        this.bloodGroup = p.bloodGroup();
        this.address = p.address();
        this.previousSchool = p.previousSchool();
        this.apaarId = p.apaarId();
    }

    /** Transferred or withdrawn: the student leaves on the given day. */
    void leave(StudentStatus newStatus, LocalDate on, String reason) {
        this.status = newStatus;
        this.leftOn = on;
        this.leavingReason = reason;
    }

    void graduate(LocalDate on) {
        this.status = StudentStatus.ALUMNI;
        this.leftOn = on;
        this.leavingReason = null;
    }

    void linkUser(UUID userId) {
        this.userAccountId = userId;
    }

    /**
     * Erasure of a student who has left: the name becomes a placeholder, the date of birth keeps only its year, and
     * the other personal fields and the student's own sign-in link are cleared. The admission number, dates and status
     * stay, so records the school must keep (fee receipts) still point at a student.
     */
    void anonymise(String placeholderName) {
        this.firstName = placeholderName;
        this.lastName = null;
        this.dateOfBirth = LocalDate.of(dateOfBirth.getYear(), 1, 1);
        this.bloodGroup = null;
        this.address = null;
        this.previousSchool = null;
        this.apaarId = null;
        this.leavingReason = null;
        this.userAccountId = null;
    }

    String fullName() {
        return lastName == null || lastName.isBlank() ? firstName : firstName + " " + lastName;
    }

    boolean isActive() {
        return status == StudentStatus.ACTIVE;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getAdmissionNo() {
        return admissionNo;
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

    LocalDate getAdmissionDate() {
        return admissionDate;
    }

    StudentStatus getStatus() {
        return status;
    }

    String getBloodGroup() {
        return bloodGroup;
    }

    String getAddress() {
        return address;
    }

    String getPreviousSchool() {
        return previousSchool;
    }

    String getApaarId() {
        return apaarId;
    }

    UUID getUserAccountId() {
        return userAccountId;
    }

    LocalDate getLeftOn() {
        return leftOn;
    }

    String getLeavingReason() {
        return leavingReason;
    }
}
