package com.akshara.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsViews.VendorView;
import com.akshara.accounts.ApprovalAction;
import com.akshara.accounts.Approvals;
import com.akshara.accounts.Approvals.Outcome;
import com.akshara.accounts.FinancialYear;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.inventory.InventoryForms.InvoiceForm;
import com.akshara.inventory.InventoryForms.NoteForm;
import com.akshara.inventory.InventoryForms.OrderLineInput;
import com.akshara.inventory.InventoryForms.PurchaseOrderForm;
import com.akshara.inventory.InventoryForms.QuoteForm;
import com.akshara.inventory.InventoryForms.ReasonForm;
import com.akshara.inventory.InventoryForms.ReceiveForm;
import com.akshara.inventory.InventoryForms.ReceiveLineInput;
import com.akshara.inventory.InventoryForms.RequisitionForm;
import com.akshara.inventory.InventoryForms.RequisitionLineInput;
import com.akshara.inventory.InventoryForms.RfqForm;
import com.akshara.inventory.InventoryForms.SelectQuoteForm;
import com.akshara.inventory.InventoryViews.ApprovalQueue;
import com.akshara.inventory.InventoryViews.ApprovalView;
import com.akshara.inventory.InventoryViews.OrderLineView;
import com.akshara.inventory.InventoryViews.OrderRow;
import com.akshara.inventory.InventoryViews.OrderView;
import com.akshara.inventory.InventoryViews.QuoteView;
import com.akshara.inventory.InventoryViews.ReceiptLineView;
import com.akshara.inventory.InventoryViews.ReceiptView;
import com.akshara.inventory.InventoryViews.RequisitionLineView;
import com.akshara.inventory.InventoryViews.RequisitionView;
import com.akshara.inventory.InventoryViews.RfqView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.DepartmentService;
import com.akshara.staff.DepartmentService.DepartmentView;
import com.akshara.staff.StaffPayrollInputs;
import com.akshara.staff.StaffPayrollInputs.PayrollPerson;
import com.akshara.staff.StaffService.DepartmentRef;

/**
 * Procurement: a requisition (any staff with inventory.read, for their department) goes to the department head when
 * the department has one, then to an approver with purchase.approve within their limit (bigger ones are passed on,
 * as in accounts); an RFQ records up to three vendors' quotes and the choice with its reason; a purchase order with
 * GST per line is approved the same way, received in parts, and the vendor's invoice recorded against it. Each
 * receipt publishes {@link PurchaseReceived}. Every step is audited.
 */
@Service
@Transactional
public class ProcurementService {

    static final int MAX_QUOTES = 3;

    private final RequisitionRepository requisitions;
    private final RequisitionLineRepository requisitionLines;
    private final PurchaseApprovalRepository approvals;
    private final RfqRepository rfqs;
    private final QuoteRepository quotes;
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository orderLines;
    private final GoodsReceiptRepository receipts;
    private final GoodsReceiptLineRepository receiptLines;
    private final InventorySetupService setup;
    private final StockService stock;
    private final DepartmentService departments;
    private final StaffPayrollInputs staff;
    private final ApplicationEventPublisher events;
    private final AuditService audit;

