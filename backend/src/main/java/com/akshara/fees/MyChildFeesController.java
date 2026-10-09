package com.akshara.fees;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.FeeForms.OrderForm;
import com.akshara.fees.FeeForms.VerifyForm;
import com.akshara.fees.FeeViews.OrderView;
import com.akshara.fees.FeeViews.ReceiptView;
import com.akshara.fees.FeeViews.StudentFees;
import com.akshara.shared.CurrentUser;

/**
 * Fees as a parent sees them, for their own children only: any other student id is a 404, exactly like a student
 * that does not exist. Parents can pay online and open their receipts.
 */
@RestController
@RequestMapping("/api/me/children/{studentId}/fees")
@PreAuthorize("hasAuthority('child.view')")
public class MyChildFeesController {

    private final FeeDuesService dues;
    private final FeePaymentService payments;
    private final OnlinePaymentService online;

    public MyChildFeesController(FeeDuesService dues, FeePaymentService payments, OnlinePaymentService online) {
        this.dues = dues;
        this.payments = payments;
        this.online = online;
    }

    @GetMapping
    public StudentFees fees(@PathVariable UUID studentId) {
        ownChild(studentId);
        return dues.studentFees(studentId);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView createOrder(@PathVariable UUID studentId, @Valid @RequestBody OrderForm request) {
        ownChild(studentId);
        return online.createOrder(studentId, request, new Actor(CurrentUser.requireId(),
                CurrentUser.name().orElse("Parent")));
    }

    @GetMapping("/orders/{orderId}")
    public OrderView order(@PathVariable UUID studentId, @PathVariable UUID orderId) {
        ownChild(studentId);
        return online.order(orderId, studentId);
    }

    @PostMapping("/orders/{orderId}/verify")
    public ReceiptView verify(@PathVariable UUID studentId, @PathVariable UUID orderId,
            @Valid @RequestBody VerifyForm request) {
        ownChild(studentId);
        return online.verify(orderId, studentId, request);
    }

    @GetMapping("/receipts/{receiptId}")
    public ReceiptView receipt(@PathVariable UUID studentId, @PathVariable UUID receiptId) {
        ownChild(studentId);
        return payments.receiptOf(studentId, receiptId);
    }

    private void ownChild(UUID studentId) {
        dues.requireOwnChild(CurrentUser.requireId(), studentId);
    }
}
