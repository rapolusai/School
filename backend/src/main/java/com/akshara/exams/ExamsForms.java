package com.akshara.exams;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies of the exams API (docs/api/phase-2-exams.md). The demo data uses the same records. */
public final class ExamsForms {

    /** At most this many students on one mark sheet or one section's records. */
    public static final int MAX_STUDENTS = 500;
    public static final int MAX_PAPERS = 40;
    public static final int MAX_BANDS = 20;

    private ExamsForms() {
    }

    public record BandForm(
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal minPercent,
            @NotBlank @Size(max = 10) String grade,
            @DecimalMin("0") @DecimalMax("10") @Digits(integer = 2, fraction = 1) BigDecimal gradePoint,
            @Size(max = 100) String remark) {
    }

    /** A grading scheme with its bands. The pass marks apply to every subject and to the total. */
    public record SchemeForm(
            @NotBlank @Size(max = 100) String name,
            @NotNull SchemeKind kind,
            @Size(max = 500) String description,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal passPercent,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
            BigDecimal overallPassPercent,
            @NotEmpty @Size(max = MAX_BANDS) List<@NotNull @Valid BandForm> bands) {
    }

    /** {@code coScholasticSchemeId} null means the built-in CBSE co-scholastic scheme. */
    public record SettingsForm(
            @NotNull Boolean ranksEnabled,
            @NotNull @Min(0) @Max(12) Integer hpcUpToLevel,
            UUID coScholasticSchemeId) {
    }

    /**
     * Gives the classes (a class range) one scheme for the year; {@code schemeId} null puts them back on the default.
     */
    public record ClassSchemeForm(
            @NotEmpty @Size(max = 100) List<@NotNull UUID> classIds,
            UUID schemeId) {
    }

    /** {@code academicYearId} is needed to add a term and ignored when changing one. */
    public record TermForm(
            UUID academicYearId,
            @NotBlank @Size(max = 60) String name,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn) {
    }

    public record WeightForm(
            @NotNull UUID id,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal weight) {
    }

    /** Weights of every term of a year, or of every exam of a term: they must add up to 100. */
    public record WeightsForm(@NotEmpty @Size(max = 50) List<@NotNull @Valid WeightForm> weights) {
    }

    /** {@code termId} is needed to add an exam and ignored when changing one. */
    public record ExamForm(
            UUID termId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 2000) String instructions,
            @NotNull @Size(max = 100) List<@NotNull UUID> classIds) {

        public ExamForm {
            classIds = classIds == null ? null : List.copyOf(classIds);
        }
    }

    /**
     * A subject paper: its maximum marks per component (a component with 0 is not part of the paper) and its date
     * sheet slot. Date, start time and duration are given together or not at all.
     */
    public record PaperForm(
            @NotNull UUID subjectId,
            @NotNull @DecimalMin("0") @DecimalMax("1000") @Digits(integer = 4, fraction = 2) BigDecimal maxTheory,
            @DecimalMin("0") @DecimalMax("1000") @Digits(integer = 4, fraction = 2) BigDecimal maxPractical,
            @DecimalMin("0") @DecimalMax("1000") @Digits(integer = 4, fraction = 2) BigDecimal maxInternal,
            LocalDate date,
            LocalTime startTime,
            @Min(15) @Max(600) Integer durationMinutes,
            @Size(max = 60) String room) {
    }

    /** Every paper of an exam for a class, replacing the list. */
    public record PapersForm(@NotNull @Size(max = MAX_PAPERS) List<@NotNull @Valid PaperForm> papers) {
    }

    /** One student's marks by component, or a code (AB, ML, EX) with no marks. Everything blank clears the line. */
    public record MarkEntryForm(
            @NotNull UUID studentId,
            @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal theory,
            @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal practical,
            @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal internal,
            MarkCode code) {
    }

    /** A draft save. {@code version} is the sheet's version as loaded (null for a sheet not saved before). */
    public record MarkSheetForm(
            Long version,
            @NotNull @Size(max = MAX_STUDENTS) List<@NotNull @Valid MarkEntryForm> entries) {
    }

    /** Submitting (locking) a sheet. {@code version} as loaded. */
    public record SubmitForm(Long version) {
    }

    public record ReasonForm(@NotBlank @Size(max = 500) String reason) {
    }

    /**
     * A student's term record: co-scholastic grades, the class teacher's remark and, for foundational and preparatory
     * classes, the holistic progress card.
     */
    public record RecordForm(
            @NotNull UUID studentId,
            @Size(max = 10) String workEducation,
            @Size(max = 10) String artEducation,
            @Size(max = 10) String healthPhysical,
            @Size(max = 10) String discipline,
            @Size(max = 500) String remark,
            HpcLevel physical,
            HpcLevel socioEmotional,
            HpcLevel cognitive,
            HpcLevel language,
            HpcLevel numeracy,
            HpcLevel aesthetic,
            @Size(max = 1000) String teacherReflection,
            @Size(max = 1000) String selfReflection,
            @Size(max = 1000) String parentReflection) {
    }

    public record RecordsForm(@NotNull @Size(max = MAX_STUDENTS) List<@NotNull @Valid RecordForm> entries) {
    }
}
