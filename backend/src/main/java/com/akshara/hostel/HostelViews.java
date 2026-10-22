package com.akshara.hostel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response bodies of the hostel API (docs/api/phase-3-hostel.md). Phone numbers are masked wherever they appear. */
public final class HostelViews {

    private HostelViews() {
    }

    /** A member of staff who may be a warden. */
    public record StaffRef(UUID id, String name, String designation) {
    }

    /** A student for the allotment and pass forms. */
    public record StudentRef(UUID id, String fullName, String admissionNo, String className) {
    }

    public record HostelView(UUID id, String name, HostelType type, UUID wardenUserId, String wardenName,
            String address, boolean active, long blocks, long rooms, long beds, long occupied, int occupancyPercent,
            boolean canManage, boolean canWarden) {
    }

    public record BlockView(UUID id, UUID hostelId, String name, int floors, long rooms) {
    }

    public record RoomView(UUID id, UUID hostelId, UUID blockId, String blockName, int floor, String number,
            int capacity, RoomType type, long monthlyFeePaise, boolean active, long beds, long occupied) {
    }

    /** A bed on the occupancy board, with the boarder in it (or null when free). */
    public record BedView(UUID id, String label, UUID allotmentId, UUID studentId, String studentName,
            String className, LocalDate since) {
    }

    public record RoomCard(UUID id, UUID blockId, String blockName, int floor, String number, int capacity,
            RoomType type, long monthlyFeePaise, boolean active, List<BedView> beds, int freeBeds, long openComplaints) {
    }

    /** The occupancy board of one hostel: rooms as cards with their beds. */
    public record OccupancyBoard(UUID hostelId, String hostelName, HostelType type, long beds, long occupied,
            long free, int occupancyPercent, List<BlockView> blocks, List<RoomCard> rooms) {
    }

    public record HostelDetail(HostelView hostel, List<BlockView> blocks, List<RoomView> rooms) {
    }

    public record AllotmentView(UUID id, UUID studentId, String studentName, String admissionNo, String className,
            UUID hostelId, String hostelName, UUID roomId, String roomNumber, String blockName, UUID bedId,
            String bedLabel, LocalDate fromDate, LocalDate toDate, boolean guardianConsent, AllotmentStatus status,
            String vacateReason, String allottedByName, String vacatedByName, long monthlyFeePaise) {
    }

    /** A boarder on the list: their bed now. */
    public record BoarderView(UUID studentId, String studentName, String admissionNo, String className,
            UUID allotmentId, UUID hostelId, String hostelName, String blockName, String roomNumber, String bedLabel,
            LocalDate since, boolean guardianConsent, String guardianName, String guardianPhoneMasked) {
    }

    /**
     * A student's hostel standing, for the student page (students.read) and the family app: whether they board, and
     * their room history.
     */
    public record StudentBoarding(UUID studentId, boolean boarder, AllotmentView current, List<AllotmentView> history) {
    }

    /* ------------------------------------------------------------------ mess */

    public record MenuSlot(int dayOfWeek, Meal meal, String dish, boolean veg) {
    }

    public record WeeklyMenu(UUID hostelId, String hostelName, List<MenuSlot> items, boolean canEdit) {
    }

    public record MealCountView(Meal meal, int headCount, String markedByName) {
    }

    public record MessDay(UUID hostelId, LocalDate date, long boarders, List<MealCountView> counts) {
    }

    /* ------------------------------------------------------------------ passes */

    public record PassView(UUID id, UUID studentId, String studentName, String admissionNo, String className,
            UUID hostelId, String hostelName, PassKind kind, HostelPassSource source, String requestedByName,
            LocalDate fromDate, LocalDate toDate, Instant returnBy, String reason, HostelPassStatus status,
            String decidedByName, Instant decidedAt, String decisionNote, Instant outAt, String outByName,
            Instant inAt, String inByName, boolean late, Instant createdAt, boolean canCancel, boolean canDecide,
            boolean canMarkOut, boolean canMarkIn) {
    }

    /* ------------------------------------------------------------------ roll call */

    public record RollCallRow(UUID studentId, String studentName, String admissionNo, String className,
            String roomNumber, String bedLabel, RollCallStatus status, String note, UUID passId,
            boolean suggestedOnLeave) {
    }

    /** One night's sheet: every boarder of the hostel, with the mark saved (or the suggestion) and the counts. */
    public record RollCallSheet(UUID hostelId, String hostelName, LocalDate night, boolean taken, String takenByName,
            Instant takenAt, int present, int onLeave, int absent, List<RollCallRow> rows, boolean canMark) {
    }

    public record RollCallNight(UUID id, LocalDate night, String takenByName, Instant takenAt, int present,
            int onLeave, int absent, List<String> absentees) {
    }

    public record RollCallRegister(UUID hostelId, String hostelName, LocalDate from, LocalDate to,
            List<RollCallNight> nights) {
    }

    public record BoarderNights(UUID studentId, String studentName, String className, int present, int onLeave,
            int absent) {
    }

    public record RollCallSummary(UUID hostelId, String hostelName, String month, int nightsTaken,
            List<BoarderNights> boarders) {
    }

    public record AlertView(UUID id, UUID hostelId, String hostelName, UUID studentId, String studentName,
            String className, AlertKind kind, LocalDate day, String message, boolean resolved, String resolvedByName,
            Instant resolvedAt, String resolutionNote, Instant createdAt) {
    }

    /* ------------------------------------------------------------------ complaints */

    public record ComplaintView(UUID id, UUID hostelId, String hostelName, UUID roomId, String roomNumber,
            String blockName, ComplaintCategory category, String description, ComplaintStatus status,
            String raisedByName, String resolutionNote, Instant resolvedAt, Instant createdAt) {
    }

    /* ------------------------------------------------------------------ the family app */

    /** What a parent sees about a boarding child, or {@code boarder = false} for a day scholar. */
    public record FamilyHostel(UUID studentId, String studentName, String className, boolean boarder,
            String hostelName, String blockName, String roomNumber, String bedLabel, String wardenName,
            PassView nextPass, LocalDate lastRollCallNight, RollCallStatus lastRollCallStatus, boolean canRequest,
            List<PassView> passes) {
    }

    /* ------------------------------------------------------------------ reports */

    public record HostelOccupancy(UUID hostelId, String hostelName, HostelType type, String wardenName, long rooms,
            long beds, long occupied, long free, int occupancyPercent) {
    }

    public record OccupancyReport(LocalDate asOf, long beds, long occupied, long free, int occupancyPercent,
            List<HostelOccupancy> hostels, List<BoarderView> rows) {
    }

    public record RollCallReportRow(LocalDate night, UUID hostelId, String hostelName, int present, int onLeave,
            int absent, List<String> absentees) {
    }

    public record RollCallReport(LocalDate from, LocalDate to, int nights, long present, long onLeave, long absent,
            List<RollCallReportRow> rows) {
    }

    public record NamedCount(String name, long count) {
    }

    public record OutingsReport(String month, long total, long outings, long homeLeaves, long late,
            List<NamedCount> byStatus, List<PassView> rows) {
    }

    public record BoarderDues(UUID studentId, String studentName, String admissionNo, String className,
            String hostelName, String roomNumber, long netPaise, long paidPaise, long balancePaise,
            long overduePaise) {
    }

    public record BoarderDuesReport(LocalDate asOf, long boarders, long balancePaise, long overduePaise,
            List<BoarderDues> rows) {
    }
}
