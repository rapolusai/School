package com.akshara.inventory;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.inventory.InventoryForms.InvoiceForm;
import com.akshara.inventory.InventoryForms.NoteForm;
import com.akshara.inventory.InventoryForms.PurchaseOrderForm;
import com.akshara.inventory.InventoryForms.QuoteForm;
import com.akshara.inventory.InventoryForms.ReasonForm;
import com.akshara.inventory.InventoryForms.ReceiveForm;
import com.akshara.inventory.InventoryForms.RequisitionForm;
import com.akshara.inventory.InventoryForms.RfqForm;
import com.akshara.inventory.InventoryForms.SelectQuoteForm;
import com.akshara.inventory.InventoryViews.ApprovalQueue;
import com.akshara.inventory.InventoryViews.OrderRow;
import com.akshara.inventory.InventoryViews.OrderView;
import com.akshara.inventory.InventoryViews.RequisitionView;
import com.akshara.inventory.InventoryViews.RfqView;
import com.akshara.shared.CurrentUser;

/** Requisitions with their approval chain, RFQs, purchase orders, receipts and invoices. */
@RestController
@RequestMapping("/api/inventory")
@PreAuthorize(InventoryAccess.READ_OR_APPROVE)
public class ProcurementController {

    private final ProcurementService procurement;

    ProcurementController(ProcurementService procurement) {
        this.procurement = procurement;
    }

    private static boolean all() {
        return InventoryAccess.canManage() || InventoryAccess.canApprove();
    }

    // ------------------------------------------------------------------ requisitions

    @GetMapping("/requisitions")
    public List<RequisitionView> requisitions(@RequestParam(required = false) RequisitionStatus status) {
        return procurement.requisitions(CurrentUser.requireId(), all(), InventoryAccess.canApprove(), status);
    }

    @GetMapping("/requisitions/{id}")
    public RequisitionView requisition(@PathVariable UUID id) {
        return procurement.requisition(id, CurrentUser.requireId(), all(), InventoryAccess.canApprove());
    }

    @PostMapping("/requisitions")
    @PreAuthorize(InventoryAccess.READ)
    @ResponseStatus(HttpStatus.CREATED)
    public RequisitionView create(@Valid @RequestBody RequisitionForm form,
            @RequestParam(defaultValue = "false") boolean submit) {
        return procurement.create(form, submit, InventoryAccess.canManage(), InventoryAccess.actor());
    }

    @PutMapping("/requisitions/{id}")
    @PreAuthorize(InventoryAccess.READ)
    public RequisitionView update(@PathVariable UUID id, @Valid @RequestBody RequisitionForm form) {
        return procurement.update(id, form, InventoryAccess.canManage(), InventoryAccess.actor());
    }

    @PostMapping("/requisitions/{id}/submit")
    @PreAuthorize(InventoryAccess.READ)
    public RequisitionView submit(@PathVariable UUID id) {
        return procurement.submit(id, InventoryAccess.canManage(), InventoryAccess.actor());
    }

