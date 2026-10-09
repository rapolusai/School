package com.akshara.staff;

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

/**
 * The staff record of one sign-in: employee code, designation, department and employment details. No salary, bank,
 * Aadhaar or PAN details are kept.
 */
@Entity
@Table(schema = "staff", name = "staff_profile")
class StaffProfile extends AssignedIdEntity {

    /** The editable part of a profile, already cleaned (trimmed, mobile numbers as 10 digits). */
    record Fields(String employeeCode, String designation, UUID departmentId, EmploymentType employmentType,
            LocalDate dateOfJoining, String mobile, String qualifications, String emergencyContactName,
            String emergencyContactMobile) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String employeeCode;

    @Column(nullable = false)
    private String designation;

    private UUID departmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmploymentType employmentType;

    @Column(nullable = false)
    private LocalDate dateOfJoining;

    private LocalDate dateOfLeaving;

    private String leavingReason;

    @Column(nullable = false)
    private String mobile;

    private String qualifications;

    private String emergencyContactName;

    private String emergencyContactMobile;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StaffProfile() {
    }

    StaffProfile(UUID userId, Fields fields) {
        this.id = Ids.newId();
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        apply(fields);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Fields f) {
        this.employeeCode = f.employeeCode();
        this.designation = f.designation();
        this.departmentId = f.departmentId();
        this.employmentType = f.employmentType();
        this.dateOfJoining = f.dateOfJoining();
        this.mobile = f.mobile();
        this.qualifications = f.qualifications();
        this.emergencyContactName = f.emergencyContactName();
        this.emergencyContactMobile = f.emergencyContactMobile();
    }

    Fields fields() {
        return new Fields(employeeCode, designation, departmentId, employmentType, dateOfJoining, mobile,
                qualifications, emergencyContactName, emergencyContactMobile);
    }

    void leave(LocalDate on, String reason) {
        this.dateOfLeaving = on;
        this.leavingReason = reason;
    }

    boolean hasLeft() {
        return dateOfLeaving != null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    String getEmployeeCode() {
        return employeeCode;
    }

    String getDesignation() {
        return designation;
    }

    UUID getDepartmentId() {
        return departmentId;
    }

    EmploymentType getEmploymentType() {
        return employmentType;
    }

    LocalDate getDateOfJoining() {
        return dateOfJoining;
    }

    LocalDate getDateOfLeaving() {
        return dateOfLeaving;
    }

    String getLeavingReason() {
        return leavingReason;
    }

    String getMobile() {
        return mobile;
    }

    String getQualifications() {
        return qualifications;
    }

    String getEmergencyContactName() {
        return emergencyContactName;
    }

    String getEmergencyContactMobile() {
        return emergencyContactMobile;
    }
}
