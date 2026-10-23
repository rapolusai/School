package com.akshara.assist;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.akshara.lessons.BloomLevel;
import com.akshara.lessons.Difficulty;
import com.akshara.lessons.QuestionType;

/** What the assistance API returns (docs/api/phase-3-assist.md). */
public final class AssistViews {

    private AssistViews() {
    }

    // ------------------------------------------------------------------ settings and usage

    public record WeightsView(int attendance30, int attendance90, int marksTrend, int feeDues, int homework,
            int threshold) {
    }

    /**
     * The school's settings with what the caller needs to show the feature: the provider's name, that it is
     * simulated, and this month's use against the limit.
     */
    public record SettingsView(boolean enabled, boolean remarksEnabled, boolean earlyWarningEnabled,
            boolean papersEnabled, boolean writingEnabled, int monthlyLimit, String language, WeightsView weights,
            boolean saved, Instant updatedAt, String provider, boolean simulated, long usedThisMonth,
            Instant lastRunAt, Integer lastRunStudents, Integer lastRunListed) {
    }

    public record UsageRow(UUID id, Instant at, UUID userId, String userName, AssistFeature feature, String provider,
            int tokenEstimate, String requestHash) {
    }

    public record FeatureCount(AssistFeature feature, long calls, long tokens) {
    }

    /** A month's usage: calls against the limit, per feature, and the latest rows. */
    public record UsageView(String month, int limit, long used, long tokens, List<FeatureCount> byFeature,
            List<UsageRow> rows) {
    }

    // ------------------------------------------------------------------ remark drafts

    public record SubjectRef(UUID id, String name) {
    }

    /**
     * A draft for one student, overall ({@code subjectId} null) or for a subject. {@code inputs} and
     * {@code inputLines} are exactly what was sent to the provider.
     */
    public record RemarkDraftView(UUID id, UUID studentId, String studentName, UUID subjectId, String subjectName,
            RemarkTone tone, String language, String provider, boolean simulated, String draftText,
            String acceptedText, String status, Map<String, String> inputs, List<String> inputLines,
            String createdByName, String acceptedByName, Instant acceptedAt, Instant updatedAt, long version) {
    }

    /** A student of the section with the remark on their report card and their drafts. */
    public record RemarkStudent(UUID studentId, String fullName, Integer rollNo, String currentRemark,
            RemarkDraftView draft, List<RemarkDraftView> subjectDrafts) {
    }

    /** The section's remark sheet for a term. {@code canAccept}: the caller may write the section's remarks. */
    public record RemarkSheet(UUID termId, String termName, UUID sectionId, String sectionLabel, boolean canAccept,
            String language, List<SubjectRef> subjects, List<RemarkStudent> students) {
    }

    // ------------------------------------------------------------------ early warning

    /** One signal's contribution to the score; {@code detail} says it in plain words. */
    public record Reason(String signal, int points, Double value, String detail) {
    }

    public record NoteView(UUID id, String authorName, String body, Instant createdAt) {
    }

    public record PersonRef(UUID id, String name) {
    }

    public record WatchRow(UUID id, UUID studentId, String studentName, UUID sectionId, String classLabel, int score,
            RiskLevel level, List<Reason> reasons, Instant computedAt, WatchStatus status, UUID assignedToId,
            String assignedToName, Instant parentInformedAt, long notes) {
    }

    public record WatchDetail(WatchRow entry, List<NoteView> notes) {
    }

    /** The watch list with the last run and who can be assigned (staff holding assist.use). */
    public record WatchList(Instant lastRunAt, Integer lastRunStudents, Integer lastRunListed, int high, int medium,
            int low, List<PersonRef> assignees, List<WatchRow> entries) {
    }

    public record RunResult(Instant at, int students, int listed) {
    }

    // ------------------------------------------------------------------ draft papers

    public record BlueprintSection(String name, int questions, BigDecimal marksEach) {
    }

    /**
     * How a paper is built: its sections, the difficulty mix (percentages that add up to 100), the Bloom's levels
     * to draw from (empty for any) and the syllabus topics (chapters) to draw from (empty for the whole subject).
     */
    public record Blueprint(List<BlueprintSection> sections, int easyPercent, int mediumPercent, int hardPercent,
            List<BloomLevel> bloomLevels, List<UUID> topicIds) {
    }

    public record PaperQuestion(UUID questionId, String text, QuestionType type, Difficulty difficulty,
            BloomLevel bloomLevel, BigDecimal marks, List<String> options) {
    }

    public record PaperSection(String name, String instructions, BigDecimal marksEach, int wanted,
            List<PaperQuestion> questions) {
    }

    /** A draft paper, unsaved ({@code id} null) or saved. {@code warnings} say where the bank fell short. */
    public record PaperView(UUID id, UUID classId, String className, UUID subjectId, String subjectName,
            String title, int durationMinutes, BigDecimal totalMarks, String instructions, Blueprint blueprint,
            List<PaperSection> sections, String provider, boolean simulated, List<String> warnings,
            String createdByName, Instant updatedAt, long version) {
    }

    public record PaperRow(UUID id, String title, String className, String subjectName, BigDecimal totalMarks,
            int durationMinutes, String createdByName, Instant updatedAt) {
    }

    // ------------------------------------------------------------------ writing

    public record WritingResult(String text, String provider, boolean simulated, int tokenEstimate) {
    }

    // ------------------------------------------------------------------ reports

    public record AtRiskClassRow(UUID sectionId, String classLabel, int students, int high, int medium, int low) {
    }

    public record AtRiskReport(Instant computedAt, UUID classId, int total, int high, int medium, int low,
            List<AtRiskClassRow> classes, List<WatchRow> entries) {
    }

    public record AcceptedRow(String teacherName, String month, long drafted, long accepted) {
    }

    public record AcceptedReport(String from, String to, long drafted, long accepted, List<AcceptedRow> rows) {
    }
}
