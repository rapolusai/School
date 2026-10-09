package com.akshara.fees;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.akshara.platform.SchoolProfile;
import com.akshara.students.StudentStatus;

/** Response bodies of the fees API (docs/api/phase-1-fees.md). Amounts are in paise. */
public final class FeeViews {

    private FeeViews() {
    }

    // ------------------------------------------------------------------ setup

    public record HeadView(UUID id, String name, FeeHeadKind kind, boolean oneTime, boolean active, int displayOrder,
            boolean inUse) {
    }

    /** One class of a year in the structures list; {@code id} and {@code status} are null when it has none yet. */
    public record StructureSummary(UUID id, UUID classId, String className, String status, long totalPaise,
            int instalmentCount, Instant publishedAt, long studentsWithDues) {
    }

    public record StructureHead(UUID headId, String name, FeeHeadKind kind, boolean oneTime, long amountPaise) {
    }

    public record Share(UUID headId, long amountPaise) {
    }

    public record InstalmentView(UUID id, int seq, String label, LocalDate dueDate, long amountPaise,
            List<Share> shares) {
    }

    public record StructureView(UUID id, UUID academicYearId, String academicYearName, UUID classId, String className,
            String status, Instant publishedAt, List<StructureHead> heads, List<InstalmentView> instalments,
            long totalPaise, long studentsWithDues) {
    }

    /** What saving or publishing did to students' dues. */
    public record DuesChange(int studentsCreated, int studentsUpdated, int paidCellsKept) {

        static final DuesChange NONE = new DuesChange(0, 0, 0);
    }

    public record StructureResult(StructureView structure, DuesChange dues) {
    }

    public record LateFeeRuleView(LateFeeMode mode, int graceDays, long flatPaise, long perDayPaise, long capPaise) {
    }

    public record HeadRef(UUID id, String name) {
    }

    public record ConcessionView(UUID id, UUID studentId, String studentName, String admissionNo, String className,
            String sectionName, UUID academicYearId, String academicYearName, ConcessionType type,
            ConcessionMode mode, BigDecimal percent, Long fixedPaise, List<HeadRef> heads, String reason,
            String approvedByName, Instant createdAt, String status, Instant revokedAt, String revokedByName,
            String revokeReason, long studentConcessionPaise) {
    }

    // ------------------------------------------------------------------ dues

    public record StudentRef(UUID id, String fullName, String admissionNo, StudentStatus status, String className,
            String sectionName, Integer rollNo) {
    }

    public record HeadDue(UUID dueId, UUID headId, String headName, long grossPaise, long concessionPaise,
            long netPaise, long paidPaise, long balancePaise, DueStatus status) {
    }

    public record InstalmentDue(UUID instalmentId, int seq, String label, LocalDate dueDate, UUID academicYearId,
            String academicYearName, DueStatus status, long grossPaise, long concessionPaise, long netPaise,
            long paidPaise, long balancePaise, long lateFeePaise, boolean lateFeeWaived, long daysOverdue,
            List<HeadDue> heads) {
    }

    public record Totals(long grossPaise, long concessionPaise, long netPaise, long paidPaise, long balancePaise,
            long overduePaise, long lateFeePaise, long payableNowPaise) {
    }

    public record ReceiptSummary(UUID id, String receiptNo, LocalDate receivedOn, Instant receivedAt, UUID studentId,
            String studentName, String admissionNo, String classLabel, PaymentMode mode, String source,
            long amountPaise, long lateFeePaise, String status, String collectedByName) {
    }

    /** A student's fees: every instalment with something on it (oldest first), totals and receipts. */
    public record StudentFees(StudentRef student, List<InstalmentDue> instalments, Totals totals,
            List<ConcessionView> concessions, List<ReceiptSummary> receipts, LateFeeRuleView lateFeeRule,
            LocalDate asOf) {
    }

    /** A student found by the collect screen's search. */
    public record StudentHit(UUID id, String fullName, String admissionNo, StudentStatus status, String className,
            String sectionName, Integer rollNo, String guardianName) {
    }

    // ------------------------------------------------------------------ receipts

    public record ReceiptLine(String kind, String instalmentLabel, String headName, long amountPaise) {
    }

    public record ReceiptView(UUID id, String receiptNo, String financialYear, LocalDate receivedOn,
            Instant receivedAt, UUID studentId, String studentName, String admissionNo, String classLabel,
            PaymentMode mode, String chequeNo, String bankName, String reference, String source,
            String gatewayPaymentId, long amountPaise, long lateFeePaise, String amountInWords, String remarks,
            String collectedByName, String status, Instant cancelledAt, String cancelledByName, String cancelReason,
            List<ReceiptLine> lines, SchoolProfile school) {
    }

    public record ReceiptPage(List<ReceiptSummary> items, int page, int size, long total, long totalPaise) {
    }

    // ------------------------------------------------------------------ online payments

    public record OrderView(UUID id, String gateway, String gatewayOrderId, long amountPaise, String currency,
            String status, UUID studentId, String studentName, List<String> instalments, UUID receiptId,
            String failureReason) {
    }

    /** What the sandbox checkout page shows. */
    public record SandboxCheckout(String gatewayOrderId, UUID orderId, long amountPaise, String currency,
            String schoolName, String studentName, List<String> instalments, String status) {
    }

    /** What a gateway's checkout hands back to the browser: verify it with the order. */
    public record CheckoutResult(UUID orderId, String gatewayOrderId, String status, String gatewayPaymentId,
            String signature) {
    }

    // ------------------------------------------------------------------ reports

    public record CollectionTotal(long amountPaise, long receiptCount) {
    }

    public record Overview(UUID academicYearId, String academicYearName, LocalDate asOf, CollectionTotal today,
            CollectionTotal thisMonth, long outstandingPaise, long overduePaise, long overdueStudents,
            long remainingThisYearPaise, List<ReceiptSummary> recentReceipts) {
    }

    public record ModeTotal(PaymentMode mode, long receiptCount, long amountPaise) {
    }

    public record HeadTotal(UUID headId, String headName, long amountPaise) {
    }

    public record CollectionReport(LocalDate from, LocalDate to, long totalPaise, long receiptCount,
            long cancelledCount, List<ModeTotal> byMode, List<HeadTotal> byHead, long lateFeePaise,
            long advancePaise) {
    }

    public record OutstandingRow(UUID classId, String className, UUID sectionId, String sectionName, long students,
            long netPaise, long paidPaise, long balancePaise, long dueSoFarPaise, long overduePaise) {
    }

    public record OutstandingReport(UUID academicYearId, String academicYearName, LocalDate asOf,
            List<OutstandingRow> rows, OutstandingRow total) {
    }

    public record OverdueRow(UUID studentId, String fullName, String admissionNo, String className,
            String sectionName, String guardianName, String guardianPhone, long overduePaise, long lateFeePaise,
            LocalDate oldestDueDate, long daysOverdue, List<String> instalments, Instant lastReminderAt) {
    }

    public record OverdueReport(UUID academicYearId, String academicYearName, LocalDate asOf, List<OverdueRow> rows,
            long totalOverduePaise) {
    }

    public record ReminderResult(int requested, int skipped) {
    }
}
