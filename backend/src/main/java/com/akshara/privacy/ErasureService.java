package com.akshara.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.admissions.AdmissionRecords;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.FeeDuesService;
import com.akshara.fees.FeeViews.ReceiptSummary;
import com.akshara.privacy.PrivacyForms.EraseForm;
import com.akshara.privacy.PrivacyViews.RequestDetail;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentErasure;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentDetail;
import com.akshara.students.StudentStatus;

/**
 * Erasure of a child's personal data for an open erasure request, once the child has left the school. Staff confirm it
 * a second time by typing the admission number. The students and admissions modules anonymise their own records
 * through narrow public methods; fee receipts, dues and other accounts stay unchanged for the legal retention period
 * (8 years after the end of the financial year, see docs/api/phase-1-privacy.md). Consent records and the request
 * itself stay as proof of what was agreed and done. Publishes {@link StudentDataErased} for modules that keep their
 * own copies (for example the message log).
 */
@Service
@Transactional
public class ErasureService {

    /** Financial records are kept this many years after the end of the financial year they belong to. */
    public static final int FINANCIAL_RETENTION_YEARS = 8;

    private final DataRequestService requests;
    private final DataExportRepository exports;
    private final StudentService students;
    private final StudentErasure studentErasure;
    private final AdmissionRecords admissions;
    private final FeeDuesService fees;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    ErasureService(DataRequestService requests, DataExportRepository exports, StudentService students,
            StudentErasure studentErasure, AdmissionRecords admissions, FeeDuesService fees, AuditService audit,
            ApplicationEventPublisher events) {
        this.requests = requests;
        this.exports = exports;
        this.students = students;
        this.studentErasure = studentErasure;
        this.admissions = admissions;
        this.fees = fees;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Erases the child of an open erasure request. 409 for another request type, a closed or already erased request,
     * or a child who is still active; 400 when the typed admission number does not match.
     */
    public RequestDetail erase(UUID requestId, EraseForm form, Actor actor) {
        UUID tenantId = TenantContext.require();
        DataRequest request = requests.findForUpdate(requestId);
        DataRequestService.requireOpen(request);
        if (request.getType() != RequestType.ERASURE || request.getSubject() != RequestSubject.CHILD) {
            throw new ApiException(HttpStatus.CONFLICT, "Not an erasure request",
                    "Only an erasure request about a child can erase a student's data.");
        }
        if (request.getErasedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Already erased", "This child's data was already erased.");
        }
        UUID studentId = request.getStudentId();
        StudentDetail student = students.detail(studentId);
        String typed = form.confirmAdmissionNo() == null ? "" : form.confirmAdmissionNo().strip();
        if (!typed.toUpperCase(Locale.ROOT).equals(student.admissionNo().toUpperCase(Locale.ROOT))) {
            throw ApiException.badRequest("Type the student's admission number exactly to confirm.",
                    "confirmAdmissionNo");
        }
        if (student.status() == StudentStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Still enrolled",
                    "Only a student who has left the school (transferred, withdrawn or alumni) can be erased. "
                            + "Record the leaving first, or decline the request with a reason.",
                    Map.of("confirmAdmissionNo", "This student is still active."));
        }

        List<ReceiptSummary> receipts = fees.studentFees(studentId).receipts();
        LocalDate keepUntil = receipts.stream().map(ReceiptSummary::receivedOn).max(LocalDate::compareTo)
                .map(ErasureService::keepFinancialRecordsUntil).orElse(null);

        StudentErasure.Result result = studentErasure.anonymiseLeftStudent(studentId, actor);
        boolean application = admissions.anonymiseForStudent(studentId, StudentErasure.ERASED_NAME, actor);
        Instant now = Instant.now();
        int exportsDeleted = 0;
        for (DataExport export : exports.readyForStudent(studentId)) {
            export.discard(ExportStatus.ERASED, now);
            exportsDeleted++;
        }
        request.markErased(now);
        String summary = "Personal details anonymised (" + result.guardiansDeleted() + " parent record(s) deleted, "
                + result.guardiansKept() + " kept for brothers or sisters). "
                + (receipts.isEmpty() ? "No fee receipts." : receipts.size() + " fee receipt(s) kept until "
                        + keepUntil + " as the law requires.");
        requests.event(request, RequestEventKind.ERASED, actor, summary, now);
        exports.flush();

        List<UUID> unlinked = new ArrayList<>();
        if (result.studentUserId() != null) {
            unlinked.add(result.studentUserId());
        }
        unlinked.addAll(result.parentUserIds());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentId", studentId.toString());
        details.put("admissionNo", result.admissionNo());
        details.put("guardiansDeleted", result.guardiansDeleted());
        details.put("guardiansKept", result.guardiansKept());
        details.put("applicationAnonymised", application);
        details.put("exportsDeleted", exportsDeleted);
        details.put("receiptsKept", receipts.size());
        details.put("keepReceiptsUntil", keepUntil == null ? null : keepUntil.toString());
        audit.record(actor, "data_request.erased", "data_request", requestId, details);
        events.publishEvent(new StudentDataErased(tenantId, studentId, requestId, List.copyOf(unlinked), now));
        return requests.detail(request, true);
    }

    /**
     * The last day financial records dated {@code day} must be kept: the end of its financial year (31 March) plus
     * {@value #FINANCIAL_RETENTION_YEARS} years.
     */
    static LocalDate keepFinancialRecordsUntil(LocalDate day) {
        int endYear = day.getMonthValue() >= Month.APRIL.getValue() ? day.getYear() + 1 : day.getYear();
        return LocalDate.of(endYear, Month.MARCH, 31).plusYears(FINANCIAL_RETENTION_YEARS);
    }
}
