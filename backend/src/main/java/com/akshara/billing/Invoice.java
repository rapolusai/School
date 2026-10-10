package com.akshara.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.TenantId;

import com.akshara.platform.Plan;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A GST tax invoice from Akshara to a school for one period of its subscription. Immutable once issued: the database
 * lets the API change only the payment and cancellation columns, so this class only ever updates those. The seller's
 * and the buyer's details are copied in, so a reprint shows exactly what was issued.
 */
@Entity
@Table(schema = "billing", name = "invoice")
@DynamicUpdate
class Invoice extends AssignedIdEntity {

    static final String ISSUED = "ISSUED";
    static final String PAID = "PAID";
    static final String CANCELLED = "CANCELLED";

    /** Everything printed on an invoice, fixed when it is issued. */
    record Issue(String invoiceNo, String financialYear, int seq, LocalDate invoiceDate, LocalDate dueDate, Plan plan,
            BillingCycle cycle, LocalDate periodStart, LocalDate periodEnd, int billedStudents, long unitPricePaise,
            GstMath.Tax tax, String sacCode, BillingProperties.Seller seller, String buyerName, String buyerAddress,
            String buyerStateCode, String buyerGstin, String issuedByName) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String invoiceNo;

    @Column(nullable = false, updatable = false)
    private String financialYear;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Column(nullable = false, updatable = false)
    private LocalDate invoiceDate;

    @Column(nullable = false, updatable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private BillingCycle billingCycle;

    @Column(nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, updatable = false)
    private int billedStudents;

    @Column(nullable = false, updatable = false)
    private long unitPricePaise;

    @Column(nullable = false, updatable = false)
    private long taxablePaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private GstMath.Split taxSplit;

    @Column(nullable = false, updatable = false)
    private long cgstPaise;

    @Column(nullable = false, updatable = false)
    private long sgstPaise;

    @Column(nullable = false, updatable = false)
    private long igstPaise;

    @Column(nullable = false, updatable = false)
    private long totalPaise;

    @Column(nullable = false, updatable = false)
    private String sacCode;

    @Column(nullable = false, updatable = false)
    private String sellerName;

    @Column(nullable = false, updatable = false)
    private String sellerAddress;

    @Column(nullable = false, updatable = false)
    private String sellerStateCode;

    @Column(nullable = false, updatable = false)
    private String sellerGstin;

    @Column(nullable = false, updatable = false)
    private String buyerName;

    @Column(updatable = false)
    private String buyerAddress;

    @Column(nullable = false, updatable = false)
    private String buyerStateCode;

    @Column(updatable = false)
    private String buyerGstin;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private long paidPaise;

    private LocalDate paidOn;

    @Column(nullable = false, updatable = false)
    private String issuedByName;

    private Instant cancelledAt;

    private String cancelledByName;

    private String cancelReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Invoice() {
    }

    Invoice(Issue issue) {
        this.id = Ids.newId();
        this.invoiceNo = issue.invoiceNo();
        this.financialYear = issue.financialYear();
        this.seq = issue.seq();
        this.invoiceDate = issue.invoiceDate();
        this.dueDate = issue.dueDate();
        this.plan = issue.plan();
        this.billingCycle = issue.cycle();
        this.periodStart = issue.periodStart();
        this.periodEnd = issue.periodEnd();
        this.billedStudents = issue.billedStudents();
        this.unitPricePaise = issue.unitPricePaise();
        this.taxablePaise = issue.tax().taxablePaise();
        this.taxSplit = issue.tax().split();
        this.cgstPaise = issue.tax().cgstPaise();
        this.sgstPaise = issue.tax().sgstPaise();
        this.igstPaise = issue.tax().igstPaise();
        this.totalPaise = issue.tax().totalPaise();
        this.sacCode = issue.sacCode();
        this.sellerName = issue.seller().name();
        this.sellerAddress = issue.seller().address();
        this.sellerStateCode = issue.seller().stateCode();
        this.sellerGstin = issue.seller().gstin();
        this.buyerName = issue.buyerName();
        this.buyerAddress = issue.buyerAddress();
        this.buyerStateCode = issue.buyerStateCode();
        this.buyerGstin = issue.buyerGstin();
        this.issuedByName = issue.issuedByName();
        this.status = ISSUED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Adds a payment; the invoice is PAID once everything is paid, on the day of the last payment. */
    void pay(long amountPaise, LocalDate on) {
        this.paidPaise += amountPaise;
        if (paidPaise == totalPaise) {
            this.status = PAID;
            this.paidOn = on;
        }
    }

    void cancel(String byName, String reason) {
        this.status = CANCELLED;
        this.cancelledAt = Instant.now();
        this.cancelledByName = byName;
        this.cancelReason = reason;
    }

    long balancePaise() {
        return isCancelled() ? 0 : totalPaise - paidPaise;
    }

    boolean isCancelled() {
        return CANCELLED.equals(status);
    }

    boolean isPaid() {
        return PAID.equals(status);
    }

    /** Unpaid and past its due date on {@code today}. */
    boolean isOverdue(LocalDate today) {
        return ISSUED.equals(status) && today.isAfter(dueDate);
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getInvoiceNo() {
        return invoiceNo;
    }

    String getFinancialYear() {
        return financialYear;
    }

    int getSeq() {
        return seq;
    }

    LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    Plan getPlan() {
        return plan;
    }

    BillingCycle getBillingCycle() {
        return billingCycle;
    }

    LocalDate getPeriodStart() {
        return periodStart;
    }

    LocalDate getPeriodEnd() {
        return periodEnd;
    }

    int getBilledStudents() {
        return billedStudents;
    }

    long getUnitPricePaise() {
        return unitPricePaise;
    }

    long getTaxablePaise() {
        return taxablePaise;
    }

    GstMath.Split getTaxSplit() {
        return taxSplit;
    }

    long getCgstPaise() {
        return cgstPaise;
    }

    long getSgstPaise() {
        return sgstPaise;
    }

    long getIgstPaise() {
        return igstPaise;
    }

    long getTotalPaise() {
        return totalPaise;
    }

    String getSacCode() {
        return sacCode;
    }

    String getSellerName() {
        return sellerName;
    }

    String getSellerAddress() {
        return sellerAddress;
    }

    String getSellerStateCode() {
        return sellerStateCode;
    }

    String getSellerGstin() {
        return sellerGstin;
    }

    String getBuyerName() {
        return buyerName;
    }

    String getBuyerAddress() {
        return buyerAddress;
    }

    String getBuyerStateCode() {
        return buyerStateCode;
    }

    String getBuyerGstin() {
        return buyerGstin;
    }

    String getStatus() {
        return status;
    }

    long getPaidPaise() {
        return paidPaise;
    }

    LocalDate getPaidOn() {
        return paidOn;
    }

    String getIssuedByName() {
        return issuedByName;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    String getCancelledByName() {
        return cancelledByName;
    }

    String getCancelReason() {
        return cancelReason;
    }
}
