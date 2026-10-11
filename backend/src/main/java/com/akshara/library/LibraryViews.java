package com.akshara.library;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response bodies of the library API (docs/api/phase-2-library.md). Money is in paise. */
public final class LibraryViews {

    private LibraryViews() {
    }

    // ------------------------------------------------------------------ members

    /**
     * A borrower. {@code code} is the admission number or employee code; {@code detail} the class ("Class 5 A") or the
     * designation. {@code active} is false for a student who left or staff who left.
     */
    public record Member(MemberType type, UUID id, String name, String code, String detail, boolean active) {
    }

    // ------------------------------------------------------------------ catalogue

    public record TitleRow(UUID id, String isbn13, String title, String subtitle, String authors, String subject,
            String callNumber, String shelfLocation, int copies, int available, int issued) {
    }

    /** Figures for the top of the catalogue: titles, copies, copies out and loans past their due date. */
    public record CatalogueStats(long titles, long copies, long issued, long overdue) {
    }

    public record TitlePage(List<TitleRow> items, int page, int size, long total, CatalogueStats stats) {
    }

    /** Who has a copy (library.manage only). */
    public record CopyLoan(UUID loanId, Member member, LocalDate issuedOn, LocalDate dueOn, boolean overdue) {
    }

    /** Who a copy on the hold shelf is kept for (library.manage only). */
    public record HeldFor(UUID reservationId, Member member, LocalDate holdUntil) {
    }

    public record CopyView(UUID id, UUID titleId, String accessionNo, CopyStatus status, String statusNote,
            Long pricePaise, CopySource source, LocalDate acquiredOn, CopyLoan loan, HeldFor heldFor) {
    }

    public record QueueEntry(UUID reservationId, int position, Member member, ReservationStatus status,
            LocalDate holdUntil, Instant createdAt) {
    }

    /** A title with its copies. {@code queue} (the holds) is empty for staff without library.manage. */
    public record TitleDetail(UUID id, String isbn13, String isbn10, String title, String subtitle, String authors,
            String publisher, Integer publicationYear, String edition, String language, String subject,
            String callNumber, String shelfLocation, int copiesTotal, int available, long timesBorrowed,
            List<CopyView> copies, List<QueueEntry> queue) {
    }

    public record TitleRef(UUID id, String title, String authors, int copies) {
    }

    /** Details to fill a new title from: the school's own catalogue (CATALOGUE) or an online source (ONLINE). */
    public record Suggestion(String source, String title, String subtitle, String authors, String publisher,
            Integer publicationYear, String edition, String language, String subject, String callNumber) {
    }

    /**
     * An ISBN checked offline. {@code problem} says what is wrong when it is not valid; {@code existing} is the title
     * the school already has with this ISBN.
     */
    public record IsbnLookup(String input, boolean valid, String problem, String isbn13, String isbn10,
            TitleRef existing, Suggestion suggestion) {
    }

    /** A copy for barcode and spine labels. */
    public record LabelCopy(UUID id, String accessionNo, UUID titleId, String title, String authors,
            String callNumber, CopyStatus status) {
    }

    /** A scanned or typed accession number at the desk: the copy, its title, and its loan or hold if any. */
    public record CopyLookup(CopyView copy, TitleRef title, LoanView loan) {
    }

    public record ImportError(int row, String column, String message) {
    }

    /** One row as it will be imported: a new title, or copies added to a title already in the catalogue. */
    public record ImportPreviewRow(int row, String title, String isbn13, String authors, int copies,
            boolean existingTitle, String accessionNo) {
    }

    /**
     * {@code errors} lists at most 500 problems; {@code invalidRows} counts every row with at least one. A real run
     * writes every row or none.
     */
    public record ImportResult(boolean dryRun, boolean committed, int totalRows, int validRows, int invalidRows,
            int titlesCreated, int copiesCreated, List<ImportError> errors, List<String> ignoredColumns,
            List<ImportPreviewRow> preview) {
    }

    // ------------------------------------------------------------------ circulation

    /**
     * A loan. {@code daysOverdue} counts calendar days past the due date; {@code fineSoFarPaise} is the overdue fine if
     * the copy came back today (school days only, capped).
     */
    public record LoanView(UUID id, UUID copyId, String accessionNo, UUID titleId, String title, String authors,
            Member member, LocalDate issuedOn, LocalDate dueOn, int renewals, int renewalsLeft, LoanStatus status,
            LocalDate returnedOn, boolean overdue, int daysOverdue, long fineSoFarPaise, String issuedByName,
            String returnedByName) {
    }

    public record LoanPage(List<LoanView> items, int page, int size, long total) {
    }

