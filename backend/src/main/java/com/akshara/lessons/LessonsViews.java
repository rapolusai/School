package com.akshara.lessons;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Response bodies of the lessons API (docs/api/phase-2-lessons.md). */
public final class LessonsViews {

    private LessonsViews() {
    }

    // ------------------------------------------------------------------ options and settings

    public record YearRef(UUID id, String name, LocalDate startsOn, LocalDate endsOn, boolean current) {
    }

    public record SectionRef(UUID id, String name, String label) {
    }

    public record SubjectRef(UUID id, String name) {
    }

    public record ClassOption(UUID id, String name, List<SectionRef> sections, List<SubjectRef> subjects) {
    }

    public record TeachingRef(UUID sectionId, UUID subjectId) {
    }

    /**
     * What the lessons screens need for their pickers: the years, every class with its sections and subjects, and
     * what the caller teaches in the year ({@code wholeSchool} for approvers).
     */
    public record Options(UUID yearId, List<YearRef> years, List<ClassOption> classes, UUID userId,
            boolean wholeSchool, boolean canManage, boolean canApprove, List<TeachingRef> teaching) {
    }

    public record Settings(boolean testNotificationsEnabled) {
    }

    // ------------------------------------------------------------------ syllabus

    public record TopicView(UUID id, int position, String title, int plannedPeriods, YearMonth targetMonth,
            boolean hasPlans) {
    }

    public record UnitView(UUID id, int position, String title, List<TopicView> topics) {
    }

    public record SyllabusRow(UUID id, UUID yearId, UUID classId, String className, UUID subjectId,
            String subjectName, int units, int topics, int plannedPeriods, Instant updatedAt, String updatedByName,
            boolean canEdit) {
    }

    public record SyllabusDetail(UUID id, UUID yearId, String yearName, UUID classId, String className,
            UUID subjectId, String subjectName, List<UnitView> units, int topics, int plannedPeriods,
            Instant updatedAt, String updatedByName, long version, boolean canEdit) {
    }

    /** A topic for a picker, with its unit. */
    public record TopicOption(UUID id, String title, String unitTitle, YearMonth targetMonth) {
    }

    public record CopyResult(int copied, int skipped) {
    }

    public record ImportError(int row, String column, String message) {
    }

    public record SyllabusPreviewRow(int row, String className, String subjectName, String unit, String topic,
            int plannedPeriods, YearMonth targetMonth) {
    }

    /** The outcome of checking or importing a syllabus CSV. Nothing is written unless every row is valid. */
    public record SyllabusImportResult(boolean dryRun, boolean committed, int totalRows, int validRows,
            int invalidRows, int syllabi, List<ImportError> errors, List<String> ignoredColumns,
            List<SyllabusPreviewRow> preview) {
    }

    // ------------------------------------------------------------------ lesson plans

    public record PlanRow(UUID id, UUID sectionId, String sectionLabel, UUID subjectId, String subjectName,
            UUID topicId, String topicTitle, String title, LocalDate weekStart, LocalDate planDate, int periods,
            PlanStatus status, UUID teacherId, String teacherName, Instant submittedAt, String reviewComment,
            LocalDate taughtOn, Instant updatedAt, boolean mine) {
    }

    public record PlanDetail(UUID id, UUID yearId, UUID classId, UUID sectionId, String sectionLabel,
            UUID subjectId, String subjectName, UUID topicId, String topicTitle, String unitTitle, String title,
            LocalDate weekStart, LocalDate planDate, int periods, String objectives, String teachingMethod,
            String resources, List<String> resourceLinks, String homeworkIdea, String assessmentNote,
            PlanStatus status, UUID teacherId, String teacherName, Instant submittedAt, Instant reviewedAt,
            String reviewedByName, String reviewComment, LocalDate taughtOn, Instant updatedAt, long version,
            boolean canEdit, boolean canSubmit, boolean canReview, boolean canMarkTaught) {
    }

    // ------------------------------------------------------------------ coverage

