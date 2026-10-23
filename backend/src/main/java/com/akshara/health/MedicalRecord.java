package com.akshara.health;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A student's medical record head: blood group, doctor, insurance and the parents' consent. Allergies, conditions,
 * medication and vaccinations are {@link RecordItem}s; height and weight are {@link GrowthMeasurement}s. Created
 * the first time anyone writes to it.
 */
@Entity
@Table(schema = "health", name = "medical_record")
class MedicalRecord extends AssignedIdEntity {

    /** The staff-kept head fields, already cleaned. */
    record Details(String bloodGroup, String doctorName, String doctorPhone, String insuranceProvider,
            String insurancePolicyNo, String notes) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    private String bloodGroup;

    private String doctorName;

    private String doctorPhone;

    private String insuranceProvider;

    private String insurancePolicyNo;

    @Column(nullable = false)
    private boolean consentFirstAid;

    @Column(nullable = false)
    private boolean consentHospital;

    private String consentByName;

    private Instant consentAt;

    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected MedicalRecord() {
    }

    MedicalRecord(UUID studentId, Instant now) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.bloodGroup = d.bloodGroup();
        this.doctorName = d.doctorName();
        this.doctorPhone = d.doctorPhone();
        this.insuranceProvider = d.insuranceProvider();
        this.insurancePolicyNo = d.insurancePolicyNo();
        this.notes = d.notes();
    }

    void consent(boolean firstAid, boolean hospital, String byName, Instant at) {
        this.consentFirstAid = firstAid;
        this.consentHospital = hospital;
        this.consentByName = byName;
        this.consentAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    String getBloodGroup() {
        return bloodGroup;
    }

    String getDoctorName() {
        return doctorName;
    }

    String getDoctorPhone() {
        return doctorPhone;
    }

    String getInsuranceProvider() {
        return insuranceProvider;
    }

    String getInsurancePolicyNo() {
        return insurancePolicyNo;
    }

    boolean isConsentFirstAid() {
        return consentFirstAid;
    }

    boolean isConsentHospital() {
        return consentHospital;
    }

    String getConsentByName() {
        return consentByName;
    }

    Instant getConsentAt() {
        return consentAt;
    }

    String getNotes() {
        return notes;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