    public record FineView(UUID id, UUID loanId, UUID titleId, String title, String accessionNo, Member member,
            FineKind kind, long amountPaise, Integer daysLate, String note, FineStatus status, String receiptNo,
            LibraryPaymentMode paymentMode, String paymentReference, Instant paidAt, String collectedByName,
            String waiveReason, Instant waivedAt, String waivedByName, Instant createdAt) {
    }

    public record FinePage(List<FineView> items, int page, int size, long total, long totalDuePaise) {
    }

    /** A hold. {@code position} is 1 for the member first in the title's queue. */
    public record HoldView(UUID id, UUID titleId, String title, String authors, Member member,
            ReservationStatus status, int position, String copyAccessionNo, LocalDate holdUntil, Instant readyAt,
            Instant createdAt, String createdByName) {
    }

    /** A copy taken back: the closed loan, any fines it raised, and the hold it is now kept for (put it aside). */
    public record ReturnResult(LoanView loan, List<FineView> fines, HoldView heldFor) {
    }

    public record RuleView(MemberType memberType, int maxBooks, int loanDays, int maxRenewals, long finePerDayPaise,
            long maxFinePaise, boolean custom) {
    }

    /**
     * Everything the desk shows about a member: their rule, books out, fines due, holds and recent history.
     * {@code blockedReason} explains why nothing more can be lent (limit reached, member left), or is null.
     */
    public record MemberAccount(Member member, RuleView rule, List<LoanView> loans, List<FineView> finesDue,
            long finesDuePaise, List<HoldView> holds, List<LoanView> history, boolean canBorrow,
            String blockedReason) {
    }

    /** Fines paid together at the desk, under one receipt number. */
    public record FineReceipt(String receiptNo, String schoolName, Member member, List<FineView> fines,
            long totalPaise, LibraryPaymentMode mode, String reference, Instant paidAt, String collectedByName) {
    }

    // ------------------------------------------------------------------ setup

    /** {@code nextAccessionNo} is the number the next new copy gets. */
    public record SettingsView(String accessionPrefix, String nextAccessionNo, int holdDays,
            boolean remindersEnabled) {
    }

    // ------------------------------------------------------------------ stock verification

    /** {@code expected}: copies that should be on the shelves (available, kept for a hold, or damaged). */
    public record StockCheckRow(UUID id, StockCheckStatus status, String note, String startedByName,
            Instant startedAt, String closedByName, Instant closedAt, long seen, long expected, long missing) {
    }

    public record MissingCopy(UUID copyId, String accessionNo, UUID titleId, String title, String callNumber,
            String shelfLocation, CopyStatus status) {
    }

    public record SeenCopy(String accessionNo, String title, Instant seenAt) {
    }

    public record StockCheckDetail(StockCheckRow check, List<MissingCopy> missing, List<SeenCopy> latest) {
    }

    /**
     * One scan. {@code warning} is set when the copy should not be on the shelf: ISSUED (on loan), LOST or WITHDRAWN.
     */
    public record ScanResult(String accessionNo, String title, CopyStatus status, boolean alreadySeen, String warning,
            long seen, long expected) {
    }

    // ------------------------------------------------------------------ reports

    public record OverdueReport(LocalDate asOf, List<LoanView> rows, long totalFinePaise) {
    }

    public record IssuedReport(LocalDate date, List<LoanView> rows) {
    }

    public record PopularRow(UUID titleId, String title, String authors, long loans, int copies) {
    }

    public record PopularReport(LocalDate from, LocalDate to, List<PopularRow> rows) {
    }

    public record MemberHistory(Member member, List<LoanView> loans, List<FineView> fines, long finesDuePaise,
            long finesPaidPaise) {
    }

    /** The figures for the librarian's dashboard card. */
    public record LibrarySummary(long issued, long overdue, long dueToday, long holdsReady, long finesDuePaise) {
    }

    // ------------------------------------------------------------------ families

    public record FamilyLoan(String title, String authors, String accessionNo, LocalDate issuedOn, LocalDate dueOn,
            boolean overdue, int daysOverdue, int renewals, long fineSoFarPaise) {
    }

    public record FamilyHold(String title, ReservationStatus status, int position, LocalDate holdUntil) {
    }

    public record FamilyFine(String title, FineKind kind, long amountPaise, FineStatus status, Instant createdAt,
            String receiptNo) {
    }

    /** One borrower as their family (or they themselves) see them. */
    public record FamilyMember(MemberType type, UUID id, String name, String className, List<FamilyLoan> loans,
            List<FamilyHold> holds, List<FamilyFine> fines, long finesDuePaise) {
    }

    /** The signed-in person's library: a parent's children, a student's own account, or a staff member's own. */
    public record MyLibrary(List<FamilyMember> members) {
    }
}