    public record CoverageRow(UUID sectionId, String sectionLabel, UUID classId, String className, UUID subjectId,
            String subjectName, int topics, int taught, BigDecimal percent, int due, int overdue,
            BigDecimal expectedPercent, boolean behind, LocalDate lastTaughtOn, String teacherName) {
    }

    public record CoverageReport(UUID yearId, String yearName, YearMonth month, int sections,
            BigDecimal averagePercent, int behind, List<CoverageRow> rows) {
    }

    public record CoverageTopic(UUID id, String title, int plannedPeriods, YearMonth targetMonth, boolean taught,
            LocalDate taughtOn, String teacherName, boolean overdue, int plans) {
    }

    public record CoverageUnit(UUID id, String title, List<CoverageTopic> topics) {
    }

    public record CoverageDetail(CoverageRow summary, List<CoverageUnit> units) {
    }

    // ------------------------------------------------------------------ study material

    public record FileRef(UUID id, String name, String contentType, long size) {
    }

    /** {@code sectionId} null means every section of the class. */
    public record MaterialView(UUID id, UUID yearId, UUID classId, String className, UUID sectionId,
            String sectionLabel, UUID subjectId, String subjectName, UUID topicId, String topicTitle, String title,
            String notes, String linkUrl, LocalDate publishOn, boolean published, String createdByName,
            Instant updatedAt, List<FileRef> files, boolean canEdit) {
    }

    // ------------------------------------------------------------------ question bank

    public record OptionView(UUID id, String text, boolean correct) {
    }

    /** {@code locked}: the question is in a published test, so it cannot be changed or deleted. */
    public record QuestionView(UUID id, UUID classId, String className, UUID subjectId, String subjectName,
            UUID topicId, String topicTitle, QuestionType type, Difficulty difficulty, BloomLevel bloomLevel,
            String text, String answerText, String explanation, BigDecimal marks, boolean shared,
            List<OptionView> options, String createdByName, Instant updatedAt, boolean canEdit, int usedInTests,
            boolean locked) {
    }

    public record QuestionPreviewRow(int row, String className, String subjectName, QuestionType type,
            Difficulty difficulty, BigDecimal marks, String text) {
    }

    public record QuestionImportResult(boolean dryRun, boolean committed, int totalRows, int validRows,
            int invalidRows, List<ImportError> errors, List<String> ignoredColumns,
            List<QuestionPreviewRow> preview) {
    }

    // ------------------------------------------------------------------ online tests (staff)

    /** {@code state}: DRAFT, SCHEDULED (published, not open yet), OPEN or CLOSED. */
    public record TestRow(UUID id, String title, UUID classId, String className, UUID subjectId, String subjectName,
            List<SectionRef> sections, Instant opensAt, Instant closesAt, int durationMinutes, int attemptsAllowed,
            String state, int questions, BigDecimal totalMarks, int students, int submitted, int pendingMarking,
            boolean released, String createdByName, boolean canEdit) {
    }

    public record TestQuestionView(UUID id, UUID questionId, int position, BigDecimal marks, QuestionView question) {
    }

    public record TestDetail(UUID id, UUID yearId, String title, String instructions, UUID classId,
            String className, UUID subjectId, String subjectName, List<SectionRef> sections, Instant opensAt,
            Instant closesAt, int durationMinutes, int attemptsAllowed, boolean shuffleQuestions,
            boolean shuffleOptions, ShowAnswers showAnswers, TestStatus status, String state, Instant publishedAt,
            Instant resultsReleasedAt, String releasedByName, String createdByName, List<TestQuestionView> questions,
            BigDecimal totalMarks, int attempts, long version, boolean canEdit) {
    }

    public record AttemptRef(UUID id, int attemptNo, Instant startedAt, Instant submittedAt, boolean autoSubmitted,
            BigDecimal score, int pendingMarking) {
    }

    /** {@code status}: NOT_STARTED, IN_PROGRESS, SUBMITTED or MARKING (short answers to mark). */
    public record StudentResult(UUID studentId, String studentName, String admissionNo, Integer rollNo,
            String sectionLabel, String status, UUID bestAttemptId, BigDecimal score, BigDecimal maxScore,
            BigDecimal percent, int pendingMarking, List<AttemptRef> attempts) {
    }

