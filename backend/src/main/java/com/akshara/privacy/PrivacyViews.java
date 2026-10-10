package com.akshara.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.akshara.students.StudentStatus;

/** Response bodies of the privacy API (docs/api/phase-1-privacy.md). */
public final class PrivacyViews {

    private PrivacyViews() {
    }

    // ------------------------------------------------------------------ notice

    /** The grievance officer. {@code updatedAt} is null on the copy kept with an older notice version. */
    public record Officer(String name, String email, String phone, Instant updatedAt) {
    }

    public record NoticeVersion(int version, Instant publishedAt, String publishedByName, String changeSummary) {
    }

    /**
     * One version of the notice. The current version shows today's grievance officer; an older one shows the officer
     * named when it was published.
     */
    public record Notice(int version, boolean current, String bodyEn, String bodyHi, String changeSummary,
            Instant publishedAt, String publishedByName, Officer grievanceOfficer) {
    }

    /**
     * The notice page for staff: the officer, the current version (null before the first), the text to start the next
     * version from (the current text, or the default template), and every version, newest first.
     */
    public record NoticeAdmin(Officer officer, Notice current, String draftEn, String draftHi, boolean draftIsTemplate,
            List<NoticeVersion> versions) {
    }

    /** What anyone can read without signing in. */
    public record PublicNotice(String schoolName, String schoolCode, int version, int currentVersion,
            Instant publishedAt, String changeSummary, String bodyEn, String bodyHi, Officer grievanceOfficer,
            List<NoticeVersion> versions) {
    }

    // ------------------------------------------------------------------ consent

    /** A purpose's current state: its latest decision, or NONE. {@code current} is true when it was taken against
     * the current notice version. */
    public record PurposeState(PrivacyPurpose purpose, String status, Instant at, Integer noticeVersion,
            ConsentMethod method, boolean current) {
    }

    public record ConsentEntry(UUID id, PrivacyPurpose purpose, ConsentAction action, ConsentMethod method,
            int noticeVersion, String givenByName, String recordedByName, String paperReference, LocalDate signedOn,
            Instant at) {
    }

    /** One child as their parent sees their consent: {@code needsConsent} until essential is agreed for the current
     * notice version. */
    public record ChildConsent(UUID studentId, String fullName, String className, String sectionName,
            StudentStatus status, boolean needsConsent, List<PurposeState> purposes, List<ConsentEntry> history) {
    }

    /**
     * The parent's privacy page. {@code needsConsent} is true when a notice is published and an active child has no
     * essential consent against its current version: the app then shows the notice before anything else.
     */
    public record ParentPrivacy(Notice notice, boolean needsConsent, List<ChildConsent> children) {
    }

    public record StudentConsent(UUID studentId, String fullName, String admissionNo, StudentStatus status,
            Integer noticeVersion, List<PurposeState> purposes, List<ConsentEntry> history) {
    }

    public record ConsentRow(UUID studentId, String fullName, String admissionNo, String className,
            String sectionName, StudentStatus status, List<PurposeState> purposes) {
    }

    /**
     * Students of the current academic year with their consent. {@code activeStudents} and {@code essentialGiven}
     * count the whole year (active students only), for the coverage figure.
     */
    public record ConsentPage(List<ConsentRow> items, int page, int size, long total, Integer noticeVersion,
            long activeStudents, long essentialGiven) {
    }

    // ------------------------------------------------------------------ requests

    public record StaffRef(UUID id, String name) {
    }

    public record RequestRow(UUID id, RequestType type, RequestSubject subject, RequestStatus status,
            RequestResolution resolution, UUID studentId, String studentName, String admissionNo,
            String requesterName, StaffRef assignedTo, Instant createdAt, LocalDate dueOn, long daysLeft,
            boolean overdue, Instant lastActivityAt, ExportStatus exportStatus) {
    }

    public record RequestCounts(long open, long submitted, long inProgress, long closed, long overdue) {
    }

    public record RequestPage(List<RequestRow> items, int page, int size, long total, RequestCounts counts) {
    }

    public record ExportView(UUID id, String fileName, long sizeBytes, ExportStatus status, String createdByName,
            Instant createdAt, Instant expiresAt, Instant deletedAt, int downloadCount, Instant lastDownloadedAt) {
    }

    /** A timeline step. {@code byRequester} marks the parent's own steps (submitted, their replies, downloads). */
    public record EventView(UUID id, RequestEventKind kind, String actorName, boolean byRequester, String body,
            Instant at) {
    }

    /**
     * A data request with its timeline. {@code export} is the file that can be downloaded now, if any;
     * {@code exports} lists every export made. {@code canErase} is true for an open erasure request about a child who
     * has left the school and has not been erased yet. Parents get the same shape without {@code assignedTo}.
     */
    public record RequestDetail(UUID id, RequestType type, RequestSubject subject, RequestStatus status,
            RequestResolution resolution, String details, UUID studentId, String studentName, String admissionNo,
            StudentStatus studentStatus, String requesterName, StaffRef assignedTo, Instant createdAt, LocalDate dueOn,
            long daysLeft, boolean overdue, String closingNote, Instant closedAt, String closedByName,
            Instant erasedAt, boolean canErase, ExportView export, List<ExportView> exports,
            List<EventView> events) {
    }

    /** A file to download: name, type and bytes. */
    public record Download(String fileName, String contentType, byte[] content) {
    }
}
