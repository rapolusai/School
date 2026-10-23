package com.akshara.govreport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.akshara.govreport.GovReportTypes.CodeListType;
import com.akshara.govreport.GovReportTypes.Eligibility;
import com.akshara.govreport.GovReportTypes.SchemeStatus;

/** Request bodies of the government reporting API (docs/api/phase-3-govreport.md). The demo data uses them too. */
public final class GovReportForms {

    public static final String UDISE_PATTERN = "^$|^[0-9]{11}$";
    public static final String APAAR_PATTERN = "^$|^[0-9]{12}$";
    public static final String PEN_PATTERN = "^$|^[A-Za-z0-9]{1,20}$";
    public static final String CODE_PATTERN = "^[A-Z0-9_]{1,30}$";
    /** The most students one bulk save carries. */
    public static final int MAX_BULK = 500;

    private GovReportForms() {
    }

    /** Edits to one code of a code list. The list and the code itself never change. */
    public record CodeForm(
            @NotBlank @Size(max = 120) String label,
            @Size(max = 60) String value,
            @Size(max = 2) String stateCode,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotNull Boolean verify,
            @NotNull Boolean active,
            @NotNull @Min(0) @Max(999) Integer displayOrder) {
    }

    /** A code the school adds to a list. */
    public record NewCodeForm(
            @NotNull CodeListType list,
            @NotBlank @Pattern(regexp = CODE_PATTERN,
                    message = "Use 1 to 30 capital letters, digits or underscores for the code.") String code,
            @NotBlank @Size(max = 120) String label,
            @Size(max = 60) String value,
            @Size(max = 2) String stateCode,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotNull @Min(0) @Max(999) Integer displayOrder) {
    }

    /** The school's government profile for a year. Every field may be empty; completeness says what is missing. */
    public record ProfileForm(
            @Pattern(regexp = UDISE_PATTERN, message = "A UDISE code has 11 digits.") String udiseCode,
            @Size(max = 20) String board,
            @Size(max = 60) String affiliationNo,
            LocalDate affiliationValidUntil,
            @Size(max = 30) String schoolCategoryCode,
            @Size(max = 30) String managementCode,
            @Size(max = 10) String diseBlockCode,
            @Size(max = 10) String diseDistrictCode,
            @Size(max = 2) String stateCode,
            @Size(max = 120) String recognitionAuthority,
            @Size(max = 60) String recognitionNo,
            LocalDate recognitionValidUntil,
            @Size(max = 20) String lowestClass,
            @Size(max = 20) String highestClass,
            @Size(max = 60) String mediumOfInstruction,
            @Min(0) @Max(999) Integer classrooms,
            @Min(0) @Max(999) Integer otherRooms,
            @Min(0) @Max(999) Integer toiletsBoys,
            @Min(0) @Max(999) Integer toiletsGirls,
            @Min(0) @Max(999) Integer toiletsCwsn,
            Boolean drinkingWater,
            @Size(max = 60) String drinkingWaterSource,
            Boolean electricity,
            Boolean library,
            @Min(0) @Max(9_999_999) Integer libraryBooks,
            Boolean playground,
            @Min(0) @Max(99_999) Integer computers,
            Boolean internet,
            Boolean ramps,
            Boolean boundaryWall) {
    }

    /** One student's government fields, as the bulk entry grid saves them. */
    public record StudentFieldsForm(
            @NotNull UUID studentId,
            @Pattern(regexp = APAAR_PATTERN, message = "An APAAR ID has 12 digits.") String apaarId,
            Boolean apaarLocked,
            @Pattern(regexp = PEN_PATTERN, message = "A PEN has up to 20 letters or digits.") String pen,
            @Size(max = 30) String socialCategory,
            @Size(max = 30) String religion,
            @Size(max = 30) String motherTongue,
            Boolean bpl,
            @Size(max = 30) String cwsnType,
            Boolean rteAdmitted,
            @Pattern(regexp = UDISE_PATTERN, message = "A UDISE code has 11 digits.") String previousSchoolUdise) {
    }

    public record BulkFieldsForm(@NotNull @Size(max = MAX_BULK) List<@Valid StudentFieldsForm> entries) {
    }

    /** A CSV file of government fields, as text. */
    public record ImportForm(@NotBlank @Size(max = 2_000_000) String csv) {
    }

    /** What a parent may fill from the family app. */
    public record FamilyFieldsForm(
            @Size(max = 30) String motherTongue,
            @Pattern(regexp = APAAR_PATTERN, message = "An APAAR ID has 12 digits.") String apaarId) {
    }

    /**
     * An RTE intake. When {@code rteSeats} is null it is 25% of the seats, rounded up; when {@code incomeLimitPaise}
     * is null it comes from the RTE_INCOME_LIMIT code list for the state; the bands default to 1, 3 and 6 km and the
     * documents to the RTE_DOCUMENT list.
     */
    public record IntakeForm(
            @NotNull UUID academicYearId,
            @NotNull UUID entryClassId,
            @NotNull @Min(1) @Max(2000) Integer totalSeats,
            @Min(0) @Max(2000) Integer rteSeats,
            @Size(max = 2) String stateCode,
            @Min(0) Long incomeLimitPaise,
            @DecimalMin("0.01") @DecimalMax("999") BigDecimal distanceBand1Km,
            @DecimalMin("0.01") @DecimalMax("999") BigDecimal distanceBand2Km,
            @DecimalMin("0.01") @DecimalMax("999") BigDecimal distanceBand3Km,
            @Size(max = 30) List<@NotBlank @Size(max = 30) String> documents,
            LocalDate applicationsCloseOn) {
    }

    /** Marks an admissions application as an RTE application of the intake. */
    public record RteApplicationForm(@NotNull UUID applicationId) {
    }

    /** The screening of an RTE application: declaration, distance, documents and the outcome. */
    public record ScreeningForm(
            @Size(max = 30) String categoryCode,
            @Min(0) Long annualIncomePaise,
            @NotNull Boolean incomeDeclared,
            @DecimalMin("0") @DecimalMax("999") BigDecimal distanceKm,
            @Size(max = 30) Map<@NotBlank @Size(max = 30) String, @NotNull Boolean> documents,
            @NotNull Eligibility eligibility,
            @Size(max = 300) String ineligibleReason) {
    }

    /** Runs the lottery. {@code seed} is drawn at random when not given; {@code reason} is required for a rerun. */
    public record LotteryForm(
            @NotBlank @Size(max = 200) String witnessName,
            @Size(max = 500) String reason,
            Long seed) {
    }

    public record SchemeForm(
            @NotNull UUID studentId,
            @NotNull UUID academicYearId,
            @NotBlank @Size(max = 30) String schemeCode,
            @NotNull SchemeStatus status,
            @Min(0) @Max(100_000_000_000L) Long amountPaise,
            @Size(max = 60) String referenceNo,
            LocalDate appliedOn,
            @Size(max = 500) String notes) {
    }

    public record SchemeUpdateForm(
            @NotNull SchemeStatus status,
            @Min(0) @Max(100_000_000_000L) Long amountPaise,
            @Size(max = 60) String referenceNo,
            LocalDate appliedOn,
            @Size(max = 500) String notes) {
    }
}
