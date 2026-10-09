package com.akshara.fees;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.fees.FeeForms.OrderForm;
import com.akshara.fees.FeeForms.PaymentForm;
import com.akshara.fees.FeeForms.ReasonForm;
import com.akshara.fees.FeeForms.VerifyForm;
import com.akshara.fees.FeeForms.WaiverForm;
import com.akshara.fees.FeePaymentService.ReceiptQuery;
import com.akshara.fees.FeeViews.OrderView;
import com.akshara.fees.FeeViews.ReceiptPage;
import com.akshara.fees.FeeViews.ReceiptView;
import com.akshara.fees.FeeViews.StudentFees;
import com.akshara.fees.FeeViews.StudentHit;

/**
 * A student's fees, payments and receipts. Reading needs fees.read, taking payments fees.collect, and cancelling
 * receipts or waiving late fees fees.manage.
 */
@RestController
@RequestMapping("/api/fees")
public class FeeCollectionController {

    private final FeeDuesService dues;
    private final FeePaymentService payments;
    private final OnlinePaymentService online;

    public FeeCollectionController(FeeDuesService dues, FeePaymentService payments, OnlinePaymentService online) {
        this.dues = dues;
        this.payments = payments;
        this.online = online;
    }

    // ------------------------------------------------------------------ students

    @GetMapping("/students")
    @PreAuthorize(FeeSetupController.READ)
    public List<StudentHit> search(@RequestParam(defaultValue = "") @Size(max = 100) String q) {
        return payments.search(q);
    }

    @GetMapping("/students/{id}")
    @PreAuthorize(FeeSetupController.READ)
    public StudentFees studentFees(@PathVariable UUID id) {
        return dues.studentFees(id);
    }

    @PostMapping("/students/{id}/payments")
    @PreAuthorize(FeeSetupController.COLLECT)
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptView collect(@PathVariable UUID id, @Valid @RequestBody PaymentForm request) {
        return payments.collect(id, request, null);
    }

    @PostMapping("/students/{id}/late-fee-waivers")
    @PreAuthorize(FeeSetupController.MANAGE)
    public StudentFees waive(@PathVariable UUID id, @Valid @RequestBody WaiverForm request) {
        return payments.waive(id, request, null);
    }

    // ------------------------------------------------------------------ online payments started by staff

    @PostMapping("/students/{id}/orders")
    @PreAuthorize(FeeSetupController.COLLECT)
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView createOrder(@PathVariable UUID id, @Valid @RequestBody OrderForm request) {
        return online.createOrder(id, request, null);
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize(FeeSetupController.COLLECT)
    public OrderView order(@PathVariable UUID orderId) {
        return online.order(orderId, null);
    }

    @PostMapping("/orders/{orderId}/verify")
    @PreAuthorize(FeeSetupController.COLLECT)
    public ReceiptView verify(@PathVariable UUID orderId, @Valid @RequestBody VerifyForm request) {
        return online.verify(orderId, null, request);
    }

    // ------------------------------------------------------------------ receipts

    @GetMapping("/receipts")
    @PreAuthorize(FeeSetupController.READ)
    public ReceiptPage receipts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PaymentMode mode,
            @RequestParam(required = false) @Pattern(regexp = "ISSUED|CANCELLED") String status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UUID studentId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(FeePaymentService.MAX_PAGE_SIZE) int size) {
        return payments.receipts(new ReceiptQuery(from, to, mode, status, q, studentId, page, size));
    }

    @GetMapping("/receipts/{id}")
    @PreAuthorize(FeeSetupController.READ)
    public ReceiptView receipt(@PathVariable UUID id) {
        return payments.receipt(id);
    }

    @PostMapping("/receipts/{id}/cancel")
    @PreAuthorize(FeeSetupController.MANAGE)
    public ReceiptView cancel(@PathVariable UUID id, @Valid @RequestBody ReasonForm request) {
        return payments.cancel(id, request.reason(), null);
    }

    /** The receipts register as CSV, for spreadsheets. */
    @GetMapping("/receipts/export.csv")
    @PreAuthorize(FeeSetupController.READ)
    public ResponseEntity<String> receiptsCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        FeeReportService.checkRange(from, to);
        return csv(payments.receiptsCsv(from, to), "receipts-" + from + "-to-" + to + ".csv");
    }

    static ResponseEntity<String> csv(String body, String filename) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
