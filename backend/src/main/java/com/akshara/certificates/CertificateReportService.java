package com.akshara.certificates;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.certificates.CertificateViews.CardsPendingReport;
import com.akshara.certificates.CertificateViews.CardsPendingRow;
import com.akshara.certificates.CertificateViews.CertificatePage;
import com.akshara.certificates.CertificateViews.CertificateRow;
import com.akshara.certificates.CertificateViews.IssuedReport;
import com.akshara.certificates.CertificateViews.PendingReport;
import com.akshara.certificates.CertificateViews.Subject;
import com.akshara.certificates.CertificateViews.TypeCount;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The module's reports for the reports hub, each as JSON and as a CSV download: certificates issued by type in a
 * period, requests waiting for the office or the signatory, and people with no ID card in use.
 */
@Service
@Transactional(readOnly = true)
public class CertificateReportService {

    public static final int MAX_RANGE_DAYS = 400;

    private final CertificateService certificates;
    private final IdCardService cards;

    CertificateReportService(CertificateService certificates, IdCardService cards) {
        this.certificates = certificates;
        this.cards = cards;
    }

    public IssuedReport issued(LocalDate from, LocalDate to, CertificateType type) {
        TenantContext.require();
        LocalDate end = to != null ? to : Serials.today();
        LocalDate start = from != null ? from : end.minusMonths(1).plusDays(1);
        if (end.isBefore(start)) {
            throw ApiException.badRequest("The period must end after it starts.", "to");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw ApiException.badRequest("Choose a period of at most " + MAX_RANGE_DAYS + " days.", "to");
        }
        List<CertificateRow> rows = new ArrayList<>();
        for (CertificateStatus status : List.of(CertificateStatus.ISSUED, CertificateStatus.CANCELLED)) {
            CertificatePage page = certificates.list(status, type, null, null, start, end, 0,
                    CertificateService.MAX_PAGE_SIZE);
            rows.addAll(page.items());
            for (int p = 1; (long) p * CertificateService.MAX_PAGE_SIZE < page.total(); p++) {
                rows.addAll(certificates.list(status, type, null, null, start, end, p,
                        CertificateService.MAX_PAGE_SIZE).items());
            }
        }
        rows.sort((a, b) -> b.issuedOn().compareTo(a.issuedOn()));
        Map<CertificateType, Long> counts = rows.stream()
                .collect(Collectors.groupingBy(CertificateRow::type, Collectors.counting()));
        List<TypeCount> byType = Arrays.stream(CertificateType.values()).filter(counts::containsKey)
                .map(t -> new TypeCount(t, counts.get(t))).toList();
        return new IssuedReport(start, end, rows.size(), byType, rows);
    }

    CertificateCsv issuedCsv(LocalDate from, LocalDate to, CertificateType type) {
        IssuedReport r = issued(from, to, type);
        CertificateCsv csv = new CertificateCsv("Serial no", "Type", "Title", "Status", "Name", "Admission/employee no",
                "Class/designation", "Issued on", "Signed by", "Version");
        for (CertificateRow row : r.rows()) {
            csv.row(row.serialNo(), row.type().name(), row.title(), row.status().name(), row.subject().name(),
                    row.subject().code(), row.subject().detail(), row.issuedOn(), row.signedByName(),
                    row.versionNo());
        }
        return csv;
    }

    public PendingReport pending() {
        TenantContext.require();
        List<CertificateRow> rows = new ArrayList<>();
        for (CertificateStatus status : List.of(CertificateStatus.REQUESTED, CertificateStatus.PREPARED)) {
            rows.addAll(certificates.list(status, null, null, null, null, null, 0,
                    CertificateService.MAX_PAGE_SIZE).items());
        }
        rows.sort((a, b) -> a.requestedAt().compareTo(b.requestedAt()));
        Map<CertificateStatus, Long> counts = certificates.countByStatus();
        return new PendingReport(counts.getOrDefault(CertificateStatus.REQUESTED, 0L),
                counts.getOrDefault(CertificateStatus.PREPARED, 0L), rows);
    }

    CertificateCsv pendingCsv() {
        PendingReport r = pending();
        CertificateCsv csv = new CertificateCsv("Status", "Type", "Name", "Admission/employee no", "Class/designation",
                "Purpose", "Requested via", "Requested by", "Requested on");
        for (CertificateRow row : r.rows()) {
            csv.row(row.status().name(), row.type().name(), row.subject().name(), row.subject().code(),
                    row.subject().detail(), row.purpose(), row.requestedVia().name(), row.requestedByName(),
                    LocalDate.ofInstant(row.requestedAt(), Serials.INDIA));
        }
        return csv;
    }

    /** Students of a section, or the staff, with no card in use. */
    public CardsPendingReport cardsPending(UUID sectionId, boolean staff) {
        TenantContext.require();
        if (!staff && sectionId == null) {
            throw ApiException.badRequest("Choose a section, or the staff.", "sectionId");
        }
        String label = staff ? null : cards.section(sectionId).orElseThrow(() -> ApiException.notFound("section"))
                .label();
        List<Subject> pending = cards.pending(staff ? SubjectType.STAFF : SubjectType.STUDENT, sectionId);
        List<CardsPendingRow> rows = pending.stream().map(s -> new CardsPendingRow(s, staff ? null : sectionId,
                label)).toList();
        return new CardsPendingReport(staff ? null : sectionId, rows.size(), rows);
    }

    CertificateCsv cardsPendingCsv(UUID sectionId, boolean staff) {
        CardsPendingReport r = cardsPending(sectionId, staff);
        CertificateCsv csv = new CertificateCsv("Name", "Admission/employee no", "Class/designation");
        for (CardsPendingRow row : r.rows()) {
            csv.row(row.subject().name(), row.subject().code(), row.subject().detail());
        }
        return csv;
    }
}
