package com.akshara.lessons;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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

/** Request bodies of the lessons API (docs/api/phase-2-lessons.md). The demo data uses the same records. */
public final class LessonsForms {

    public static final int MAX_UNITS = 40;
    public static final int MAX_TOPICS_PER_UNIT = 60;
    public static final int MAX_OPTIONS = 6;
    public static final int MAX_TEST_QUESTIONS = 100;
    public static final int MAX_TEST_SECTIONS = 30;

    private LessonsForms() {
    }

    public record SettingsForm(@NotNull Boolean testNotificationsEnabled) {
    }

    /** A topic; {@code id} keeps an existing topic (and the plans that point at it). */
    public record TopicForm(
            UUID id,
            @NotBlank @Size(max = 200) String title,
            @NotNull @Min(1) @Max(200) Integer plannedPeriods,
            YearMonth targetMonth) {
    }

    public record UnitForm(
            UUID id,
            @NotBlank @Size(max = 200) String title,
            @NotNull @Size(max = MAX_TOPICS_PER_UNIT) List<@NotNull @Valid TopicForm> topics) {
    }

    /**
     * A whole syllabus: its units and topics in order. On update, units and topics without an id are added and those
     * left out are removed (a topic with lesson plans cannot be removed). {@code academicYearId} null is the current
     * year; {@code version} guards against two people editing at once.
     */
    public record SyllabusForm(
            UUID academicYearId,
            @NotNull UUID classId,
            @NotNull UUID subjectId,
            Long version,
            @NotNull @Size(max = MAX_UNITS) List<@NotNull @Valid UnitForm> units) {
    }

    /** Copies the syllabi of one year to another (all classes, or the ones listed); existing ones are skipped. */
    public record CopyForm(@NotNull UUID fromYearId, @NotNull UUID toYearId, @Size(max = 100) List<UUID> classIds) {
    }

    public record ImportRequest(@NotNull @Size(max = 2_000_000, message = "The file is too large.") String csv) {
    }

    /**
     * A lesson plan. {@code weekStart} may be any day of the week (it is moved to that Monday); {@code planDate}, if
     * given, is a day of that week. {@code title} may be left out when a topic is chosen.
     */
    public record PlanForm(
            UUID academicYearId,
            @NotNull UUID sectionId,
            @NotNull UUID subjectId,
            UUID topicId,
            @Size(max = 200) String title,
            @NotNull LocalDate weekStart,
            LocalDate planDate,
            @NotNull @Min(1) @Max(20) Integer periods,
            @Size(max = 2000) String objectives,
            @Size(max = 2000) String teachingMethod,
            @Size(max = 2000) String resources,
            @Size(max = 2000) String resourceLinks,
            @Size(max = 1000) String homeworkIdea,
            @Size(max = 1000) String assessmentNote,
            Long version) {
    }

    /** An approver's comment: optional when approving, required when returning a plan. */
    public record ReviewForm(@Size(max = 1000) String comment) {
    }

    public record TaughtForm(@NotNull LocalDate taughtOn) {
    }

    /** Study material; {@code sectionId} null is every section of the class; {@code publishOn} null is today. */
    public record MaterialForm(
            UUID academicYearId,
            @NotNull UUID classId,
            UUID sectionId,
            @NotNull UUID subjectId,
            UUID topicId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String notes,
            @Size(max = 1000) String linkUrl,
            LocalDate publishOn) {
    }

    public record OptionForm(@NotBlank @Size(max = 500) String text, @NotNull Boolean correct) {
    }

    /**
     * A bank question. Multiple choice questions need 2 to 6 options (exactly one correct for MCQ_SINGLE, at least
     * one for MCQ_MULTI); {@code answerText} is TRUE or FALSE for TRUE_FALSE, the accepted answers (one per line) for
     * FILL_BLANK and an optional model answer for SHORT_ANSWER.
     */
    public record QuestionForm(
            @NotNull UUID classId,
            @NotNull UUID subjectId,
            UUID topicId,
            @NotNull QuestionType type,
            @NotNull Difficulty difficulty,
            BloomLevel bloomLevel,
            @NotBlank @Size(max = 2000) String text,
            @Size(max = 1000) String answerText,
            @Size(max = 2000) String explanation,
            @NotNull @DecimalMin("0.25") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal marks,
            Boolean shared,
            @Size(max = MAX_OPTIONS) List<@NotNull @Valid OptionForm> options) {
    }

    /** An online test's settings; its questions are set separately. {@code attemptsAllowed} null is 1. */
    public record TestForm(
            UUID academicYearId,
            @NotNull UUID classId,
            @NotNull UUID subjectId,
            @NotEmpty @Size(max = MAX_TEST_SECTIONS) List<@NotNull UUID> sectionIds,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String instructions,
            @NotNull Instant opensAt,
            @NotNull Instant closesAt,
            @NotNull @Min(1) @Max(300) Integer durationMinutes,
            @Min(1) @Max(5) Integer attemptsAllowed,
            Boolean shuffleQuestions,
            Boolean shuffleOptions,
            ShowAnswers showAnswers,
            Long version) {
    }

    /** A bank question in a test; {@code marks} null keeps the question's own marks. */
    public record TestQuestionForm(
            @NotNull UUID questionId,
            @DecimalMin("0.25") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal marks) {
    }

    public record TestQuestionsForm(
            @NotNull @Size(max = MAX_TEST_QUESTIONS) List<@NotNull @Valid TestQuestionForm> questions) {
    }

    /** Adds {@code count} random bank questions of the test's class and subject, optionally of a topic and level. */
    public record RandomQuestionsForm(UUID topicId, Difficulty difficulty, QuestionType type,
            @NotNull @Min(1) @Max(50) Integer count) {
    }

    public record MarkForm(
            @NotNull UUID answerId,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal marks,
            @Size(max = 500) String feedback) {
    }

    public record MarksForm(@NotEmpty @Size(max = MAX_TEST_QUESTIONS) List<@NotNull @Valid MarkForm> marks) {
    }

    /** A student's answer: the chosen options (multiple choice) or the typed text (TRUE/FALSE for true/false). */
    public record AnswerForm(@Size(max = MAX_OPTIONS) List<@NotNull UUID> optionIds, @Size(max = 1000) String text) {
    }
}
