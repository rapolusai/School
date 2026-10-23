package com.akshara.inventory;

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

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A purchase order; amounts in paise, lines frozen once submitted. */
@Entity
@Table(schema = "inventory", name = "purchase_order")
class PurchaseOrder extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(nullable = false, updatable = false)
    private String financialYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseOrderStatus status;

    @Column(nullable = false)
    private UUID vendorId;

    @Column(nullable = false)
    private String vendorName;

    private UUID requisitionId;

    private UUID rfqId;

    @Column(nullable = false)
    private LocalDate orderedOn;

    private LocalDate expectedOn;

    private String terms;

    private long subtotalPaise;

    private long taxPaise;

    private long totalPaise;

    private Long escalatedAbovePaise;

    private String rejectionReason;

    private String invoiceNumber;

    private LocalDate invoiceDate;

    private Long invoicePaise;

    @Column(updatable = false)
    private UUID createdById;

    @Column(nullable = false, updatable = false)
    private String createdByName;

    private Instant submittedAt;

    private Instant approvedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PurchaseOrder() {
    }

    PurchaseOrder(String number, String financialYear, PurchaseOrderStatus status, UUID vendorId, String vendorName, UUID requisitionId, UUID rfqId, LocalDate orderedOn, LocalDate expectedOn, String terms, long subtotalPaise, long taxPaise, long totalPaise, Long escalatedAbovePaise, String rejectionReason, String invoiceNumber, LocalDate invoiceDate, Long invoicePaise, UUID createdById, String createdByName, Instant submittedAt, Instant approvedAt) {
        this.id = Ids.newId();
        this.number = number;
        this.financialYear = financialYear;
        this.status = status;
        this.vendorId = vendorId;
        this.vendorName = vendorName;
        this.requisitionId = requisitionId;
        this.rfqId = rfqId;
        this.orderedOn = orderedOn;
        this.expectedOn = expectedOn;
        this.terms = terms;
        this.subtotalPaise = subtotalPaise;
        this.taxPaise = taxPaise;
        this.totalPaise = totalPaise;
        this.escalatedAbovePaise = escalatedAbovePaise;
        this.rejectionReason = rejectionReason;
        this.invoiceNumber = invoiceNumber;
        this.invoiceDate = invoiceDate;
        this.invoicePaise = invoicePaise;
        this.createdById = createdById;
        this.createdByName = createdByName;
        this.submittedAt = submittedAt;
        this.approvedAt = approvedAt;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getNumber() {
        return number;
    }

    String getFinancialYear() {
        return financialYear;
    }

    PurchaseOrderStatus getStatus() {
        return status;
    }

    void setStatus(PurchaseOrderStatus status) {
        this.status = status;
    }

    UUID getVendorId() {
        return vendorId;
    }

    void setVendorId(UUID vendorId) {
        this.vendorId = vendorId;
    }

    String getVendorName() {
        return vendorName;
    }

    void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }

    UUID getRequisitionId() {
        return requisitionId;
    }

    void setRequisitionId(UUID requisitionId) {
        this.requisitionId = requisitionId;
    }

    UUID getRfqId() {
        return rfqId;
    }

    void setRfqId(UUID rfqId) {
        this.rfqId = rfqId;
    }

    LocalDate getOrderedOn() {
        return orderedOn;
    }

    void setOrderedOn(LocalDate orderedOn) {
        this.orderedOn = orderedOn;
    }

    LocalDate getExpectedOn() {
        return expectedOn;
    }

    void setExpectedOn(LocalDate expectedOn) {
        this.expectedOn = expectedOn;
    }

    String getTerms() {
        return terms;
    }

    void setTerms(String terms) {
        this.terms = terms;
    }

    long getSubtotalPaise() {
        return subtotalPaise;
    }

    void setSubtotalPaise(long subtotalPaise) {
        this.subtotalPaise = subtotalPaise;
    }

    long getTaxPaise() {
        return taxPaise;
    }

    void setTaxPaise(long taxPaise) {
        this.taxPaise = taxPaise;
    }

    long getTotalPaise() {
        return totalPaise;
    }

    void setTotalPaise(long totalPaise) {
        this.totalPaise = totalPaise;
    }

    Long getEscalatedAbovePaise() {
        return escalatedAbovePaise;
    }

    void setEscalatedAbovePaise(Long escalatedAbovePaise) {
        this.escalatedAbovePaise = escalatedAbovePaise;
    }

    String getRejectionReason() {
        return rejectionReason;
    }

    void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    String getInvoiceNumber() {
        return invoiceNumber;
    }

    void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    void setInvoiceDate(LocalDate invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    Long getInvoicePaise() {
        return invoicePaise;
    }

    void setInvoicePaise(Long invoicePaise) {
        this.invoicePaise = invoicePaise;
    }

    UUID getCreatedById() {
        return createdById;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    Instant getApprovedAt() {
        return approvedAt;
    }

    void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }
}
