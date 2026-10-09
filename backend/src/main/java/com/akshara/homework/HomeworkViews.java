package com.akshara.homework;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.files.StoredFileInfo;

/** What the homework API returns. Dates are school days in India; times are instants. */
public final class HomeworkViews {

    private HomeworkViews() {
    }

    public record YearRef(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {

        static YearRef of(YearInfo y) {
            return y == null ? null : new YearRef(y.id(), y.name(), y.startsOn(), y.endsOn());
        }
    }

    public record SectionRef(UUID id, String label) {
    }

    /** A stored file; download it from /api/files/{id}. */
    public record FileRef(UUID id, String name, String contentType, int size) {

        static FileRef of(StoredFileInfo f) {
            return new FileRef(f.id(), f.name(), f.contentType(), f.size());
        }
    }

    public record SubjectOption(UUID id, String name) {
    }

    /** A section the person may set homework for, with the subjects they may set it in. */
    public record SectionOption(UUID id, String label, UUID classId, List<SubjectOption> subjects) {
    }

    public record HomeworkOptions(YearRef academicYear, LocalDate today, int maxAttachments, int maxFileBytes,
            List<SectionOption> sections) {
    }

    /**
     * How a homework is going: students in its sections, how many submitted (late among them), have not submitted,
     * were reviewed, were asked to redo, and are waiting for review.
     */
    public record Counts(int students, int submitted, int late, int missing, int reviewed, int needsRedo,
            int waiting) {
    }

    public record HomeworkRow(UUID id, String title, UUID subjectId, String subjectName, List<SectionRef> sections,
            LocalDate assignedOn, LocalDate dueOn, boolean onlineSubmission, int attachments, Counts counts,
            String createdByName, boolean canEdit) {
    }

    public record HomeworkPage(List<HomeworkRow> items, int page, int size, long total) {
    }

    public record HomeworkDetail(UUID id, String title, String instructions, UUID subjectId, String subjectName,
            List<SectionRef> sections, LocalDate assignedOn, LocalDate dueOn, boolean onlineSubmission,
            List<FileRef> attachments, String createdByName, Instant createdAt, Instant updatedAt, Counts counts,
            boolean canEdit, boolean canDelete, LocalDate today) {
    }

    /** One student on the tracker. {@code status} is MISSING when nothing has been submitted. */
    public record TrackerRow(UUID studentId, String fullName, String admissionNo, Integer rollNo, UUID sectionId,
            String sectionLabel, String status, UUID submissionId, Instant submittedAt, boolean late, int attempts,
            String body, List<FileRef> files, String grade, String remark, String reviewedByName,
            Instant reviewedAt) {
    }

    public record Tracker(HomeworkDetail homework, Counts counts, List<TrackerRow> rows) {
    }

    public record SubmissionView(UUID id, String body, List<FileRef> files, Instant submittedAt, boolean late,
            int attempts, SubmissionStatus status, String grade, String remark, String reviewedByName,
            Instant reviewedAt) {
    }

    /** A homework on a student's (or their parent's) list. {@code status} is PENDING until something is sent. */
    public record StudentHomeworkRow(UUID id, String title, UUID subjectId, String subjectName, LocalDate assignedOn,
            LocalDate dueOn, boolean onlineSubmission, int attachments, String status, boolean late,
            boolean overdue, Instant submittedAt, String grade) {
    }

    public record StudentHomework(UUID studentId, String studentName, UUID sectionId, String sectionLabel,
            LocalDate today, List<StudentHomeworkRow> items) {
    }

    public record StudentHomeworkDetail(UUID id, UUID studentId, String studentName, String title,
            String instructions, UUID subjectId, String subjectName, LocalDate assignedOn, LocalDate dueOn,
            boolean onlineSubmission, List<FileRef> attachments, String createdByName, SubmissionView submission,
            boolean canSubmit, boolean overdue, int maxFiles, int maxFileBytes, LocalDate today) {
    }

    public record HomeworkSettingsView(boolean remindersEnabled) {
    }
}
