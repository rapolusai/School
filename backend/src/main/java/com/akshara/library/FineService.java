package com.akshara.library;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.library.LibraryForms.CollectForm;
import com.akshara.library.LibraryForms.WaiveForm;
import com.akshara.library.LibraryViews.FinePage;
import com.akshara.library.LibraryViews.FineReceipt;
import com.akshara.library.LibraryViews.FineView;
import com.akshara.library.LibraryViews.Member;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Library fines, kept apart from school fees: listed with the member's history, paid at the library desk (one receipt
 * number for the fines paid together, numbered per financial year) or waived with a reason. Both are audited.
 */
@Service
@Transactional
public class FineService {

    public static final int MAX_PAGE_SIZE = 100;

    private final FineRepository fines;
    private final LibrarySetupService setup;
    private final LibraryMapper mapper;
    private final LibraryMembers members;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final EntityManager entityManager;

    FineService(FineRepository fines, LibrarySetupService setup, LibraryMapper mapper, LibraryMembers members,
            TenantDirectory tenants, AuditService audit, EntityManager entityManager) {
        this.fines = fines;
        this.setup = setup;
        this.mapper = mapper;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    /** Fines, newest first, with the total still due among those matching. */
    @Transactional(readOnly = true)
    public FinePage list(FineStatus status, MemberType memberType, UUID memberId, int page, int size) {
        TenantContext.require();
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNo = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" from Fine f where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (status != null) {
            where.append(" and f.status = :status");
            params.put("status", status);
        }
        if (memberType != null) {
            where.append(" and f.memberType = :memberType");
            params.put("memberType", memberType);
            if (memberId != null) {
                where.append(memberType == MemberType.STUDENT ? " and f.studentId = :member"
                        : " and f.staffUserId = :member");
                params.put("member", memberId);
            }
        }
        TypedQuery<Long> count = entityManager.createQuery("select count(f)" + where, Long.class);
        TypedQuery<Long> due = entityManager.createQuery("select coalesce(sum(f.amountPaise), 0)" + where
                + " and f.status = com.akshara.library.FineStatus.DUE", Long.class);
        TypedQuery<Fine> rows = entityManager.createQuery("select f" + where + " order by f.createdAt desc, f.id",
                Fine.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            due.setParameter(k, v);
            rows.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<Fine> found = rows.setFirstResult(pageNo * pageSize).setMaxResults(pageSize).getResultList();
        return new FinePage(mapper.fines(found), pageNo, pageSize, total, due.getSingleResult());
    }

    /**
     * Records the payment of one member's fines under one new receipt number. A cheque or bank transfer needs its
     * reference. Fines of another school are a 400; a fine already paid or waived is a 409.
     */
    public FineReceipt collect(CollectForm form, Actor actor, Instant at, LocalDate today) {
        TenantContext.require();
        Set<UUID> ids = new LinkedHashSet<>(form.fineIds());
        List<Fine> list = fines.lockByIds(ids);
        if (list.size() != ids.size()) {
            throw ApiException.badRequest("Pick fines of this school.", "fineIds");
        }
        if (list.stream().map(Fine::member).distinct().count() > 1) {
            throw ApiException.badRequest("Collect one member's fines at a time.", "fineIds");
        }
        if (list.stream().anyMatch(f -> !f.isDue())) {
            throw ApiException.conflict("Some of these fines are already paid or waived.", "fineIds");
        }
        String reference = CatalogueService.blankToNull(form.reference());
        if (reference == null && (form.mode() == LibraryPaymentMode.CHEQUE
                || form.mode() == LibraryPaymentMode.BANK_TRANSFER)) {
            throw ApiException.badRequest("Enter the cheque or transfer reference.", "reference");
        }
        String receiptNo = setup.nextReceiptNo(today);
        for (Fine f : list) {
            f.paid(receiptNo, form.mode(), reference, at, actor);
        }
        fines.saveAllAndFlush(list);
        long total = list.stream().mapToLong(Fine::getAmountPaise).sum();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("receiptNo", receiptNo);
        details.put("amountPaise", total);
        details.put("fines", list.size());
        details.put("mode", form.mode().name());
        audit.record(actor, "library_fine.collected", "library_fine", list.getFirst().getId(), details);
        return receipt(receiptNo);
    }

    public FineView waive(UUID id, WaiveForm form, Actor actor, Instant at) {
        TenantContext.require();
        Fine fine = fines.lockByIds(List.of(id)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Fine"));
        if (!fine.isDue()) {
            throw ApiException.conflict("This fine is already paid or waived.", "status");
        }
        fine.waived(form.reason().trim(), at, actor);
        fines.saveAndFlush(fine);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("amountPaise", fine.getAmountPaise());
        details.put("kind", fine.getKind().name());
        details.put("reason", fine.getWaiveReason());
        audit.record(actor, "library_fine.waived", "library_fine", fine.getId(), details);
        return mapper.fine(fine);
    }

    /** A receipt again, to print. 404 when the number is unknown. */
    @Transactional(readOnly = true)
    public FineReceipt receipt(String receiptNo) {
        TenantContext.require();
        List<Fine> paid = fines.byReceipt(receiptNo == null ? "" : receiptNo.trim());
        if (paid.isEmpty()) {
            throw ApiException.notFound("Receipt");
        }
        Fine first = paid.getFirst();
        Member member = members.describe(List.of(first.member())).get(first.member());
        String school = tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("");
        return new FineReceipt(first.getReceiptNo(), school, member, mapper.fines(paid),
                paid.stream().mapToLong(Fine::getAmountPaise).sum(), first.getPaymentMode(),
                first.getPaymentReference(), first.getPaidAt(), first.getCollectedByName());
    }
}
