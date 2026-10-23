package com.akshara.govreport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.akshara.govreport.GovReportTypes.AllotmentOutcome;
import com.akshara.govreport.GovReportTypes.CodeListType;
import com.akshara.govreport.GovReportTypes.Eligibility;
import com.akshara.govreport.GovReportTypes.ExportKind;
import com.akshara.govreport.GovReportTypes.IntakeStatus;
import com.akshara.govreport.GovReportTypes.SchemeStatus;
import com.akshara.govreport.GovReportTypes.UpdatedVia;
import com.akshara.students.Gender;

/** Response bodies of the government reporting API (docs/api/phase-3-govreport.md). */
public final class GovReportViews {

    private GovReportViews() {
    }

    // ------------------------------------------------------------------ code lists

    public record CodeView(UUID id, CodeListType list, String code, String label, String value, String stateCode,
            LocalDate effectiveFrom, LocalDate effectiveTo, boolean verify, boolean active, int displayOrder,
            boolean inForce) {
    }

    // ------------------------------------------------------------------ the school profile

    /** Teacher counts by qualification, derived from the staff module's profiles (free-text qualifications). */
    public record TeacherCounts(long total, long trained, long postGraduate, long graduate, long other,
            long profileIncomplete) {
    }

    /** What is filled in of what the export needs; {@code missing} names the empty fields. */
    public record Completeness(int filled, int total, int percent, List<String> missing) {
    }

    public record ProfileView(boolean saved, UUID academicYearId, String academicYearName, String schoolName,
            String udiseCode, String board, String affiliationNo, LocalDate affiliationValidUntil,
            String schoolCategoryCode, String managementCode, String diseBlockCode, String diseDistrictCode,
            String stateCode, String recognitionAuthority, String recognitionNo, LocalDate recognitionValidUntil,
            String lowestClass, String highestClass, String mediumOfInstruction, Integer classrooms,
            Integer otherRooms, Integer toiletsBoys, Integer toiletsGirls, Integer toiletsCwsn, Boolean drinkingWater,
            String drinkingWaterSource, Boolean electricity, Boolean library, Integer libraryBooks,
            Boolean playground, Integer computers, Boolean internet, Boolean ramps, Boolean boundaryWall,
            TeacherCounts teachers, Completeness completeness, Instant updatedAt) {
    }

    // ------------------------------------------------------------------ students' government fields

    public record StudentFieldsRow(UUID studentId, String fullName, String admissionNo, Integer rollNo, Gender gender,
            LocalDate dateOfBirth, String className, String sectionName, String apaarId, boolean apaarLocked,
            String pen, String socialCategory, String religion, String motherTongue, Boolean bpl, String cwsnType,
            Boolean rteAdmitted, String previousSchoolUdise, List<String> missing, UpdatedVia updatedVia,
            String updatedByName, Instant updatedAt) {
    }

    public record SectionGrid(UUID sectionId, String sectionLabel, UUID academicYearId, int students, int complete,
            List<StudentFieldsRow> rows) {
    }

    public record BulkResult(int saved, List<StudentFieldsRow> rows) {
    }

    public record RowError(int row, String column, String message) {
    }

    public record ImportResult(boolean dryRun, boolean committed, int totalRows, int validRows, int invalidRows,
            int saved, List<RowError> errors) {
    }

    public record SectionCompleteness(UUID sectionId, String label, int students, int complete, int percent,
            Map<String, Integer> missingByField) {
    }

    public record CompletenessReport(UUID academicYearId, String academicYearName, int students, int complete,
            int percent, List<SectionCompleteness> rows) {
    }

    // ------------------------------------------------------------------ the family app

    public record FamilyFields(UUID studentId, String fullName, String className, String sectionName,
            String motherTongue, String apaarId, boolean apaarEditable) {
    }

    public record FamilyView(List<FamilyFields> children, List<CodeView> motherTongues) {
    }

    // ------------------------------------------------------------------ exports

    /** One table of an export: its sheet or file name, column headings and rows (strings, numbers, dates). */
    public record ExportTable(String key, String title, List<String> columns, List<List<Object>> rows) {
    }

    /** The preview of an export before it is downloaded. Every export is labelled "verify". */
    public record ExportPreview(ExportKind kind, UUID academicYearId, String academicYearName, LocalDate asOf,
            boolean verify, List<String> notes, List<ExportTable> tables) {
    }

    // ------------------------------------------------------------------ RTE

    public record IntakeView(UUID id, UUID academicYearId, String academicYearName, UUID entryClassId,
            String className, int totalSeats, int rteSeats, String stateCode, long incomeLimitPaise,
            BigDecimal distanceBand1Km, BigDecimal distanceBand2Km, BigDecimal distanceBand3Km,
            List<String> documents, LocalDate applicationsCloseOn, IntakeStatus status, UUID currentRunId,
            long applicants, long eligible, int runs, String createdByName, Instant createdAt) {
    }

    /** An RTE application with the child's details from admissions (the guardian's phone masked). */
    public record RteApplicationView(UUID id, UUID applicationId, String childName, LocalDate dateOfBirth,
            Gender gender, String guardianName, String guardianPhone, String stage, String categoryCode,
            Long annualIncomePaise, boolean incomeDeclared, BigDecimal distanceKm, int distanceBand,
            Map<String, Boolean> documents, Eligibility eligibility, String ineligibleReason, String screenedByName,
            Instant screenedAt, boolean withdrawn, Instant createdAt) {
    }

    public record RunSummary(UUID id, int runNo, long seed, int seats, int applicants, String witnessName,
            String reason, String ranByName, Instant ranAt, boolean current) {
    }

    public record IntakeDetail(IntakeView intake, List<RteApplicationView> applications, List<RunSummary> runs) {
    }

    public record AllotmentView(int position, AllotmentOutcome outcome, Integer waitlistNo, int distanceBand,
            RteApplicationView application) {
    }

    public record RunDetail(RunSummary run, IntakeView intake, String schoolName, List<AllotmentView> allotments) {
    }

    /** An admissions application the office may add to the intake. */
    public record ApplicationPick(UUID applicationId, String childName, String className, String stage,
            boolean inIntake) {
    }

    // ------------------------------------------------------------------ schemes

    public record SchemeView(UUID id, UUID studentId, String fullName, String admissionNo, String className,
            String sectionName, UUID academicYearId, String schemeCode, String schemeLabel, SchemeStatus status,
            Long amountPaise, String referenceNo, LocalDate appliedOn, String notes, Instant updatedAt) {
    }

    // ------------------------------------------------------------------ reports

    public record EnrolmentRow(UUID classId, String className, long boys, long girls, long other, long total,
            Map<String, Long> byCategory) {
    }

    public record EnrolmentReport(UUID academicYearId, String academicYearName, LocalDate asOf,
            List<String> categories, List<EnrolmentRow> rows, EnrolmentRow totals) {
    }

    public record RteReport(UUID academicYearId, String academicYearName, long seats, long allotted, long waitlisted,
            List<IntakeView> intakes) {
    }

    public record SchemeTotal(String schemeCode, String schemeLabel, long students, long amountPaise,
            Map<SchemeStatus, Long> byStatus) {
    }

    public record SchemeReport(UUID academicYearId, String academicYearName, long students, long amountPaise,
            List<SchemeTotal> rows) {
    }
}