    ProcurementService(RequisitionRepository requisitions, RequisitionLineRepository requisitionLines,
            PurchaseApprovalRepository approvals, RfqRepository rfqs, QuoteRepository quotes,
            PurchaseOrderRepository orders, PurchaseOrderLineRepository orderLines, GoodsReceiptRepository receipts,
            GoodsReceiptLineRepository receiptLines, InventorySetupService setup, StockService stock,
            DepartmentService departments, StaffPayrollInputs staff, ApplicationEventPublisher events,
            AuditService audit) {
        this.requisitions = requisitions;
        this.requisitionLines = requisitionLines;
        this.approvals = approvals;
        this.rfqs = rfqs;
        this.quotes = quotes;
        this.orders = orders;
        this.orderLines = orderLines;
        this.receipts = receipts;
        this.receiptLines = receiptLines;
        this.setup = setup;
        this.stock = stock;
        this.departments = departments;
        this.staff = staff;
        this.events = events;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ requisitions

    /**
     * Requisitions the viewer may see: all of them for inventory.manage and purchase.approve, otherwise their own
     * and the ones waiting for them as department head.
     */
    @Transactional(readOnly = true)
    public List<RequisitionView> requisitions(UUID viewerId, boolean all, boolean viewerApproves,
            RequisitionStatus status) {
        TenantContext.require();
        List<Requisition> list = requisitions.findAllNewestFirst().stream()
                .filter(r -> all || viewerId.equals(r.getRequestedById()) || viewerId.equals(r.getHeadUserId()))
                .filter(r -> status == null || r.getStatus() == status).toList();
        Map<UUID, List<RequisitionLine>> lines = list.isEmpty() ? Map.of()
                : requisitionLines.findByRequisitions(list.stream().map(Requisition::getId).toList()).stream()
                        .collect(Collectors.groupingBy(RequisitionLine::getRequisitionId));
        Map<UUID, Item> items = stock.itemsById();
        Optional<Long> limit = viewerApproves ? setup.limitOf(viewerId) : Optional.empty();
        return list.stream().map(r -> view(r, lines.getOrDefault(r.getId(), List.of()), items, List.of(),
                canAct(r, viewerId, viewerApproves, limit))).toList();
    }

    @Transactional(readOnly = true)
    public RequisitionView requisition(UUID id, UUID viewerId, boolean all, boolean viewerApproves) {
        TenantContext.require();
        Requisition r = requisitions.findById(id).orElseThrow(() -> ApiException.notFound("Requisition"));
        if (!all && !viewerId.equals(r.getRequestedById()) && !viewerId.equals(r.getHeadUserId())) {
            throw ApiException.notFound("Requisition");
        }
        return view(r, viewerId, viewerApproves);
    }

    private RequisitionView view(Requisition r, UUID viewerId, boolean viewerApproves) {
        Optional<Long> limit = viewerApproves ? setup.limitOf(viewerId) : Optional.empty();
        return view(r, requisitionLines.findByRequisition(r.getId()), stock.itemsById(),
                approvals.findByRequisition(r.getId()), canAct(r, viewerId, viewerApproves, limit));
    }

    private static boolean canAct(Requisition r, UUID viewerId, boolean viewerApproves, Optional<Long> limit) {
        if (r.getStatus() == RequisitionStatus.SUBMITTED) {
            return viewerId.equals(r.getHeadUserId());
        }
        return r.getStatus() == RequisitionStatus.HEAD_APPROVED && viewerApproves
                && Approvals.mayAct(limit.orElse(null), r.getEscalatedAbovePaise());
    }

    static RequisitionView view(Requisition r, List<RequisitionLine> lines, Map<UUID, Item> items,
            List<PurchaseApproval> history, boolean canAct) {
        List<RequisitionLineView> rows = lines.stream().map(l -> {
            Item i = l.getItemId() == null ? null : items.get(l.getItemId());
            return new RequisitionLineView(l.getId(), l.getLineNo(), l.getItemId(), i == null ? null : i.getName(),
                    l.getDescription(), l.getQuantity(), l.getUnit(), l.getEstimatedUnitPaise(),
                    l.getEstimatedUnitPaise() * l.getQuantity());
        }).toList();
        return new RequisitionView(r.getId(), r.getNumber(), r.getStatus(), r.getDepartmentId(),
                r.getDepartmentName(), r.getRequestedById(), r.getRequestedByName(), r.getNeededBy(), r.getPurpose(),
                r.getEstimatedPaise(), r.getHeadUserId(), r.getHeadName(), r.getEscalatedAbovePaise(),
                r.getRejectionReason(), r.getSubmittedAt(), r.getDecidedAt(), r.getCreatedAt(), rows,
                history.stream().map(ProcurementService::view).toList(), canAct);
    }

    static ApprovalView view(PurchaseApproval a) {
        return new ApprovalView(a.getStage(), a.getAction(), a.getApproverId(), a.getApproverName(),
                a.getLimitPaise(), a.getNote(), a.getCreatedAt());
    }

    /**
     * A new requisition for the requester's own department (anyone), or for any department (inventory.manage).
     * {@code submit} sends it on at once.
     */
    public RequisitionView create(RequisitionForm form, boolean submit, boolean manager, Actor actor) {
        TenantContext.require();
        DepartmentRef department = department(form, manager, actor);
        Requisition r = new Requisition(null, RequisitionStatus.DRAFT, department == null ? null : department.id(),
                department == null ? null : department.name(), actor.id(), actor.name(), form.neededBy(),
                form.purpose().strip(), 0, null, null, null, null, null, null);
        requisitions.saveAndFlush(r);
        r.setEstimatedPaise(lines(r, form.lines()));
        requisitions.flush();
        audit.record(actor, "requisition.created", "requisition", r.getId(), details(r));
        if (submit) {
            submit(r, actor);
        }
        return view(r, actor.id(), false);
    }

    public RequisitionView update(UUID id, RequisitionForm form, boolean manager, Actor actor) {
        TenantContext.require();
        Requisition r = requisitions.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Requisition"));
        mine(r, manager, actor);
        if (r.getStatus() != RequisitionStatus.DRAFT) {
            throw ApiException.conflict("Only a draft can be changed.", "id");
        }
        DepartmentRef department = department(form, manager, actor);
        r.setPurpose(form.purpose().strip());
        r.setNeededBy(form.neededBy());
        requisitionLines.deleteAll(requisitionLines.findByRequisition(id));
        requisitionLines.flush();
        r.setEstimatedPaise(lines(r, form.lines()));
        r.setDepartment(department == null ? null : department.id(), department == null ? null : department.name());
        requisitions.flush();
        audit.record(actor, "requisition.updated", "requisition", id, details(r));
        return view(r, actor.id(), false);
    }

    public RequisitionView submit(UUID id, boolean manager, Actor actor) {
        TenantContext.require();
        Requisition r = requisitions.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Requisition"));
        mine(r, manager, actor);
        submit(r, actor);
        return view(r, actor.id(), false);
    }

    public void delete(UUID id, boolean manager, Actor actor) {
        TenantContext.require();
        Requisition r = requisitions.findById(id).orElseThrow(() -> ApiException.notFound("Requisition"));
        mine(r, manager, actor);
        if (r.getStatus() != RequisitionStatus.DRAFT) {
            throw ApiException.conflict("Only a draft can be deleted.", "id");
        }
        requisitions.delete(r);
        requisitions.flush();
        audit.record(actor, "requisition.deleted", "requisition", id, Map.of("purpose", r.getPurpose()));
    }

    private static void mine(Requisition r, boolean manager, Actor actor) {
        if (!manager && !actor.id().equals(r.getRequestedById())) {
            throw ApiException.notFound("Requisition");
        }
    }

    private DepartmentRef department(RequisitionForm form, boolean manager, Actor actor) {
        PayrollPerson me = staff.person(actor.id()).orElse(null);
        UUID own = me == null ? null : me.departmentId();
        if (form.departmentId() == null) {
            return own == null ? null : new DepartmentRef(own, me.departmentName());
        }
        if (!manager && !form.departmentId().equals(own)) {
            throw ApiException.badRequest("You can raise requisitions for your own department only.",
                    "departmentId");
        }
        return setup.department(form.departmentId(), "departmentId");
    }

    private long lines(Requisition r, List<RequisitionLineInput> inputs) {
        Map<UUID, Item> items = stock.itemsById();
        List<RequisitionLine> lines = new ArrayList<>();
        long total = 0;
        for (int n = 0; n < inputs.size(); n++) {
            RequisitionLineInput in = inputs.get(n);
            if (in.itemId() != null && !items.containsKey(in.itemId())) {
                throw ApiException.badRequest("Pick an item.", "lines[" + n + "].itemId");
            }
            lines.add(new RequisitionLine(r.getId(), n + 1, in.itemId(), in.description().strip(), in.quantity(),
                    in.unit().strip(), in.estimatedUnitPaise()));
            total += in.estimatedUnitPaise() * in.quantity();
        }
        requisitionLines.saveAllAndFlush(lines);
        return total;
    }

    private void submit(Requisition r, Actor actor) {
        if (r.getStatus() != RequisitionStatus.DRAFT) {
            throw ApiException.conflict("Already submitted.", "id");
        }
        if (r.getNumber() == null) {
            r.setNumber(setup.nextNumber("REQ", FinancialYear.current().label()));
        }
        // The department head approves first, unless there is none or it is the requester.
        DepartmentView d = r.getDepartmentId() == null ? null : departments.list().stream()
                .filter(x -> x.id().equals(r.getDepartmentId())).findFirst().orElse(null);
        if (d != null && d.head() != null && !d.head().id().equals(r.getRequestedById())) {
            r.setHeadUserId(d.head().id());
            r.setHeadName(d.head().name());
            r.setStatus(RequisitionStatus.SUBMITTED);
        } else {
            r.setStatus(RequisitionStatus.HEAD_APPROVED);
        }
        r.setSubmittedAt(Instant.now());
        r.setEscalatedAbovePaise(null);
        requisitions.flush();
        audit.record(actor, "requisition.submitted", "requisition", r.getId(), details(r));
    }

    /** The department head's approval (SUBMITTED → HEAD_APPROVED). */
    public RequisitionView headApprove(UUID id, NoteForm form, Actor actor) {
        TenantContext.require();
        Requisition r = requisitions.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Requisition"));
        head(r, actor);
        r.setStatus(RequisitionStatus.HEAD_APPROVED);
        approvals.save(new PurchaseApproval(id, null, ApprovalStage.HEAD, ApprovalAction.APPROVED, actor.id(),
                actor.name(), null, form == null ? null : InventoryForms.clean(form.note())));
        requisitions.flush();
        audit.record(actor, "requisition.head_approved", "requisition", id, details(r));
        return view(r, actor.id(), false);
    }

    public RequisitionView headReject(UUID id, ReasonForm form, Actor actor) {
        TenantContext.require();
        Requisition r = requisitions.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Requisition"));
        head(r, actor);
        reject(r, ApprovalStage.HEAD, form.reason().strip(), null, actor);
        return view(r, actor.id(), false);
    }

    private static void head(Requisition r, Actor actor) {
        if (r.getStatus() != RequisitionStatus.SUBMITTED) {
            throw ApiException.conflict("This requisition is not waiting for the department head.", "id");
        }
        if (!actor.id().equals(r.getHeadUserId())) {
            throw ApiException.conflict("Only the department head can decide this.", "id");
        }
    }

    /** An approver with purchase.approve approves within their limit or passes it on (HEAD_APPROVED → APPROVED). */
    public RequisitionView approve(UUID id, NoteForm form, Actor actor) {
        TenantContext.require();
        Requisition r = requisitions.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Requisition"));
        if (r.getStatus() != RequisitionStatus.HEAD_APPROVED) {
            throw ApiException.conflict("This requisition is not waiting for approval.", "id");
        }
        Long limit = setup.limitOf(actor.id()).orElse(null);
        Outcome outcome = Approvals.decide(limit, r.getEscalatedAbovePaise(), r.getEstimatedPaise(),
                setup.largestOtherLimit(actor.id()));
        String note = form == null ? null : InventoryForms.clean(form.note());
        switch (outcome) {
            case APPROVE -> {
                r.setStatus(RequisitionStatus.APPROVED);
                r.setDecidedAt(Instant.now());
                approvals.save(new PurchaseApproval(id, null, ApprovalStage.PURCHASE, ApprovalAction.APPROVED,
                        actor.id(), actor.name(), limit, note));
                requisitions.flush();
                audit.record(actor, "requisition.approved", "requisition", id, details(r));
            }
            case ESCALATE -> {
                r.setEscalatedAbovePaise(limit);
                approvals.save(new PurchaseApproval(id, null, ApprovalStage.PURCHASE, ApprovalAction.ESCALATED,
                        actor.id(), actor.name(), limit, note));
                requisitions.flush();
                audit.record(actor, "requisition.escalated", "requisition", id, details(r));
            }
            case NOT_YOURS -> throw ApiException.conflict(
                    "An approver with a larger limit has passed this on; only they or someone above can act.", "id");
            case NOBODY_ABOVE -> throw ApiException.conflict("This is above your limit and nobody has a larger "
                    + "one; raise a limit in the settings first.", "id");
        }
        return view(r, actor.id(), true);
    }

    public RequisitionView reject(UUID id, ReasonForm form, Actor actor) {
        TenantContext.require();
        Requisition r = requisitions.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Requisition"));
        if (r.getStatus() != RequisitionStatus.HEAD_APPROVED) {
            throw ApiException.conflict("This requisition is not waiting for approval.", "id");
        }
        Long limit = setup.limitOf(actor.id()).orElse(null);
        if (!Approvals.mayAct(limit, r.getEscalatedAbovePaise())) {
            throw ApiException.conflict(
                    "An approver with a larger limit has passed this on; only they or someone above can act.", "id");
        }
        reject(r, ApprovalStage.PURCHASE, form.reason().strip(), limit, actor);
        return view(r, actor.id(), true);
    }

    private void reject(Requisition r, ApprovalStage stage, String reason, Long limit, Actor actor) {
        r.setStatus(RequisitionStatus.REJECTED);
        r.setRejectionReason(reason);
        r.setDecidedAt(Instant.now());
        approvals.save(new PurchaseApproval(r.getId(), null, stage, ApprovalAction.REJECTED, actor.id(),
                actor.name(), limit, reason));
        requisitions.flush();
        audit.record(actor, "requisition.rejected", "requisition", r.getId(), details(r));
    }

    private static Map<String, Object> details(Requisition r) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (r.getNumber() != null) {
            d.put("number", r.getNumber());
        }
        d.put("status", r.getStatus().name());
        d.put("purpose", r.getPurpose());
        d.put("estimatedPaise", r.getEstimatedPaise());
        if (r.getDepartmentName() != null) {
            d.put("department", r.getDepartmentName());
        }
        d.put("requestedBy", r.getRequestedByName());
        if (r.getRejectionReason() != null) {
            d.put("reason", r.getRejectionReason());
        }
        return d;
    }

