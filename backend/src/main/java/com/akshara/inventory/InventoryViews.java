package com.akshara.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.akshara.accounts.ApprovalAction;

/** Response bodies of the inventory API (docs/api/phase-3-inventory.md). */
public final class InventoryViews {

    private InventoryViews() {
    }

    public record ApproverView(UUID userId, String userName, Long limitPaise) {
    }

    public record CategoryView(UUID id, String name, int depreciationRateBp, boolean active, boolean inUse) {
    }

    public record LocationView(UUID id, String name, LocationKind kind, boolean active, boolean inUse) {
    }

    public record PersonView(UUID id, String name, String departmentName) {
    }

    public record DepartmentView(UUID id, String name) {
    }

    public record VendorRef(UUID id, String name, boolean active) {
    }

    /** The masters a form needs: categories, locations, vendors, departments and staff. */
    public record Lookups(List<CategoryView> categories, List<LocationView> locations, List<VendorRef> vendors,
            List<DepartmentView> departments, List<PersonView> people) {
    }

    public record ItemView(UUID id, String name, UUID categoryId, String categoryName, String unit, ItemKind kind,
            int reorderLevel, UUID preferredVendorId, String preferredVendorName, boolean active, long stock,
            boolean low) {
    }

    public record MovementView(UUID id, UUID itemId, String itemName, String unit, MovementKind kind, int direction,
            int quantity, Long unitCostPaise, LocalDate movedOn, UUID locationId, String locationName,
            UUID departmentId, String departmentName, UUID issuedToId, String issuedToName, String purpose,
            UUID purchaseOrderId, UUID goodsReceiptId, UUID stockTakeId, String createdByName, long balance) {
    }

    public record ItemLedger(ItemView item, List<MovementView> movements) {
    }

    public record StockTakeLineView(UUID itemId, String itemName, String unit, int expectedQty, Integer countedQty,
            Integer variance) {
    }

    public record StockTakeView(UUID id, UUID locationId, String locationName, StockTakeStatus status,
            LocalDate startedOn, Instant closedAt, String notes, String createdByName, int lines, int counted,
            int variances, List<StockTakeLineView> items) {
    }

    public record AssetView(UUID id, String assetTag, String name, UUID categoryId, String categoryName,
            int depreciationRateBp, UUID itemId, UUID locationId, String locationName, UUID custodianId,
            String custodianName, LocalDate purchaseDate, long costPaise, UUID vendorId, String vendorName,
            String serialNo, AssetCondition condition, LocalDate warrantyExpiresOn, boolean warrantyExpired,
            AssetStatus status, String disposalReason, String disposalRequestedBy, LocalDate disposedOn,
            String disposedByName, String notes, LocalDate lastVerifiedOn, long depreciationPaise,
            long writtenDownPaise) {
    }

    public record TransferView(UUID id, String fromLocationName, String toLocationName, String fromCustodianName,
            String toCustodianName, String reason, LocalDate movedOn, String createdByName) {
    }

    public record AssetDetail(AssetView asset, List<TransferView> transfers) {
    }

    /** What a label needs: the tag, the name and where it belongs. */
    public record AssetLabel(UUID id, String assetTag, String name, String categoryName, String locationName) {
    }

    public record VerificationItemView(UUID assetId, String assetTag, String assetName, String locationName,
            String custodianName, VerificationResult result, String note, Instant checkedAt) {
    }

    public record RoundView(UUID id, String name, UUID locationId, String locationName, StockTakeStatus status,
            LocalDate startedOn, Instant closedAt, String createdByName, int assets, int checked, int found,
            int missing, int damaged, List<VerificationItemView> items) {
    }

    public record ApprovalView(ApprovalStage stage, ApprovalAction action, UUID approverId, String approverName,
            Long limitPaise, String note, Instant at) {
    }

    public record RequisitionLineView(UUID id, int lineNo, UUID itemId, String itemName, String description,
            int quantity, String unit, long estimatedUnitPaise, long estimatedPaise) {
    }

