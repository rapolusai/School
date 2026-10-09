package com.akshara.fees;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.FeeDuesService.PlanItem;
import com.akshara.fees.FeeForms.PaymentForm;
import com.akshara.fees.FeeForms.WaiverForm;
import com.akshara.fees.FeeViews.ReceiptPage;
import com.akshara.fees.FeeViews.ReceiptSummary;
import com.akshara.fees.FeeViews.ReceiptView;
import com.akshara.fees.FeeViews.StudentFees;
import com.akshara.fees.FeeViews.StudentHit;
import com.akshara.platform.SchoolProfile;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Taking fees: payments at the counter, receipts with gap-free numbers per financial year, cancellations (which
 * reverse the allocations, never delete them) and late-fee waivers. Online payments use the same recording path.
 */
@Service
@Transactional
public class FeePaymentService {

    public static final int MAX_PAGE_SIZE = 100;

    /** How a payment arrived, copied onto its receipt. */
    record Payment(PaymentMode mode, String chequeNo, String bankName, String reference, String source,
            String gateway, String gatewayPaymentId, String remarks, LocalDate receivedOn, Instant receivedAt) {
    }

    /** Filters of the receipts list. Null means "any". */
    public record ReceiptQuery(LocalDate from, LocalDate to, PaymentMode mode, String status, String search,
            UUID studentId, int page, int size) {
    }

    private final FeeDuesService duesService;
    private final ReceiptRepository receipts;
    private final PaymentAllocationRepository allocations;
    private final LateFeeWaiverRepository waivers;
    private final StudentDueRepository dues;
    private final FeeInstalmentRepository instalments;
    private final StudentRoster roster;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;

