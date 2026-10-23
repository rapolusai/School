package com.akshara.assist;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.akshara.assist.AssistViews.Blueprint;
import com.akshara.assist.AssistViews.PaperSection;

/** What the assistance API accepts (docs/api/phase-3-assist.md). */
public final class AssistForms {

    /** The most students one drafting call takes (the web app drafts a section in batches to show progress). */
    public static final int MAX_BATCH = 60;

    private AssistForms() {
    }

    public record WeightsForm(
            @Min(0) @Max(100) int attendance30,
            @Min(0) @Max(100) int attendance90,
            @Min(0) @Max(100) int marksTrend,
            @Min(0) @Max(100) int feeDues,
            @Min(0) @Max(100) int homework,
            @Min(1) @Max(100) int threshold) {
    }

    public record SettingsForm(
            boolean enabled,
            boolean remarksEnabled,
            boolean earlyWarningEnabled,
            boolean papersEnabled,
            boolean writingEnabled,
            @Min(0) @Max(100000) int monthlyLimit,
            @NotNull @Pattern(regexp = "en|hi") String language,
            @NotNull @Valid WeightsForm weights) {
    }

    /**
     * Draft remarks for students of a section in a term: every active student when {@code studentIds} is empty,
     * overall when {@code subjectId} is null, else for that subject.
     */
    public record DraftRemarksForm(
            @NotNull UUID termId,
            @NotNull UUID sectionId,
            @NotNull RemarkTone tone,
            @Size(max = MAX_BATCH) List<UUID> studentIds,
            UUID subjectId) {
    }

    public record RemarkTextForm(@NotBlank @Size(max = 500) String text) {
    }

    public record AssignForm(UUID userId) {
    }

    public record StatusForm(@NotNull WatchStatus status) {
    }

    public record NoteForm(@NotBlank @Size(max = 1000) String body) {
    }

    public record PaperDraftForm(
            @NotNull UUID classId,
            @NotNull UUID subjectId,
            @NotBlank @Size(max = 160) String title,
            @Min(10) @Max(240) int durationMinutes,
            @NotNull Blueprint blueprint) {
    }

    /** A paper as the teacher edited it, to save (or update with {@code version}). */
    public record PaperSaveForm(
            @NotNull UUID classId,
            @NotNull UUID subjectId,
            @NotBlank @Size(max = 160) String title,
            @Min(10) @Max(240) int durationMinutes,
            @Size(max = 2000) String instructions,
            @NotNull Blueprint blueprint,
            @NotNull @Size(min = 1, max = 10) List<PaperSection> sections,
            Long version) {
    }

    public record ImproveForm(@NotBlank @Size(max = 4000) String text) {
    }
}
