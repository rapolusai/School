package com.akshara.frontoffice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response bodies of the front office API (docs/api/phase-2-frontoffice.md). Phone numbers are masked in lists. */
public final class FrontOfficeViews {

    private FrontOfficeViews() {
    }

    /** A member of staff a visitor may come to meet, or a call or enquiry may be assigned to. */
    public record StaffRef(UUID id, String name, String designation) {
    }

    /** A student for the office's pass form. */
    public record StudentRef(UUID id, String fullName, String admissionNo, String className) {
    }

    /** Why a visitor matched the blocklist. */
    public record BlockMatch(UUID id, String reason) {
    }

    /** {@code phone} (the full number) is given on the detail view only, for calling back; lists mask it. */
    public record VisitorView(UUID id, String name, String phoneMasked, String phone, VisitorPurpose purpose,
            String purposeNote, UUID toMeetUserId, String toMeetName, int peopleCount, IdType idType, String badgeNo,
            UUID photoFileId, VisitorStatus status, LocalDate expectedOn, Instant checkedInAt, Instant checkedOutAt,
            String addedByName, String checkedInByName, String checkedOutByName, BlockMatch blocked) {
    }

    /** One day's visitors, who is inside now, and the visitors expected today. */
    public record VisitorDay(LocalDate date, List<VisitorView> visitors, int inside, List<VisitorView> expected) {
    }

    public record BlockedView(UUID id, String name, String phoneMasked, String reason, String addedByName,
            Instant createdAt) {
    }

    /** An early pickup pass. The OTP itself is never part of any view. */
    public record GatePassView(UUID id, UUID studentId, String studentName, String admissionNo, String className,
            PassSource source, String requestedByName, Instant pickupAt, String reason, CollectorKind collectorKind,
            String collectorName, String collectorPhoneMasked, PassStatus status, Instant otpExpiresAt,
            int attemptsLeft, String guardianName, String guardianPhoneMasked, String approvedByName,
            Instant approvedAt, String decisionNote, String releasedByName, Instant releasedAt,
            String collectedByName, Instant createdAt, boolean canCancel, boolean canDecide, boolean canRelease) {
    }

    public record PickupPersonView(UUID id, UUID studentId, String studentName, String name, String relation,
            String phoneMasked, UUID photoFileId, String addedByName, Instant createdAt) {
    }

    /** A parent's view of one child: the passes (latest first) and the authorised pickup persons. */
    public record FamilyPickup(UUID studentId, String studentName, String className, boolean canRequest,
            List<GatePassView> passes, List<PickupPersonView> persons) {
    }

    /** The gate's screen: today's approved passes waiting for release, who is inside and who is expected. */
    public record GateScreen(LocalDate date, List<GatePassView> passes, List<VisitorView> inside,
            List<VisitorView> expected) {
    }

    /** The pickup persons the gate may check for one student. */
    public record StudentPickupPersons(UUID studentId, String studentName, String className,
            List<PickupPersonView> persons) {
    }

    public record CallView(UUID id, CallDirection direction, String callerName, String phoneMasked, String about,
            String outcome, LocalDate followUpOn, UUID assignedToUserId, String assignedToName, CallStatus status,
            String loggedByName, Instant calledAt) {
    }

    public record EnquiryView(UUID id, EnquirySource source, EnquiryTopic topic, String name, String phoneMasked,
            String childName, String className, String details, EnquiryStatus status, String outcome,
            LocalDate followUpOn, UUID assignedToUserId, String assignedToName, UUID applicationId,
            String loggedByName, Instant enquiredAt) {
    }

    /** What is to be followed up: open calls and enquiries with a date, overdue first. */
    public record FollowUpQueue(LocalDate today, List<FollowUp> items, int dueToday, int overdue) {
    }

    public record FollowUp(String kind, UUID id, String name, String about, LocalDate followUpOn, long daysOverdue,
            String assignedToName, String phoneMasked) {
    }

    public record CourierView(UUID id, CourierDirection direction, String item, String sender, String receiver,
            LocalDate itemDate, String carrier, String trackingNo, String receivedBy, String note,
            String loggedByName, Instant createdAt) {
    }

    /* ------------------------------------------------------------------ reports */

    public record DayCount(LocalDate date, long count, long people) {
    }

    public record NamedCount(String name, long count) {
    }

    public record VisitorsReport(LocalDate from, LocalDate to, long total, long people, List<DayCount> byDay,
            List<NamedCount> byPurpose, List<VisitorView> rows) {
    }

    public record PassDay(LocalDate date, long requested, long approved, long released, long rejected, long cancelled,
            long expired, long locked) {
    }

    public record PassesReport(LocalDate from, LocalDate to, long total, List<PassDay> byDay, List<GatePassView> rows) {
    }

    public record EnquiriesReport(LocalDate from, LocalDate to, long total, List<NamedCount> bySource,
            List<NamedCount> byTopic, List<NamedCount> byOutcome, List<EnquiryView> rows) {
    }

    public record FollowUpsReport(LocalDate asOf, int overdue, List<FollowUp> rows) {
    }
}
