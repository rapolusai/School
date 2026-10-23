package com.akshara.govreport;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.govreport.GovReportForms.ProfileForm;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** The school's government profile for one academic year: codes, recognition and infrastructure counts. */
@Entity
@Table(schema = "govreport", name = "school_profile")
class GovSchoolProfile extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    private String udiseCode;
    private String board;
    private String affiliationNo;
    private LocalDate affiliationValidUntil;
    private String schoolCategoryCode;
    private String managementCode;
    private String diseBlockCode;
    private String diseDistrictCode;
    private String stateCode;
    private String recognitionAuthority;
    private String recognitionNo;
    private LocalDate recognitionValidUntil;
    private String lowestClass;
    private String highestClass;
    private String mediumOfInstruction;
    private Integer classrooms;
    private Integer otherRooms;
    private Integer toiletsBoys;
    private Integer toiletsGirls;
    private Integer toiletsCwsn;
    private Boolean drinkingWater;
    private String drinkingWaterSource;
    private Boolean electricity;
    private Boolean library;
    private Integer libraryBooks;
    private Boolean playground;
    private Integer computers;
    private Boolean internet;
    private Boolean ramps;
    private Boolean boundaryWall;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected GovSchoolProfile() {
    }

    GovSchoolProfile(UUID academicYearId) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(ProfileForm f) {
        this.udiseCode = GovReportAuth.blankToNull(f.udiseCode());
        this.board = GovReportAuth.blankToNull(f.board());
        this.affiliationNo = GovReportAuth.blankToNull(f.affiliationNo());
        this.affiliationValidUntil = f.affiliationValidUntil();
        this.schoolCategoryCode = GovReportAuth.blankToNull(f.schoolCategoryCode());
        this.managementCode = GovReportAuth.blankToNull(f.managementCode());
        this.diseBlockCode = GovReportAuth.blankToNull(f.diseBlockCode());
        this.diseDistrictCode = GovReportAuth.blankToNull(f.diseDistrictCode());
        this.stateCode = GovReportAuth.blankToNull(f.stateCode());
        this.recognitionAuthority = GovReportAuth.blankToNull(f.recognitionAuthority());
        this.recognitionNo = GovReportAuth.blankToNull(f.recognitionNo());
        this.recognitionValidUntil = f.recognitionValidUntil();
        this.lowestClass = GovReportAuth.blankToNull(f.lowestClass());
        this.highestClass = GovReportAuth.blankToNull(f.highestClass());
        this.mediumOfInstruction = GovReportAuth.blankToNull(f.mediumOfInstruction());
        this.classrooms = f.classrooms();
        this.otherRooms = f.otherRooms();
        this.toiletsBoys = f.toiletsBoys();
        this.toiletsGirls = f.toiletsGirls();
        this.toiletsCwsn = f.toiletsCwsn();
        this.drinkingWater = f.drinkingWater();
        this.drinkingWaterSource = GovReportAuth.blankToNull(f.drinkingWaterSource());
        this.electricity = f.electricity();
        this.library = f.library();
        this.libraryBooks = f.libraryBooks();
        this.playground = f.playground();
        this.computers = f.computers();
        this.internet = f.internet();
        this.ramps = f.ramps();
        this.boundaryWall = f.boundaryWall();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    String getUdiseCode() {
        return udiseCode;
    }

    String getBoard() {
        return board;
    }

    String getAffiliationNo() {
        return affiliationNo;
    }

    LocalDate getAffiliationValidUntil() {
        return affiliationValidUntil;
    }

    String getSchoolCategoryCode() {
        return schoolCategoryCode;
    }

    String getManagementCode() {
        return managementCode;
    }

    String getDiseBlockCode() {
        return diseBlockCode;
    }

    String getDiseDistrictCode() {
        return diseDistrictCode;
    }

    String getStateCode() {
        return stateCode;
    }

    String getRecognitionAuthority() {
        return recognitionAuthority;
    }

    String getRecognitionNo() {
        return recognitionNo;
    }

    LocalDate getRecognitionValidUntil() {
        return recognitionValidUntil;
    }

    String getLowestClass() {
        return lowestClass;
    }

    String getHighestClass() {
        return highestClass;
    }

    String getMediumOfInstruction() {
        return mediumOfInstruction;
    }

    Integer getClassrooms() {
        return classrooms;
    }

    Integer getOtherRooms() {
        return otherRooms;
    }

    Integer getToiletsBoys() {
        return toiletsBoys;
    }

    Integer getToiletsGirls() {
        return toiletsGirls;
    }

    Integer getToiletsCwsn() {
        return toiletsCwsn;
    }

    Boolean getDrinkingWater() {
        return drinkingWater;
    }

    String getDrinkingWaterSource() {
        return drinkingWaterSource;
    }

    Boolean getElectricity() {
        return electricity;
    }

    Boolean getLibrary() {
        return library;
    }

    Integer getLibraryBooks() {
        return libraryBooks;
    }

    Boolean getPlayground() {
        return playground;
    }

    Integer getComputers() {
        return computers;
    }

    Boolean getInternet() {
        return internet;
    }

    Boolean getRamps() {
        return ramps;
    }

    Boolean getBoundaryWall() {
        return boundaryWall;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