    @DeleteMapping("/requisitions/{id}")
    @PreAuthorize(InventoryAccess.READ)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        procurement.delete(id, InventoryAccess.canManage(), InventoryAccess.actor());
    }

    /** The department head's decision: anyone with inventory.read may call it; the service checks who they are. */
    @PostMapping("/requisitions/{id}/head-approve")
    @PreAuthorize(InventoryAccess.READ)
    public RequisitionView headApprove(@PathVariable UUID id, @Valid @RequestBody(required = false) NoteForm form) {
        return procurement.headApprove(id, form, InventoryAccess.actor());
    }

    @PostMapping("/requisitions/{id}/head-reject")
    @PreAuthorize(InventoryAccess.READ)
    public RequisitionView headReject(@PathVariable UUID id, @Valid @RequestBody ReasonForm form) {
        return procurement.headReject(id, form, InventoryAccess.actor());
    }

    @PostMapping("/requisitions/{id}/approve")
    @PreAuthorize(InventoryAccess.APPROVE)
    public RequisitionView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) NoteForm form) {
        return procurement.approve(id, form, InventoryAccess.actor());
    }

    @PostMapping("/requisitions/{id}/reject")
    @PreAuthorize(InventoryAccess.APPROVE)
    public RequisitionView reject(@PathVariable UUID id, @Valid @RequestBody ReasonForm form) {
        return procurement.reject(id, form, InventoryAccess.actor());
    }

    @GetMapping("/approvals/queue")
    @PreAuthorize(InventoryAccess.APPROVE)
    public ApprovalQueue queue() {
        return procurement.queue(CurrentUser.requireId());
    }

    // ------------------------------------------------------------------ RFQs

    @GetMapping("/rfqs")
    public List<RfqView> rfqs() {
        return procurement.rfqs();
    }

    @GetMapping("/rfqs/{id}")
    public RfqView rfq(@PathVariable UUID id) {
        return procurement.rfq(id);
    }

    @PostMapping("/rfqs")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public RfqView createRfq(@Valid @RequestBody RfqForm form) {
        return procurement.createRfq(form, InventoryAccess.actor());
    }

    @PostMapping("/rfqs/{id}/quotes")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public RfqView addQuote(@PathVariable UUID id, @Valid @RequestBody QuoteForm form) {
        return procurement.addQuote(id, form, InventoryAccess.actor());
    }

    @PostMapping("/rfqs/{id}/select")
    @PreAuthorize(InventoryAccess.MANAGE)
    public RfqView selectQuote(@PathVariable UUID id, @Valid @RequestBody SelectQuoteForm form) {
        return procurement.selectQuote(id, form, InventoryAccess.actor());
    }

    @PostMapping("/rfqs/{id}/cancel")
    @PreAuthorize(InventoryAccess.MANAGE)
    public RfqView cancelRfq(@PathVariable UUID id) {
        return procurement.cancelRfq(id, InventoryAccess.actor());
    }

    // ------------------------------------------------------------------ purchase orders

    @GetMapping("/purchase-orders")
    public List<OrderRow> orders(@RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(required = false) UUID vendorId) {
        return procurement.orders(status, vendorId);
    }

    @GetMapping("/purchase-orders/{id}")
    public OrderView order(@PathVariable UUID id) {
        return procurement.order(id, CurrentUser.requireId(), InventoryAccess.canApprove());
    }

    @PostMapping("/purchase-orders")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView createOrder(@Valid @RequestBody PurchaseOrderForm form,
            @RequestParam(defaultValue = "false") boolean submit) {
        return procurement.createOrder(form, submit, InventoryAccess.actor());
    }

    @PutMapping("/purchase-orders/{id}")
    @PreAuthorize(InventoryAccess.MANAGE)
    public OrderView updateOrder(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderForm form) {
        return procurement.updateOrder(id, form, InventoryAccess.actor());
    }

    @PostMapping("/purchase-orders/{id}/submit")
    @PreAuthorize(InventoryAccess.MANAGE)
    public OrderView submitOrder(@PathVariable UUID id) {
        return procurement.submitOrder(id, InventoryAccess.actor());
    }

    @PostMapping("/purchase-orders/{id}/approve")
    @PreAuthorize(InventoryAccess.APPROVE)
    public OrderView approveOrder(@PathVariable UUID id, @Valid @RequestBody(required = false) NoteForm form) {
        return procurement.approveOrder(id, form, InventoryAccess.actor());
    }

    @PostMapping("/purchase-orders/{id}/reject")
    @PreAuthorize(InventoryAccess.APPROVE)
    public OrderView rejectOrder(@PathVariable UUID id, @Valid @RequestBody ReasonForm form) {
        return procurement.rejectOrder(id, form, InventoryAccess.actor());
    }

    @PostMapping("/purchase-orders/{id}/cancel")
    @PreAuthorize(InventoryAccess.MANAGE)
    public OrderView cancelOrder(@PathVariable UUID id, @Valid @RequestBody ReasonForm form) {
        return procurement.cancelOrder(id, form, InventoryAccess.actor());
    }

    @PostMapping("/purchase-orders/{id}/receipts")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView receive(@PathVariable UUID id, @Valid @RequestBody ReceiveForm form) {
        return procurement.receive(id, form, InventoryAccess.actor());
    }

    @PostMapping("/purchase-orders/{id}/invoice")
    @PreAuthorize(InventoryAccess.MANAGE)
    public OrderView invoice(@PathVariable UUID id, @Valid @RequestBody InvoiceForm form) {
        return procurement.recordInvoice(id, form, InventoryAccess.actor());
    }
}