    /** How a question went: answers, fully correct answers and the average marks (difficulty analysis). */
    public record QuestionStat(UUID testQuestionId, int position, String text, QuestionType type,
            Difficulty difficulty, BigDecimal marks, int answered, int correct, BigDecimal averageMarks,
            BigDecimal percentCorrect, int pendingMarking) {
    }

    /** {@code releaseBlocker}: why the results cannot be released yet (null when they can). */
    public record TestResults(UUID testId, String title, String state, boolean released, Instant resultsReleasedAt,
            BigDecimal totalMarks, int studentCount, int attempted, int pendingMarking, BigDecimal averagePercent,
            BigDecimal highestPercent, BigDecimal lowestPercent, List<StudentResult> students,
            List<QuestionStat> questions, boolean canEdit, String releaseBlocker) {
    }

    public record AnswerReview(UUID answerId, int position, UUID testQuestionId, QuestionType type, String text,
            BigDecimal maxMarks, List<OptionView> options, List<UUID> selectedOptionIds, String response,
            String answerText, String explanation, BigDecimal marks, boolean autoMarked, String feedback,
            String markedByName) {
    }

    public record AttemptReview(UUID attemptId, UUID testId, String testTitle, UUID studentId, String studentName,
            String sectionLabel, int attemptNo, Instant startedAt, Instant deadlineAt, Instant submittedAt,
            boolean autoSubmitted, BigDecimal score, BigDecimal maxScore, int pendingMarking,
            List<AnswerReview> answers, boolean canMark) {
    }

    // ------------------------------------------------------------------ students and parents

    public record StudyMaterial(UUID id, String title, String notes, String linkUrl, UUID subjectId,
            String subjectName, String topicTitle, LocalDate publishOn, String createdByName, List<FileRef> files) {
    }

    /**
     * A test as a student or parent sees it. {@code state}: UPCOMING, OPEN or CLOSED. The score fields are null
     * until the teacher releases the results.
     */
    public record StudyTest(UUID id, String title, UUID subjectId, String subjectName, Instant opensAt,
            Instant closesAt, int durationMinutes, int questions, BigDecimal totalMarks, int attemptsAllowed,
            int attemptsUsed, String state, UUID inProgressAttemptId, boolean canStart, boolean submitted,
            boolean released, BigDecimal score, BigDecimal maxScore, BigDecimal percent, UUID reviewAttemptId) {
    }

    public record Study(UUID studentId, String studentName, String classLabel, LocalDate today,
            List<StudyMaterial> material, List<StudyTest> tests) {
    }

    public record AttemptSummary(UUID id, int attemptNo, Instant startedAt, Instant submittedAt,
            boolean autoSubmitted, BigDecimal score, BigDecimal maxScore) {
    }

    public record TestIntro(StudyTest test, String instructions, List<AttemptSummary> attempts) {
    }

    public record LearnerOption(UUID id, String text) {
    }

    public record LearnerQuestion(UUID answerId, int position, QuestionType type, String text, BigDecimal marks,
            List<LearnerOption> options, List<UUID> selectedOptionIds, String response, boolean answered) {
    }

    /** One question after release; the correct answer and explanation only when the teacher shows answers. */
    public record LearnerReviewItem(int position, QuestionType type, String text, BigDecimal maxMarks,
            List<OptionView> options, List<UUID> selectedOptionIds, String response, String correctAnswer,
            String explanation, BigDecimal marks, String feedback) {
    }

    /**
     * An attempt as its student sees it: the questions while it runs (one per screen in the app), the clock
     * ({@code secondsLeft} counted by the server), and after release the score and, if shown, the answers.
     */
    public record LearnerAttempt(UUID id, UUID testId, String testTitle, String subjectName, int attemptNo,
            Instant startedAt, Instant deadlineAt, Instant serverNow, long secondsLeft, boolean submitted,
            boolean autoSubmitted, Instant submittedAt, int answered, int total, List<LearnerQuestion> questions,
            boolean released, BigDecimal score, BigDecimal maxScore, List<LearnerReviewItem> review) {
    }
}
