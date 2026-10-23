package com.akshara.inventory;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request bodies of the inventory API (docs/api/phase-3-inventory.md). The demo data uses the same records. */
public final class InventoryForms {

    /** An amount is at most ₹10 crore. */
    static final long MAX_PAISE = 10_000_000_000L;
    static final int MAX_QTY = 1_000_000;

    private InventoryForms() {
    }

    public record ApproverInput(
            @NotNull UUID userId,
            @Positive @Max(MAX_PAISE) Long limitPaise) {
    }

    /** The whole list of approvers with limits; it replaces the previous one. */
    public record ApproversForm(@NotNull @Size(max = 20) List<@NotNull @Valid ApproverInput> approvers) {
    }

    public record CategoryForm(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Min(0) @Max(10_000) Integer depreciationRateBp,
            @NotNull Boolean active) {
    }

    public record LocationForm(
            @NotBlank @Size(max = 80) String name,
            @NotNull LocationKind kind,
            @NotNull Boolean active) {
    }

    public record ItemForm(
            @NotBlank @Size(max = 120) String name,
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 20) String unit,
            @NotNull ItemKind kind,
            @NotNull @Min(0) @Max(MAX_QTY) Integer reorderLevel,
            UUID preferredVendorId,
            @NotNull Boolean active) {
    }

    /** A direct goods receipt (not against a purchase order). */
    public record ReceiptForm(
            @NotNull UUID itemId,
            UUID locationId,
            @NotNull @Positive @Max(MAX_QTY) Integer quantity,
            @PositiveOrZero @Max(MAX_PAISE) Long unitCostPaise,
            @NotNull LocalDate movedOn,
            @Size(max = 500) String purpose) {
    }

    /** An issue to a department or person, or a return from them. */
    public record IssueForm(
            @NotNull UUID itemId,
            UUID locationId,
            @NotNull @Positive @Max(MAX_QTY) Integer quantity,
            @NotNull LocalDate movedOn,
            UUID departmentId,
            UUID issuedToId,
            @NotBlank @Size(max = 500) String purpose) {
    }

    public record WriteOffForm(
            @NotNull UUID itemId,
            UUID locationId,
            @NotNull @Positive @Max(MAX_QTY) Integer quantity,
            @NotNull LocalDate movedOn,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record StockTakeForm(UUID locationId, @Size(max = 500) String notes) {
    }

    public record CountInput(@NotNull UUID itemId, @NotNull @Min(0) @Max(MAX_QTY) Integer countedQty) {
    }

    public record CountForm(@NotEmpty @Size(max = 500) List<@NotNull @Valid CountInput> counts) {
    }

    public record AssetForm(
            @NotBlank @Size(max = 120) String name,
            @NotNull UUID categoryId,
            UUID itemId,
            UUID locationId,
            UUID custodianId,
            @NotNull LocalDate purchaseDate,
            @NotNull @PositiveOrZero @Max(MAX_PAISE) Long costPaise,
            UUID vendorId,
            @Size(max = 80) String serialNo,
            @NotNull AssetCondition condition,
            LocalDate warrantyExpiresOn,
            @Size(max = 500) String notes) {
    }

    public record TransferForm(
            UUID toLocationId,
            UUID toCustodianId,
            @Size(max = 500) String reason,
            @NotNull LocalDate movedOn) {
    }

    public record ReasonForm(@NotBlank @Size(max = 500) String reason) {
    }

    public record NoteForm(@Size(max = 500) String note) {
    }

    public record RoundForm(@NotBlank @Size(max = 120) String name, UUID locationId) {
    }

    /** One asset checked in a round, by id or by scanning its tag. */
    public record VerifyForm(
            UUID assetId,
            @Size(max = 20) String assetTag,
            @NotNull VerificationResult result,
            @Size(max = 300) String note) {
    }

    public record RequisitionLineInput(
            UUID itemId,
            @NotBlank @Size(max = 200) String description,
            @NotNull @Positive @Max(MAX_QTY) Integer quantity,
            @NotBlank @Size(max = 20) String unit,
            @NotNull @PositiveOrZero @Max(MAX_PAISE) Long estimatedUnitPaise) {
    }

    public record RequisitionForm(
            UUID departmentId,
            LocalDate neededBy,
            @NotBlank @Size(max = 500) String purpose,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid RequisitionLineInput> lines) {
    }

    public record RfqForm(@NotBlank @Size(max = 200) String title, UUID requisitionId) {
    }

    public record QuoteForm(
            @NotNull UUID vendorId,
            @NotNull @Positive @Max(MAX_PAISE) Long amountPaise,
            @Min(0) @Max(365) Integer deliveryDays,
            LocalDate validUntil,
            @Size(max = 500) String notes) {
    }

    public record SelectQuoteForm(@NotNull UUID quoteId, @NotBlank @Size(max = 500) String reason) {
    }

    public record OrderLineInput(
            UUID itemId,
            @NotBlank @Size(max = 200) String description,
            @NotNull @Positive @Max(MAX_QTY) Integer quantity,
            @NotBlank @Size(max = 20) String unit,
            @NotNull @PositiveOrZero @Max(MAX_PAISE) Long unitPaise,
            @NotNull @Min(0) @Max(10_000) Integer gstRateBp) {
    }

    public record PurchaseOrderForm(
            @NotNull UUID vendorId,
            UUID requisitionId,
            UUID rfqId,
            @NotNull LocalDate orderedOn,
            LocalDate expectedOn,
            @Size(max = 1000) String terms,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid OrderLineInput> lines) {
    }

    public record ReceiveLineInput(@NotNull UUID poLineId, @NotNull @Positive @Max(MAX_QTY) Integer quantity) {
    }

    public record ReceiveForm(
            @NotNull LocalDate receivedOn,
            UUID locationId,
            @Size(max = 60) String invoiceNumber,
            @Size(max = 500) String notes,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid ReceiveLineInput> lines) {
    }

    public record InvoiceForm(
            @NotBlank @Size(max = 60) String invoiceNumber,
            @NotNull LocalDate invoiceDate,
            @NotNull @Positive @Max(MAX_PAISE) Long invoicePaise) {
    }

    /** Trimmed, null when blank. */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return v.isEmpty() ? null : v;
    }
}