    // ------------------------------------------------------------------ the approver's queue

    @Transactional(readOnly = true)
    public ApprovalQueue queue(UUID userId) {
        TenantContext.require();
        Optional<Long> limit = setup.limitOf(userId);
        List<RequisitionView> waiting = requisitions(userId, true, true, null).stream()
                .filter(r -> r.status() == RequisitionStatus.HEAD_APPROVED
                        || r.status() == RequisitionStatus.SUBMITTED && userId.equals(r.headUserId())).toList();
        List<OrderRow> submitted = rows(orders.findSubmitted());
        return new ApprovalQueue(waiting, submitted, limit.orElse(null), limit.isPresent() && limit.get() != null);
    }

    // ------------------------------------------------------------------ RFQs

    @Transactional(readOnly = true)
    public List<RfqView> rfqs() {
        TenantContext.require();
        Map<UUID, Requisition> reqs = requisitionsById();
        return rfqs.findAllNewestFirst().stream().map(r -> view(r, reqs)).toList();
    }

    @Transactional(readOnly = true)
    public RfqView rfq(UUID id) {
        TenantContext.require();
        return view(rfqs.findById(id).orElseThrow(() -> ApiException.notFound("RFQ")), requisitionsById());
    }

    public RfqView createRfq(RfqForm form, Actor actor) {
        TenantContext.require();
        Requisition req = approvedRequisition(form.requisitionId(), "requisitionId");
        Rfq r = rfqs.saveAndFlush(new Rfq(setup.nextNumber("RFQ", FinancialYear.current().label()),
                req == null ? null : req.getId(), form.title().strip(), RfqStatus.OPEN, null, null, actor.name()));
        audit.record(actor, "rfq.created", "rfq", r.getId(), Map.of("number", r.getNumber(), "title",
                r.getTitle()));
        return view(r, requisitionsById());
    }

