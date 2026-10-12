package com.akshara.certificates;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response bodies of the certificates API (docs/api/phase-2-certificates.md). Money is in paise. */
public final class CertificateViews {

    private CertificateViews() {
    }

    /** The school as printed at the top of a certificate or on an ID card. */
    public record SchoolHeader(String name, String address, String affiliationNo, String logoText, String city,
            String phone) {
    }

    public record Signatory(String name, String designation) {
    }

    public record SettingsView(boolean saved, String headerName, String headerAddress, String affiliationNo,
            String logoText, String signatoryName, String signatoryDesignation, boolean tcDuesCheck,
            int cardValidMonths, long lostCardFeePaise) {
    }

    /** A type's wording. {@code custom} is true once the school saved its own; {@code nextSerial} is what the next
     * signed certificate of the type gets. */
    public record TemplateView(CertificateType type, String title, String body, String serialPrefix, boolean staff,
            List<String> placeholders, boolean custom, String nextSerial) {
    }

    /**
     * Whom a certificate or card is for. {@code code} is the admission number or employee code; {@code detail} the
     * class ("Class 5 A") or the designation. {@code active} is false for a student or staff member who left.
     */
    public record Subject(SubjectType type, UUID id, String name, String firstName, String code, String detail,
            boolean active) {
    }

    public record CertificateRow(UUID id, CertificateType type, String title, CertificateStatus status,
            Subject subject, String serialNo, int versionNo, String purpose, RequestedVia requestedVia,
            String requestedByName, Instant requestedAt, LocalDate issuedOn, String signedByName) {
    }

    public record CertificatePage(List<CertificateRow> items, int page, int size, long total) {
    }

    /**
     * A certificate in full: the editable placeholder values, the wording (frozen once signed, otherwise rendered
     * from the template as it stands), the printed header and signatory, and the path of its verification page.
     */
    public record CertificateDetail(UUID id, CertificateType type, String title, CertificateStatus status,
            Subject subject, String serialNo, String academicYear, int versionNo, UUID supersedesId,
            UUID supersededById, String purpose, Map<String, String> fields, List<String> placeholders, String body,
            SchoolHeader school, Signatory signatory, String verifyToken, String verifyPath, Long duesPaise,
            RequestedVia requestedVia, String requestedByName, Instant requestedAt, String preparedByName,
            Instant preparedAt, String signedByName, Instant signedAt, LocalDate issuedOn, String cancelledByName,
            Instant cancelledAt, String cancelReason, boolean editable) {
    }

    /** What anyone with the QR code sees: nothing more about the student than their first name. */
    public record Verification(String schoolName, CertificateType type, String title, String serialNo,
            String firstName, LocalDate issuedOn, boolean valid, CertificateStatus status) {
    }

    /** An ID card. {@code emergencyPhone} is in full (the printed card carries it); screens mask it. */
    public record IdCardView(UUID id, Subject subject, String cardNo, int issueNo, CardStatus status,
            String bloodGroup, String emergencyPhone, LocalDate validFrom, LocalDate validUntil, UUID photoFileId,
            UUID replacesId, String reissueReason, Long lostFeePaise, String feeNote, String issuedByName,
            Instant createdAt, Instant cancelledAt) {
    }

    /** The register, with the school header for printing. */
    public record CardRegister(SchoolHeader school, List<IdCardView> items, int page, int size, long total) {
    }

    public record CardBatchResult(int issued, int skipped, List<IdCardView> cards) {
    }

    public record PhotoView(UUID fileId, String name) {
    }

    // ------------------------------------------------------------------ reports

    public record TypeCount(CertificateType type, long count) {
    }

    public record IssuedReport(LocalDate from, LocalDate to, long total, List<TypeCount> byType,
            List<CertificateRow> rows) {
    }

    public record PendingReport(long requested, long prepared, List<CertificateRow> rows) {
    }

    /** A student (of a section) or a member of staff with no card in use. */
    public record CardsPendingRow(Subject subject, UUID sectionId, String sectionLabel) {
    }

    public record CardsPendingReport(UUID sectionId, long total, List<CardsPendingRow> rows) {
    }

    // ------------------------------------------------------------------ families

    public record FamilyChild(UUID studentId, String name, String className, List<CertificateRow> certificates,
            IdCardView idCard) {
    }

    public record MyCertificates(List<FamilyChild> children, List<CertificateType> requestableTypes) {
    }
}