    FeePaymentService(FeeDuesService duesService, ReceiptRepository receipts, PaymentAllocationRepository allocations,
            LateFeeWaiverRepository waivers, StudentDueRepository dues, FeeInstalmentRepository instalments,
            StudentRoster roster, TenantDirectory tenants, AuditService audit, ApplicationEventPublisher events,
            EntityManager entityManager) {
        this.duesService = duesService;
        this.receipts = receipts;
        this.allocations = allocations;
        this.waivers = waivers;
        this.dues = dues;
        this.instalments = instalments;
        this.roster = roster;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ finding students

    /** Students of the current year by name or admission number, for the collect screen. */
    @Transactional(readOnly = true)
    public List<StudentHit> search(String text) {
        TenantContext.require();
        String q = text == null ? "" : text.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        return roster.search(q, 20).stream()
                .map(s -> new StudentHit(s.id(), s.fullName(), s.admissionNo(), s.status(), s.className(),
                        s.sectionName(), s.rollNo(), s.guardianName()))
                .toList();
    }

    // ------------------------------------------------------------------ counter payments

    /** A payment taken at the counter today. */
    public ReceiptView collect(UUID studentId, PaymentForm form, Actor actor) {
        LocalDate today = SchoolDay.today();
        return recordCounterPayment(studentId, form, today, null, actor);
    }

    /**
     * Records a counter payment received on {@code receivedOn} (at {@code at}, or now when null). Used by the API with
     * today's date, and by the demo data with earlier dates. Late fees are worked out as of the day received.
     */
    public ReceiptView recordCounterPayment(UUID studentId, PaymentForm form, LocalDate receivedOn, LocalTime at,
            Actor actor) {
        TenantContext.require();
        Actor by = Fees.actor(actor);
        StudentRoster.Entry student = roster.student(studentId);
        Payment payment = counterPayment(form, receivedOn,
                at == null ? Instant.now() : SchoolDay.at(receivedOn, at));
        duesService.generateForStudent(studentId);
        List<PlanItem> plan = duesService.plan(studentId, form.instalmentIds(), form.includeLateFee(), receivedOn);
        long outstanding = plan.stream().mapToLong(PlanItem::outstandingPaise).sum();
        if (outstanding == 0) {
            throw Fees.conflict("Nothing due", form.instalmentIds() == null || form.instalmentIds().isEmpty()
                    ? "Nothing is due from this student." : "Nothing is due in the chosen instalments.");
        }
        if (form.amountPaise() > outstanding) {
            throw ApiException.badRequest("This is more than the " + Fees.rupees(outstanding) + " due"
                    + (form.instalmentIds() == null || form.instalmentIds().isEmpty() ? "" : " in the chosen "
                            + "instalments") + ".", "amountPaise");
        }
        Receipt receipt = issue(student, plan, form.amountPaise(), false, payment, by);
        return view(receipt);
    }

    private static Payment counterPayment(PaymentForm form, LocalDate receivedOn, Instant receivedAt) {
        String chequeNo = Fees.blankToNull(form.chequeNo());
        String bankName = Fees.blankToNull(form.bankName());
        String reference = Fees.blankToNull(form.reference());
        switch (form.mode()) {
            case ONLINE -> throw ApiException.badRequest("Online payments are recorded by the payment gateway.",
                    "mode");
            case CHEQUE -> {
                if (chequeNo == null) {
                    throw ApiException.badRequest("Enter the cheque number.", "chequeNo");
                }
                if (bankName == null) {
                    throw ApiException.badRequest("Enter the bank name.", "bankName");
                }
            }
            case UPI -> {
                if (reference == null) {
                    throw ApiException.badRequest("Enter the UPI transaction reference.", "reference");
                }
            }
            case BANK_TRANSFER -> {
                if (reference == null) {
                    throw ApiException.badRequest("Enter the bank reference (UTR).", "reference");
                }
            }
            default -> {
            }
        }
        boolean cheque = form.mode() == PaymentMode.CHEQUE;
        return new Payment(form.mode(), cheque ? chequeNo : null, cheque ? bankName : null,
                form.mode() == PaymentMode.CASH || cheque ? null : reference, Receipt.COUNTER, null, null,
                Fees.blankToNull(form.remarks()), receivedOn, receivedAt);
    }

    /**
     * Issues a receipt: allocates the amount over {@code plan} in order, takes the next receipt number of the
     * financial year, writes the ledger and updates the dues. Callers have locked the student's dues through
     * {@link FeeDuesService#plan}. Anything above the plan is kept as an advance when {@code allowAdvance} (online
     * payments, where the gateway already took the money), and refused otherwise.
     */
    Receipt issue(StudentRoster.Entry student, List<PlanItem> plan, long amountPaise, boolean allowAdvance,
            Payment payment, Actor by) {
        long[] outstanding = plan.stream().mapToLong(PlanItem::outstandingPaise).toArray();
        long[] allocated = FeeMath.allocate(amountPaise, outstanding);
        long used = 0;
        long lateFee = 0;
        for (int i = 0; i < allocated.length; i++) {
            used += allocated[i];
            if (PaymentAllocation.LATE_FEE.equals(plan.get(i).kind())) {
                lateFee += allocated[i];
            }
        }
        long advance = amountPaise - used;
        if (advance > 0 && !allowAdvance) {
            throw ApiException.badRequest("This is more than the " + Fees.rupees(used) + " due.", "amountPaise");
        }
        String financialYear = FeeMath.financialYear(payment.receivedOn());
        int seq = nextReceiptSeq(financialYear);
        Receipt receipt = receipts.save(new Receipt(new Receipt.Issue(FeeMath.receiptNo(financialYear, seq),
                financialYear, seq, student.id(), student.fullName(), student.admissionNo(), student.classLabel(),
                payment.receivedOn(), payment.receivedAt(), payment.mode(), payment.chequeNo(), payment.bankName(),
                payment.reference(), amountPaise, lateFee, payment.source(), payment.gateway(),
                payment.gatewayPaymentId(), payment.remarks(), by.id(), by.name())));
        receipts.flush();
        List<PaymentAllocation> lines = new ArrayList<>();
        for (int i = 0; i < allocated.length; i++) {
            if (allocated[i] == 0) {
                continue;
            }
            PlanItem item = plan.get(i);
            if (item.due() != null) {
                item.due().pay(allocated[i]);
            }
            lines.add(new PaymentAllocation(receipt.getId(), student.id(), lines.size() + 1,
                    PaymentAllocation.ALLOCATION, item.kind(),
                    item.due() == null ? null : item.due().getId(), item.instalment().getId(),
                    item.head() == null ? null : item.head().getId(),
                    item.head() == null ? null : item.head().getName(), item.instalment().getLabel(), allocated[i]));
        }
        if (advance > 0) {
            lines.add(new PaymentAllocation(receipt.getId(), student.id(), lines.size() + 1,
                    PaymentAllocation.ALLOCATION, PaymentAllocation.ADVANCE, null, null, null, null, null, advance));
        }
        allocations.saveAll(lines);
        dues.flush();
        allocations.flush();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("receiptNo", receipt.getReceiptNo());
        details.put("studentName", student.fullName());
        details.put("admissionNo", student.admissionNo());
        details.put("amountPaise", amountPaise);
        details.put("lateFeePaise", lateFee);
        details.put("mode", payment.mode().name());
        details.put("source", payment.source());
        if (advance > 0) {
            details.put("advancePaise", advance);
        }
        audit.record(by, "fee_payment.recorded", "receipt", receipt.getId(), details);
        events.publishEvent(new FeePaymentReceived(TenantContext.require(), receipt.getId(), receipt.getReceiptNo(),
                student.id(), amountPaise, payment.mode(), payment.receivedAt()));
        return receipt;
    }

    /**
     * The next receipt number of the financial year. The counter row is locked FOR UPDATE until the transaction ends,
     * so concurrent payments get consecutive numbers, and a payment that rolls back gives its number back.
     */
    private int nextReceiptSeq(String financialYear) {
        UUID tenantId = TenantContext.require();
        entityManager.createNativeQuery("insert into fees.receipt_counter (tenant_id, financial_year, last_seq, "
                + "updated_at) values (?1, ?2, 0, now()) on conflict (tenant_id, financial_year) do nothing")
                .setParameter(1, tenantId).setParameter(2, financialYear).executeUpdate();
        Number last = (Number) entityManager.createNativeQuery("select last_seq from fees.receipt_counter "
                + "where tenant_id = ?1 and financial_year = ?2 for update")
                .setParameter(1, tenantId).setParameter(2, financialYear).getSingleResult();
        int next = last.intValue() + 1;
        entityManager.createNativeQuery("update fees.receipt_counter set last_seq = ?3, updated_at = now() "
                + "where tenant_id = ?1 and financial_year = ?2")
                .setParameter(1, tenantId).setParameter(2, financialYear).setParameter(3, next).executeUpdate();
        return next;
    }

    // ------------------------------------------------------------------ cancellation and waivers

    /**
     * Cancels a receipt: it stays on file marked CANCELLED, and every allocation gets a matching reversal, so the
     * dues it paid are owed again. Nothing is deleted.
     */
    public ReceiptView cancel(UUID receiptId, String reason, Actor actor) {
        TenantContext.require();
        Actor by = Fees.actor(actor);
        Receipt receipt = receipts.lock(receiptId).orElseThrow(() -> ApiException.notFound("Receipt"));
        if (receipt.isCancelled()) {
            throw Fees.conflict("Already cancelled", "Receipt " + receipt.getReceiptNo() + " is already cancelled.");
        }
        Map<UUID, StudentDue> dueById = dues.lockByStudent(receipt.getStudentId()).stream()
                .collect(Collectors.toMap(StudentDue::getId, Function.identity()));
        List<PaymentAllocation> reversals = new ArrayList<>();
        for (PaymentAllocation a : allocations.findByReceiptIdOrderByLineNoAscEntryAsc(receiptId)) {
            if (!PaymentAllocation.ALLOCATION.equals(a.getEntry())) {
                continue;
            }
            if (a.getDueId() != null) {
                dueById.get(a.getDueId()).reverse(a.getAmountPaise());
            }
            reversals.add(a.reversal());
        }
        allocations.saveAll(reversals);
        receipt.cancel(by.id(), by.name(), reason.trim());
        receipts.saveAndFlush(receipt);
        dues.flush();
        allocations.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("receiptNo", receipt.getReceiptNo());
        details.put("studentName", receipt.getStudentName());
        details.put("amountPaise", receipt.getAmountPaise());
        details.put("reason", reason.trim());
        audit.record(by, "receipt.cancelled", "receipt", receiptId, details);
        return view(receipt);
    }

    /** Waives the late fee of one of the student's instalments, now and later. */
    public StudentFees waive(UUID studentId, WaiverForm form, Actor actor) {
        TenantContext.require();
        Actor by = Fees.actor(actor);
        StudentRoster.Entry student = roster.student(studentId);
        boolean owes = dues.findByStudentId(studentId).stream()
                .anyMatch(d -> d.getInstalmentId().equals(form.instalmentId()));
        if (!owes) {
            throw ApiException.badRequest("Pick an instalment of this student.", "instalmentId");
        }
        if (waivers.existsByStudentIdAndInstalmentId(studentId, form.instalmentId())) {
            throw Fees.conflict("Already waived", "The late fee of this instalment is already waived.");
        }
        FeeInstalment instalment = instalments.findById(form.instalmentId()).orElseThrow();
        LateFeeWaiver waiver = waivers.saveAndFlush(new LateFeeWaiver(studentId, form.instalmentId(),
                form.reason().trim(), by.id(), by.name()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentName", student.fullName());
        details.put("admissionNo", student.admissionNo());
        details.put("instalment", instalment.getLabel());
        details.put("reason", form.reason().trim());
        audit.record(by, "late_fee.waived", "late_fee_waiver", waiver.getId(), details);
        return duesService.studentFees(studentId);
    }

    // ------------------------------------------------------------------ receipts

    @Transactional(readOnly = true)
    public ReceiptPage receipts(ReceiptQuery query) {
        TenantContext.require();
        int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
        int page = Math.max(query.page(), 0);
        StringBuilder where = new StringBuilder(" from Receipt r where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (query.from() != null) {
            where.append(" and r.receivedOn >= :from");
            params.put("from", query.from());
        }
        if (query.to() != null) {
            where.append(" and r.receivedOn <= :to");
            params.put("to", query.to());
        }
        if (query.mode() != null) {
            where.append(" and r.mode = :mode");
            params.put("mode", query.mode());
        }
        if (query.status() != null) {
            where.append(" and r.status = :status");
            params.put("status", query.status());
        }
        if (query.studentId() != null) {
            where.append(" and r.studentId = :student");
            params.put("student", query.studentId());
        }
        String search = query.search() == null ? "" : query.search().trim().toLowerCase(Locale.ROOT);
        if (!search.isEmpty()) {
            where.append(" and (lower(r.receiptNo) like :q escape '\\' or lower(r.studentName) like :q escape '\\'"
                    + " or lower(r.admissionNo) like :q escape '\\')");
            params.put("q", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        TypedQuery<Long> count = entityManager.createQuery("select count(r)" + where, Long.class);
        TypedQuery<Long> sum = entityManager.createQuery(
                "select coalesce(sum(r.amountPaise), 0)" + where + " and r.status = 'ISSUED'", Long.class);
        TypedQuery<Receipt> rows = entityManager.createQuery("select r" + where
                + " order by r.receivedAt desc, r.receiptNo desc", Receipt.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            sum.setParameter(k, v);
            rows.setParameter(k, v);
        });
        List<ReceiptSummary> items = rows.setFirstResult(page * size).setMaxResults(size).getResultList().stream()
                .map(ReceiptMapper::summary).toList();
        return new ReceiptPage(items, page, size, count.getSingleResult(), sum.getSingleResult());
    }

    @Transactional(readOnly = true)
    public ReceiptView receipt(UUID id) {
        TenantContext.require();
        return view(receipts.findById(id).orElseThrow(() -> ApiException.notFound("Receipt")));
    }

    /** A receipt of one student; 404 when it belongs to someone else. */
    @Transactional(readOnly = true)
    public ReceiptView receiptOf(UUID studentId, UUID receiptId) {
        TenantContext.require();
        Receipt receipt = receipts.findById(receiptId).filter(r -> r.getStudentId().equals(studentId))
                .orElseThrow(() -> ApiException.notFound("Receipt"));
        return view(receipt);
    }

    /** The receipts register for a date range, as CSV. */
    @Transactional(readOnly = true)
    public String receiptsCsv(LocalDate from, LocalDate to) {
        TenantContext.require();
        DateTimeFormatter day = DateTimeFormatter.ISO_LOCAL_DATE;
        Csv csv = new Csv("Receipt no", "Date", "Student", "Admission no", "Class", "Mode", "Cheque no", "Bank",
                "Reference", "Amount", "Late fee", "Source", "Collected by", "Status", "Cancelled on",
                "Cancel reason");
        for (Receipt r : receipts.issuedBetween(from, to)) {
            csv.row(r.getReceiptNo(), r.getReceivedOn().format(day), r.getStudentName(), r.getAdmissionNo(),
                    r.getClassLabel(), r.getMode().name(), r.getChequeNo(), r.getBankName(), r.getReference(),
                    new Csv.Money(r.getAmountPaise()), new Csv.Money(r.getLateFeePaise()), r.getSource(),
                    r.getCollectedByName(), r.getStatus(),
                    r.getCancelledAt() == null ? null : SchoolDay.of(r.getCancelledAt()).format(day),
                    r.getCancelReason());
        }
        return csv.toString();
    }

    ReceiptView view(Receipt receipt) {
        SchoolProfile school = tenants.profile(TenantContext.require()).orElse(null);
        return ReceiptMapper.view(receipt, allocations.findByReceiptIdOrderByLineNoAscEntryAsc(receipt.getId()), school);
    }

    static Set<String> statuses() {
        return Set.of(Receipt.ISSUED, Receipt.CANCELLED);
    }
}