    public RfqView addQuote(UUID id, QuoteForm form, Actor actor) {
        TenantContext.require();
        Rfq r = rfqs.findById(id).orElseThrow(() -> ApiException.notFound("RFQ"));
        if (r.getStatus() != RfqStatus.OPEN) {
            throw ApiException.conflict("This RFQ is closed.", "id");
        }
        VendorView vendor = setup.vendor(form.vendorId(), "vendorId");
        List<Quote> existing = quotes.findByRfq(id);
        if (existing.stream().anyMatch(q -> q.getVendorId().equals(vendor.id()))) {
            throw ApiException.conflict("This vendor has already quoted.", "vendorId");
        }
        if (existing.size() >= MAX_QUOTES) {
            throw ApiException.conflict("An RFQ goes to at most " + MAX_QUOTES + " vendors.", "vendorId");
        }
        Quote q = quotes.saveAndFlush(new Quote(id, vendor.id(), vendor.name(), form.amountPaise(),
                form.deliveryDays(), form.validUntil(), InventoryForms.clean(form.notes())));
        audit.record(actor, "rfq.quoted", "rfq", id, Map.of("number", r.getNumber(), "vendor", vendor.name(),
                "amountPaise", q.getAmountPaise()));
        return view(r, requisitionsById());
    }