    public record RequisitionView(UUID id, String number, RequisitionStatus status, UUID departmentId,
            String departmentName, UUID requestedById, String requestedByName, LocalDate neededBy, String purpose,
            long estimatedPaise, UUID headUserId, String headName, Long escalatedAbovePaise, String rejectionReason,
            Instant submittedAt, Instant decidedAt, Instant createdAt, List<RequisitionLineView> lines,
            List<ApprovalView> approvals, boolean canAct) {
    }

    public record QuoteView(UUID id, UUID vendorId, String vendorName, long amountPaise, Integer deliveryDays,
            LocalDate validUntil, String notes, boolean selected) {
    }

    public record RfqView(UUID id, String number, UUID requisitionId, String requisitionNumber, String title,
            RfqStatus status, UUID selectedQuoteId, String selectionReason, String createdByName, Instant createdAt,
            List<QuoteView> quotes) {
    }

    public record OrderLineView(UUID id, int lineNo, UUID itemId, String itemName, String description, int quantity,
            String unit, long unitPaise, int gstRateBp, long amountPaise, long taxPaise, long totalPaise,
            int receivedQty) {
    }

    public record ReceiptLineView(UUID poLineId, String description, int quantity, String unit, long unitPaise) {
    }

    public record ReceiptView(UUID id, String number, LocalDate receivedOn, UUID locationId, String locationName,
            String invoiceNumber, String notes, long totalPaise, String receivedByName, List<ReceiptLineView> lines) {
    }

    public record OrderRow(UUID id, String number, PurchaseOrderStatus status, UUID vendorId, String vendorName,
            LocalDate orderedOn, LocalDate expectedOn, long totalPaise, int lines, int receivedLines,
            String requisitionNumber, Instant createdAt) {
    }

    public record OrderView(UUID id, String number, String financialYear, PurchaseOrderStatus status, UUID vendorId,
            String vendorName, String vendorGstin, String vendorAddress, UUID requisitionId, String requisitionNumber,
            UUID rfqId, String rfqNumber, LocalDate orderedOn, LocalDate expectedOn, String terms,
            long subtotalPaise, long taxPaise, long totalPaise, Long escalatedAbovePaise, String rejectionReason,
            String invoiceNumber, LocalDate invoiceDate, Long invoicePaise, String createdByName, Instant submittedAt,
            Instant approvedAt, Instant createdAt, List<OrderLineView> lines, List<ApprovalView> approvals,
            List<ReceiptView> receipts, boolean canAct) {
    }

    /** The signed-in approver's queue: what waits, their limit, and what they can act on. */
    public record ApprovalQueue(List<RequisitionView> requisitions, List<OrderRow> orders, Long limitPaise,
            boolean limited) {
    }

    /** The dashboard card for storekeepers and the School Admin. */
    public record InventoryCard(long lowStockItems, long requisitionsWaiting, long ordersWaiting,
            long openVerificationRounds, long assetsVerificationDue) {
    }

    // ------------------------------------------------------------------ reports

    public record CategoryStock(UUID categoryId, String categoryName, int items, long stock, long valuePaise) {
    }

    public record StockOnHand(LocalDate asOf, List<CategoryStock> categories, List<ItemView> items) {
    }

    public record LowStock(List<ItemView> items) {
    }

    public record LocationAssets(UUID locationId, String locationName, int assets, long costPaise,
            long writtenDownPaise, List<AssetView> items) {
    }

    public record AssetRegister(LocalDate asOf, long assets, long costPaise, long writtenDownPaise,
            List<LocationAssets> locations) {
    }

    public record CustodianAssets(UUID custodianId, String custodianName, int assets, long costPaise,
            List<AssetView> items) {
    }

    public record AssetsByCustodian(long assets, List<CustodianAssets> custodians) {
    }

    public record StatusCount(PurchaseOrderStatus status, long orders, long totalPaise) {
    }

    public record VendorOrders(UUID vendorId, String vendorName, long orders, long totalPaise) {
    }

    public record OrdersReport(List<StatusCount> byStatus, List<VendorOrders> byVendor, List<OrderRow> orders) {
    }

    public record ConsumptionCell(String department, String month, long quantity, long valuePaise) {
    }

    public record Consumption(LocalDate from, LocalDate to, List<String> months, List<String> departments,
            List<ConsumptionCell> cells) {
    }
}