    public RfqView selectQuote(UUID id, SelectQuoteForm form, Actor actor) {
        TenantContext.require();
        Rfq r = rfqs.findById(id).orElseThrow(() -> ApiException.notFound("RFQ"));
        if (r.getStatus() != RfqStatus.OPEN) {
            throw ApiException.conflict("This RFQ is closed.", "id");
        }
        Quote q = quotes.findByRfq(id).stream().filter(x -> x.getId().equals(form.quoteId())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Pick one of this RFQ's quotes.", "quoteId"));
        r.setSelectedQuoteId(q.getId());
        r.setSelectionReason(form.reason().strip());
        r.setStatus(RfqStatus.SELECTED);
        rfqs.flush();
        audit.record(actor, "rfq.selected", "rfq", id, Map.of("number", r.getNumber(), "vendor",
                q.getVendorName(), "amountPaise", q.getAmountPaise(), "reason", r.getSelectionReason()));
        return view(r, requisitionsById());
    }

    public RfqView cancelRfq(UUID id, Actor actor) {
        TenantContext.require();
        Rfq r = rfqs.findById(id).orElseThrow(() -> ApiException.notFound("RFQ"));
        if (r.getStatus() != RfqStatus.OPEN) {
            throw ApiException.conflict("This RFQ is closed.", "id");
        }
        r.setStatus(RfqStatus.CANCELLED);
        rfqs.flush();
        audit.record(actor, "rfq.cancelled", "rfq", id, Map.of("number", r.getNumber()));
        return view(r, requisitionsById());
    }

    private RfqView view(Rfq r, Map<UUID, Requisition> reqs) {
        Requisition req = r.getRequisitionId() == null ? null : reqs.get(r.getRequisitionId());
        return new RfqView(r.getId(), r.getNumber(), r.getRequisitionId(), req == null ? null : req.getNumber(),
                r.getTitle(), r.getStatus(), r.getSelectedQuoteId(), r.getSelectionReason(), r.getCreatedByName(),
                r.getCreatedAt(), quotes.findByRfq(r.getId()).stream().map(q -> new QuoteView(q.getId(),
                        q.getVendorId(), q.getVendorName(), q.getAmountPaise(), q.getDeliveryDays(),
                        q.getValidUntil(), q.getNotes(), q.getId().equals(r.getSelectedQuoteId()))).toList());
    }

    private Map<UUID, Requisition> requisitionsById() {
        return requisitions.findAll().stream().collect(Collectors.toMap(Requisition::getId, Function.identity()));
    }

    /** An approved (or already ordered) requisition, or null when none was given; 400 on the field otherwise. */
    private Requisition approvedRequisition(UUID id, String field) {
        if (id == null) {
            return null;
        }
        Requisition r = requisitions.findById(id).orElseThrow(() -> ApiException.badRequest(
                "Pick a requisition.", field));
        if (r.getStatus() != RequisitionStatus.APPROVED && r.getStatus() != RequisitionStatus.ORDERED) {
            throw ApiException.badRequest("The requisition is not approved.", field);
        }
        return r;
    }

    // ------------------------------------------------------------------ purchase orders

    @Transactional(readOnly = true)
    public List<OrderRow> orders(PurchaseOrderStatus status, UUID vendorId) {
        TenantContext.require();
        return rows(orders.findAllNewestFirst().stream()
                .filter(o -> status == null || o.getStatus() == status)
                .filter(o -> vendorId == null || vendorId.equals(o.getVendorId())).toList());
    }

    List<OrderRow> rows(List<PurchaseOrder> list) {
        Map<UUID, List<PurchaseOrderLine>> lines = list.isEmpty() ? Map.of()
                : orderLines.findByOrders(list.stream().map(PurchaseOrder::getId).toList()).stream()
                        .collect(Collectors.groupingBy(PurchaseOrderLine::getPurchaseOrderId));
        Map<UUID, Requisition> reqs = requisitionsById();
        return list.stream().map(o -> {
            List<PurchaseOrderLine> ls = lines.getOrDefault(o.getId(), List.of());
            Requisition req = o.getRequisitionId() == null ? null : reqs.get(o.getRequisitionId());
            return new OrderRow(o.getId(), o.getNumber(), o.getStatus(), o.getVendorId(), o.getVendorName(),
                    o.getOrderedOn(), o.getExpectedOn(), o.getTotalPaise(), ls.size(),
                    (int) ls.stream().filter(l -> l.getReceivedQty() >= l.getQuantity()).count(),
                    req == null ? null : req.getNumber(), o.getCreatedAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public OrderView order(UUID id, UUID viewerId, boolean viewerApproves) {
        TenantContext.require();
        return view(orders.findById(id).orElseThrow(() -> ApiException.notFound("Purchase order")), viewerId,
                viewerApproves);
    }

    public OrderView createOrder(PurchaseOrderForm form, boolean submit, Actor actor) {
        TenantContext.require();
        VendorView vendor = setup.vendor(form.vendorId(), "vendorId");
        Requisition req = approvedRequisition(form.requisitionId(), "requisitionId");
        Rfq rfq = rfq(form.rfqId(), "rfqId");
        StockService.notFuture(form.orderedOn(), "orderedOn");
        FinancialYear year = FinancialYear.of(form.orderedOn());
        PurchaseOrder o = new PurchaseOrder(setup.nextNumber("PO", year.label()), year.label(),
                PurchaseOrderStatus.DRAFT, vendor.id(), vendor.name(), req == null ? null : req.getId(),
                rfq == null ? null : rfq.getId(), form.orderedOn(), form.expectedOn(),
                InventoryForms.clean(form.terms()), 0, 0, 0, null, null, null, null, null, actor.id(), actor.name(),
                null, null);
        orders.saveAndFlush(o);
        lines(o, form.lines());
        orders.flush();
        audit.record(actor, "purchase_order.created", "purchase_order", o.getId(), details(o));
        if (submit) {
            submit(o, actor);
        }
        return view(o, actor.id(), false);
    }

    public OrderView updateOrder(UUID id, PurchaseOrderForm form, Actor actor) {
        TenantContext.require();
        PurchaseOrder o = orders.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
        if (o.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw ApiException.conflict("Only a draft can be changed.", "id");
        }
        VendorView vendor = setup.vendor(form.vendorId(), "vendorId");
        Requisition req = approvedRequisition(form.requisitionId(), "requisitionId");
        Rfq rfq = rfq(form.rfqId(), "rfqId");
        StockService.notFuture(form.orderedOn(), "orderedOn");
        o.setVendorId(vendor.id());
        o.setVendorName(vendor.name());
        o.setRequisitionId(req == null ? null : req.getId());
        o.setRfqId(rfq == null ? null : rfq.getId());
        o.setOrderedOn(form.orderedOn());
        o.setExpectedOn(form.expectedOn());
        o.setTerms(InventoryForms.clean(form.terms()));
        orderLines.deleteAll(orderLines.findByOrder(id));
        orderLines.flush();
        lines(o, form.lines());
        orders.flush();
        audit.record(actor, "purchase_order.updated", "purchase_order", id, details(o));
        return view(o, actor.id(), false);
    }

    private Rfq rfq(UUID id, String field) {
        if (id == null) {
            return null;
        }
        return rfqs.findById(id).orElseThrow(() -> ApiException.badRequest("Pick an RFQ.", field));
    }

    private void lines(PurchaseOrder o, List<OrderLineInput> inputs) {
        Map<UUID, Item> items = stock.itemsById();
        List<PurchaseOrderLine> lines = new ArrayList<>();
        long subtotal = 0;
        long tax = 0;
        for (int n = 0; n < inputs.size(); n++) {
            OrderLineInput in = inputs.get(n);
            if (in.itemId() != null && !items.containsKey(in.itemId())) {
                throw ApiException.badRequest("Pick an item.", "lines[" + n + "].itemId");
            }
            long amount = in.unitPaise() * in.quantity();
            long gst = InventoryMath.gst(amount, in.gstRateBp());
            lines.add(new PurchaseOrderLine(o.getId(), n + 1, in.itemId(), in.description().strip(), in.quantity(),
                    in.unit().strip(), in.unitPaise(), in.gstRateBp(), amount, gst, 0));
            subtotal += amount;
            tax += gst;
        }
        if (subtotal + tax > InventoryForms.MAX_PAISE) {
            throw ApiException.badRequest("The order is above ₹10 crore.", "lines");
        }
        orderLines.saveAllAndFlush(lines);
        o.setSubtotalPaise(subtotal);
        o.setTaxPaise(tax);
        o.setTotalPaise(subtotal + tax);
    }

    public OrderView submitOrder(UUID id, Actor actor) {
        TenantContext.require();
        PurchaseOrder o = orders.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
        submit(o, actor);
        return view(o, actor.id(), false);
    }

    private void submit(PurchaseOrder o, Actor actor) {
        if (o.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw ApiException.conflict("Already submitted.", "id");
        }
        o.setStatus(PurchaseOrderStatus.SUBMITTED);
        o.setSubmittedAt(Instant.now());
        o.setEscalatedAbovePaise(null);
        orders.flush();
        audit.record(actor, "purchase_order.submitted", "purchase_order", o.getId(), details(o));
    }

    public OrderView approveOrder(UUID id, NoteForm form, Actor actor) {
        TenantContext.require();
        PurchaseOrder o = orders.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
        if (o.getStatus() != PurchaseOrderStatus.SUBMITTED) {
            throw ApiException.conflict("This order is not waiting for approval.", "id");
        }
        Long limit = setup.limitOf(actor.id()).orElse(null);
        Outcome outcome = Approvals.decide(limit, o.getEscalatedAbovePaise(), o.getTotalPaise(),
                setup.largestOtherLimit(actor.id()));
        String note = form == null ? null : InventoryForms.clean(form.note());
        switch (outcome) {
            case APPROVE -> {
                o.setStatus(PurchaseOrderStatus.APPROVED);
                o.setApprovedAt(Instant.now());
                approvals.save(new PurchaseApproval(null, id, ApprovalStage.PURCHASE, ApprovalAction.APPROVED,
                        actor.id(), actor.name(), limit, note));
                if (o.getRequisitionId() != null) {
                    requisitions.findById(o.getRequisitionId()).ifPresent(r -> r.setStatus(
                            RequisitionStatus.ORDERED));
                }
                orders.flush();
                audit.record(actor, "purchase_order.approved", "purchase_order", id, details(o));
            }
            case ESCALATE -> {
                o.setEscalatedAbovePaise(limit);
                approvals.save(new PurchaseApproval(null, id, ApprovalStage.PURCHASE, ApprovalAction.ESCALATED,
                        actor.id(), actor.name(), limit, note));
                orders.flush();
                audit.record(actor, "purchase_order.escalated", "purchase_order", id, details(o));
            }
            case NOT_YOURS -> throw ApiException.conflict(
                    "An approver with a larger limit has passed this on; only they or someone above can act.", "id");
            case NOBODY_ABOVE -> throw ApiException.conflict("This is above your limit and nobody has a larger "
                    + "one; raise a limit in the settings first.", "id");
        }
        return view(o, actor.id(), true);
    }

    public OrderView rejectOrder(UUID id, ReasonForm form, Actor actor) {
        TenantContext.require();
        PurchaseOrder o = orders.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
        if (o.getStatus() != PurchaseOrderStatus.SUBMITTED) {
            throw ApiException.conflict("This order is not waiting for approval.", "id");
        }
        Long limit = setup.limitOf(actor.id()).orElse(null);
        if (!Approvals.mayAct(limit, o.getEscalatedAbovePaise())) {
            throw ApiException.conflict(
                    "An approver with a larger limit has passed this on; only they or someone above can act.", "id");
        }
        o.setStatus(PurchaseOrderStatus.REJECTED);
        o.setRejectionReason(form.reason().strip());
        approvals.save(new PurchaseApproval(null, id, ApprovalStage.PURCHASE, ApprovalAction.REJECTED, actor.id(),
                actor.name(), limit, form.reason().strip()));
        orders.flush();
        audit.record(actor, "purchase_order.rejected", "purchase_order", id, details(o));
        return view(o, actor.id(), true);
    }

    public OrderView cancelOrder(UUID id, ReasonForm form, Actor actor) {
        TenantContext.require();
        PurchaseOrder o = orders.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
        if (o.getStatus() != PurchaseOrderStatus.DRAFT && o.getStatus() != PurchaseOrderStatus.SUBMITTED
                && o.getStatus() != PurchaseOrderStatus.APPROVED) {
            throw ApiException.conflict("Only a draft, submitted or approved order with nothing received can be "
                    + "cancelled.", "id");
        }
        o.setStatus(PurchaseOrderStatus.CANCELLED);
        o.setRejectionReason(form.reason().strip());
        orders.flush();
        audit.record(actor, "purchase_order.cancelled", "purchase_order", id, details(o));
        return view(o, actor.id(), false);
    }

    /** Goods received against an approved order, in part or in full; consumables go into stock at once. */
    public OrderView receive(UUID id, ReceiveForm form, Actor actor) {
        TenantContext.require();
        PurchaseOrder o = orders.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
        if (o.getStatus() != PurchaseOrderStatus.APPROVED && o.getStatus() != PurchaseOrderStatus.PARTIAL) {
            throw ApiException.conflict("Only an approved order can be received.", "id");
        }
        StockService.notFuture(form.receivedOn(), "receivedOn");
        if (form.receivedOn().isBefore(o.getOrderedOn())) {
            throw ApiException.badRequest("The receipt is before the order date.", "receivedOn");
        }
        Location location = setup.location(form.locationId(), "locationId");
        Map<UUID, PurchaseOrderLine> lines = orderLines.findByOrder(id).stream()
                .collect(Collectors.toMap(PurchaseOrderLine::getId, Function.identity()));
        Map<UUID, Item> items = stock.itemsById();
        GoodsReceipt g = receipts.saveAndFlush(new GoodsReceipt(setup.nextNumber("GRN",
                FinancialYear.current().label()), id, form.receivedOn(), location == null ? null : location.getId(),
                InventoryForms.clean(form.invoiceNumber()), InventoryForms.clean(form.notes()), 0, actor.id(),
                actor.name()));
        long total = 0;
        List<PurchaseReceived.Item> received = new ArrayList<>();
        List<GoodsReceiptLine> gls = new ArrayList<>();
        for (int n = 0; n < form.lines().size(); n++) {
            ReceiveLineInput in = form.lines().get(n);
            PurchaseOrderLine l = lines.get(in.poLineId());
            if (l == null) {
                throw ApiException.badRequest("This line is not on the order.", "lines[" + n + "].poLineId");
            }
            int remaining = l.getQuantity() - l.getReceivedQty();
            if (in.quantity() > remaining) {
                throw ApiException.conflict("Only " + remaining + " " + l.getUnit() + " of \"" + l.getDescription()
                        + "\" are still to come.", "lines[" + n + "].quantity");
            }
            l.setReceivedQty(l.getReceivedQty() + in.quantity());
            gls.add(new GoodsReceiptLine(g.getId(), l.getId(), l.getItemId(), in.quantity(), l.getUnitPaise()));
            long amount = l.getUnitPaise() * in.quantity();
            total += amount + InventoryMath.gst(amount, l.getGstRateBp());
            received.add(new PurchaseReceived.Item(l.getItemId(), l.getDescription(), in.quantity(), l.getUnit(),
                    l.getUnitPaise(), l.getGstRateBp()));
            Item item = l.getItemId() == null ? null : items.get(l.getItemId());
            if (item != null && item.getKind() == ItemKind.CONSUMABLE) {
                stock.receiveFromOrder(item, location, in.quantity(), l.getUnitPaise(), form.receivedOn(), id,
                        g.getId(), "Against " + o.getNumber(), actor);
            }
        }
        receiptLines.saveAllAndFlush(gls);
        orderLines.flush();
        boolean complete = lines.values().stream().allMatch(l -> l.getReceivedQty() >= l.getQuantity());
        o.setStatus(complete ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIAL);
        if (g.getInvoiceNumber() != null && o.getInvoiceNumber() == null) {
            o.setInvoiceNumber(g.getInvoiceNumber());
        }
        orders.flush();
        g.setTotalPaise(total);
        receipts.flush();
        Map<String, Object> d = details(o);
        d.put("receiptNumber", g.getNumber());
        d.put("receiptPaise", total);
        d.put("lines", gls.size());
        d.put("complete", complete);
        audit.record(actor, "purchase_order.received", "purchase_order", id, d);
        events.publishEvent(new PurchaseReceived(TenantContext.require(), id, o.getNumber(), g.getId(),
                g.getNumber(), o.getVendorId(), o.getVendorName(), total,
                g.getInvoiceNumber() != null ? g.getInvoiceNumber() : o.getInvoiceNumber(), form.receivedOn(),
                complete, List.copyOf(received), actor.id(), Instant.now()));
        return view(o, actor.id(), false);
    }

    public OrderView recordInvoice(UUID id, InvoiceForm form, Actor actor) {
        TenantContext.require();
        PurchaseOrder o = orders.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
        if (o.getStatus() != PurchaseOrderStatus.APPROVED && o.getStatus() != PurchaseOrderStatus.PARTIAL
                && o.getStatus() != PurchaseOrderStatus.RECEIVED) {
            throw ApiException.conflict("An invoice goes against an approved order.", "id");
        }
        StockService.notFuture(form.invoiceDate(), "invoiceDate");
        o.setInvoiceNumber(form.invoiceNumber().strip());
        o.setInvoiceDate(form.invoiceDate());
        o.setInvoicePaise(form.invoicePaise());
        orders.flush();
        Map<String, Object> d = details(o);
        d.put("invoiceNumber", o.getInvoiceNumber());
        d.put("invoicePaise", o.getInvoicePaise());
        audit.record(actor, "purchase_order.invoiced", "purchase_order", id, d);
        return view(o, actor.id(), false);
    }

    private static Map<String, Object> details(PurchaseOrder o) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("number", o.getNumber());
        d.put("status", o.getStatus().name());
        d.put("vendor", o.getVendorName());
        d.put("totalPaise", o.getTotalPaise());
        if (o.getRejectionReason() != null) {
            d.put("reason", o.getRejectionReason());
        }
        return d;
    }

    OrderView view(PurchaseOrder o, UUID viewerId, boolean viewerApproves) {
        Map<UUID, Item> items = stock.itemsById();
        Map<UUID, Location> locations = setup.locationsById();
        VendorView vendor = setup.vendorsById().get(o.getVendorId());
        Requisition req = o.getRequisitionId() == null ? null : requisitions.findById(o.getRequisitionId())
                .orElse(null);
        Rfq rfq = o.getRfqId() == null ? null : rfqs.findById(o.getRfqId()).orElse(null);
        List<PurchaseOrderLine> lines = orderLines.findByOrder(o.getId());
        Map<UUID, PurchaseOrderLine> byId = lines.stream()
                .collect(Collectors.toMap(PurchaseOrderLine::getId, Function.identity()));
        List<OrderLineView> lineViews = lines.stream().map(l -> {
            Item i = l.getItemId() == null ? null : items.get(l.getItemId());
            return new OrderLineView(l.getId(), l.getLineNo(), l.getItemId(), i == null ? null : i.getName(),
                    l.getDescription(), l.getQuantity(), l.getUnit(), l.getUnitPaise(), l.getGstRateBp(),
                    l.getAmountPaise(), l.getTaxPaise(), l.getAmountPaise() + l.getTaxPaise(), l.getReceivedQty());
        }).toList();
        List<ReceiptView> receiptViews = receipts.findByOrder(o.getId()).stream().map(g -> {
            List<ReceiptLineView> rls = receiptLines.findByReceipt(g.getId()).stream().map(rl -> {
                PurchaseOrderLine l = byId.get(rl.getPoLineId());
                return new ReceiptLineView(rl.getPoLineId(), l == null ? null : l.getDescription(),
                        rl.getQuantity(), l == null ? null : l.getUnit(), rl.getUnitPaise());
            }).toList();
            return new ReceiptView(g.getId(), g.getNumber(), g.getReceivedOn(), g.getLocationId(),
                    InventorySetupService.nameOf(locations, g.getLocationId()), g.getInvoiceNumber(), g.getNotes(),
                    g.getTotalPaise(), g.getReceivedByName(), rls);
        }).toList();
        boolean canAct = o.getStatus() == PurchaseOrderStatus.SUBMITTED && viewerApproves
                && Approvals.mayAct(setup.limitOf(viewerId).orElse(null), o.getEscalatedAbovePaise());
        return new OrderView(o.getId(), o.getNumber(), o.getFinancialYear(), o.getStatus(), o.getVendorId(),
                o.getVendorName(), vendor == null ? null : vendor.gstin(), vendor == null ? null : vendor.address(),
                o.getRequisitionId(), req == null ? null : req.getNumber(), o.getRfqId(),
                rfq == null ? null : rfq.getNumber(), o.getOrderedOn(), o.getExpectedOn(), o.getTerms(),
                o.getSubtotalPaise(), o.getTaxPaise(), o.getTotalPaise(), o.getEscalatedAbovePaise(),
                o.getRejectionReason(), o.getInvoiceNumber(), o.getInvoiceDate(), o.getInvoicePaise(),
                o.getCreatedByName(), o.getSubmittedAt(), o.getApprovedAt(), o.getCreatedAt(), lineViews,
                approvals.findByOrder(o.getId()).stream().map(ProcurementService::view).toList(), receiptViews,
                canAct);
    }

    /** For the dashboard: orders waiting for approval and requisitions waiting for a purchase approver. */
    long ordersWaiting() {
        return orders.findSubmitted().size();
    }

    long requisitionsWaiting() {
        return requisitions.findWaiting().size();
    }

    List<PurchaseOrder> allOrders() {
        return orders.findAllNewestFirst();
    }
}
