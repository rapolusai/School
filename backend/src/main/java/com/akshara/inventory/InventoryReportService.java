package com.akshara.inventory;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsViews.VendorView;
import com.akshara.inventory.InventoryCsv.Download;
import com.akshara.inventory.InventoryCsv.Money;
import com.akshara.inventory.InventoryViews.AssetRegister;
import com.akshara.inventory.InventoryViews.AssetView;
import com.akshara.inventory.InventoryViews.AssetsByCustodian;
import com.akshara.inventory.InventoryViews.CategoryStock;
import com.akshara.inventory.InventoryViews.Consumption;
import com.akshara.inventory.InventoryViews.ConsumptionCell;
import com.akshara.inventory.InventoryViews.CustodianAssets;
import com.akshara.inventory.InventoryViews.ItemView;
import com.akshara.inventory.InventoryViews.LocationAssets;
import com.akshara.inventory.InventoryViews.LowStock;
import com.akshara.inventory.InventoryViews.OrderRow;
import com.akshara.inventory.InventoryViews.OrdersReport;
import com.akshara.inventory.InventoryViews.StatusCount;
import com.akshara.inventory.InventoryViews.StockOnHand;
import com.akshara.inventory.InventoryViews.VendorOrders;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The inventory reports: stock on hand by category (valued at the last receipt cost), low stock, the asset register
 * by location with depreciation, assets by custodian, purchase orders by status and vendor, and consumption by
 * department and month. Each also as CSV for Excel.
 */
@Service
@Transactional(readOnly = true)
public class InventoryReportService {

    static final int MAX_DAYS = 400;

    private final ItemRepository items;
    private final StockMovementRepository movements;
    private final AssetRepository assets;
    private final StockService stock;
    private final AssetService assetService;
    private final ProcurementService procurement;
    private final InventorySetupService setup;

    InventoryReportService(ItemRepository items, StockMovementRepository movements, AssetRepository assets,
            StockService stock, AssetService assetService, ProcurementService procurement,
            InventorySetupService setup) {
        this.items = items;
        this.movements = movements;
        this.assets = assets;
        this.stock = stock;
        this.assetService = assetService;
        this.procurement = procurement;
        this.setup = setup;
    }

    // ------------------------------------------------------------------ stock

    public StockOnHand stockOnHand() {
        TenantContext.require();
        Map<UUID, Long> lastCost = lastCostByItem();
        List<ItemView> all = stock.items().stream().filter(i -> i.kind() == ItemKind.CONSUMABLE).toList();
        Map<UUID, CategoryStock> byCategory = new LinkedHashMap<>();
        for (ItemView i : all) {
            CategoryStock c = byCategory.get(i.categoryId());
            long value = i.stock() * lastCost.getOrDefault(i.id(), 0L);
            byCategory.put(i.categoryId(), new CategoryStock(i.categoryId(), i.categoryName(),
                    (c == null ? 0 : c.items()) + 1, (c == null ? 0 : c.stock()) + i.stock(),
                    (c == null ? 0 : c.valuePaise()) + value));
        }
        List<CategoryStock> categories = new ArrayList<>(byCategory.values());
        categories.sort((a, b) -> String.valueOf(a.categoryName()).compareToIgnoreCase(
                String.valueOf(b.categoryName())));
        return new StockOnHand(StockService.today(), categories, all);
    }

    public Download stockOnHandCsv() {
        StockOnHand r = stockOnHand();
        InventoryCsv csv = new InventoryCsv("Category", "Item", "Unit", "Stock", "Reorder level", "Low");
        for (ItemView i : r.items()) {
            csv.row(i.categoryName(), i.name(), i.unit(), i.stock(), i.reorderLevel(), i.low() ? "Yes" : "No");
        }
        return csv.download("stock-on-hand-" + r.asOf() + ".csv");
    }

    public LowStock lowStock() {
        TenantContext.require();
        return new LowStock(stock.items().stream().filter(ItemView::low).toList());
    }

    public Download lowStockCsv() {
        InventoryCsv csv = new InventoryCsv("Item", "Category", "Unit", "Stock", "Reorder level", "Short by",
                "Preferred vendor");
        for (ItemView i : lowStock().items()) {
            csv.row(i.name(), i.categoryName(), i.unit(), i.stock(), i.reorderLevel(),
                    Math.max(0, i.reorderLevel() - i.stock()), i.preferredVendorName());
        }
        return csv.download("low-stock-" + StockService.today() + ".csv");
    }

    private Map<UUID, Long> lastCostByItem() {
        Map<UUID, Long> cost = new LinkedHashMap<>();
        for (StockMovement m : movements.findAllOrdered()) {
            if (m.getKind() == MovementKind.RECEIPT && m.getUnitCostPaise() != null) {
                cost.put(m.getItemId(), m.getUnitCostPaise());
            }
        }
        return cost;
    }

    // ------------------------------------------------------------------ assets

    public AssetRegister assetRegister() {
        TenantContext.require();
        AssetService.Context c = assetService.context();
        Map<String, List<AssetView>> byLocation = new TreeMap<>();
        List<AssetView> views = assets.findLive().stream().map(a -> AssetService.view(a, c)).toList();
        for (AssetView a : views) {
            byLocation.computeIfAbsent(a.locationName() == null ? "" : a.locationName(), k -> new ArrayList<>())
                    .add(a);
        }
        List<LocationAssets> locations = byLocation.entrySet().stream().map(e -> new LocationAssets(
                e.getValue().get(0).locationId(), e.getKey().isEmpty() ? null : e.getKey(), e.getValue().size(),
                e.getValue().stream().mapToLong(AssetView::costPaise).sum(),
                e.getValue().stream().mapToLong(AssetView::writtenDownPaise).sum(), e.getValue())).toList();
        return new AssetRegister(c.today(), views.size(), views.stream().mapToLong(AssetView::costPaise).sum(),
                views.stream().mapToLong(AssetView::writtenDownPaise).sum(), locations);
    }

    public Download assetRegisterCsv() {
        AssetRegister r = assetRegister();
        InventoryCsv csv = new InventoryCsv("Location", "Tag", "Asset", "Category", "Custodian", "Purchased",
                "Cost", "Depreciation", "Written down", "Condition", "Status");
        for (LocationAssets l : r.locations()) {
            for (AssetView a : l.items()) {
                csv.row(l.locationName(), a.assetTag(), a.name(), a.categoryName(), a.custodianName(),
                        a.purchaseDate(), new Money(a.costPaise()), new Money(a.depreciationPaise()),
                        new Money(a.writtenDownPaise()), a.condition().name(), a.status().name());
            }
        }
        return csv.download("asset-register-" + r.asOf() + ".csv");
    }

    public AssetsByCustodian assetsByCustodian() {
        TenantContext.require();
        AssetService.Context c = assetService.context();
        Map<String, List<AssetView>> byCustodian = new TreeMap<>();
        List<AssetView> views = assets.findLive().stream().map(a -> AssetService.view(a, c)).toList();
        for (AssetView a : views) {
            byCustodian.computeIfAbsent(a.custodianName() == null ? "" : a.custodianName(), k -> new ArrayList<>())
                    .add(a);
        }
        return new AssetsByCustodian(views.size(), byCustodian.entrySet().stream().map(e -> new CustodianAssets(
                e.getValue().get(0).custodianId(), e.getKey().isEmpty() ? null : e.getKey(), e.getValue().size(),
                e.getValue().stream().mapToLong(AssetView::costPaise).sum(), e.getValue())).toList());
    }

    public Download assetsByCustodianCsv() {
        InventoryCsv csv = new InventoryCsv("Custodian", "Tag", "Asset", "Category", "Location", "Cost",
                "Condition");
        for (CustodianAssets cu : assetsByCustodian().custodians()) {
            for (AssetView a : cu.items()) {
                csv.row(cu.custodianName(), a.assetTag(), a.name(), a.categoryName(), a.locationName(),
                        new Money(a.costPaise()), a.condition().name());
            }
        }
        return csv.download("assets-by-custodian-" + StockService.today() + ".csv");
    }

    // ------------------------------------------------------------------ purchase orders

    public OrdersReport orders(LocalDate from, LocalDate to) {
        TenantContext.require();
        period(from, to);
        List<OrderRow> rows = procurement.rows(procurement.allOrders().stream()
                .filter(o -> !o.getOrderedOn().isBefore(from) && !o.getOrderedOn().isAfter(to)).toList());
        Map<PurchaseOrderStatus, List<OrderRow>> byStatus = rows.stream()
                .collect(Collectors.groupingBy(OrderRow::status));
        List<StatusCount> statuses = new ArrayList<>();
        for (PurchaseOrderStatus s : PurchaseOrderStatus.values()) {
            List<OrderRow> l = byStatus.getOrDefault(s, List.of());
            statuses.add(new StatusCount(s, l.size(), l.stream().mapToLong(OrderRow::totalPaise).sum()));
        }
        Map<UUID, VendorView> vendors = setup.vendorsById();
        Map<UUID, List<OrderRow>> byVendor = rows.stream().collect(Collectors.groupingBy(OrderRow::vendorId));
        List<VendorOrders> vendorRows = byVendor.entrySet().stream().map(e -> new VendorOrders(e.getKey(),
                InventorySetupService.vendorName(vendors, e.getKey()) == null ? e.getValue().get(0).vendorName()
                        : InventorySetupService.vendorName(vendors, e.getKey()), e.getValue().size(),
                e.getValue().stream().filter(o -> o.status() != PurchaseOrderStatus.REJECTED
                        && o.status() != PurchaseOrderStatus.CANCELLED).mapToLong(OrderRow::totalPaise).sum()))
                .sorted((a, b) -> Long.compare(b.totalPaise(), a.totalPaise())).toList();
        return new OrdersReport(statuses, vendorRows, rows);
    }

    public Download ordersCsv(LocalDate from, LocalDate to) {
        OrdersReport r = orders(from, to);
        InventoryCsv csv = new InventoryCsv("Number", "Status", "Vendor", "Ordered on", "Expected on", "Total",
                "Lines", "Lines received", "Requisition");
        for (OrderRow o : r.orders()) {
            csv.row(o.number(), o.status().name(), o.vendorName(), o.orderedOn(), o.expectedOn(),
                    new Money(o.totalPaise()), o.lines(), o.receivedLines(), o.requisitionNumber());
        }
        return csv.download("purchase-orders-" + from + "-to-" + to + ".csv");
    }

    // ------------------------------------------------------------------ consumption

    /** Issues (less returns) per department and month, in units and at the last receipt cost. */
    public Consumption consumption(LocalDate from, LocalDate to) {
        TenantContext.require();
        period(from, to);
        Map<UUID, Long> lastCost = lastCostByItem();
        Map<String, Map<String, long[]>> cells = new TreeMap<>();
        List<String> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            months.add(m.toString());
        }
        for (StockMovement m : movements.findBetween(from, to)) {
            if (m.getKind() != MovementKind.ISSUE && m.getKind() != MovementKind.RETURN) {
                continue;
            }
            String department = m.getDepartmentName() == null ? "—" : m.getDepartmentName();
            String month = YearMonth.from(m.getMovedOn()).toString();
            long[] cell = cells.computeIfAbsent(department, k -> new LinkedHashMap<>())
                    .computeIfAbsent(month, k -> new long[2]);
            long signed = m.getKind() == MovementKind.ISSUE ? m.getQuantity() : -m.getQuantity();
            cell[0] += signed;
            cell[1] += signed * lastCost.getOrDefault(m.getItemId(), 0L);
        }
        List<ConsumptionCell> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, long[]>> d : cells.entrySet()) {
            for (Map.Entry<String, long[]> c : d.getValue().entrySet()) {
                rows.add(new ConsumptionCell(d.getKey(), c.getKey(), c.getValue()[0], c.getValue()[1]));
            }
        }
        return new Consumption(from, to, months, new ArrayList<>(cells.keySet()), rows);
    }

    public Download consumptionCsv(LocalDate from, LocalDate to) {
        Consumption r = consumption(from, to);
        InventoryCsv csv = new InventoryCsv("Department", "Month", "Quantity", "Value");
        for (ConsumptionCell c : r.cells()) {
            csv.row(c.department(), c.month(), c.quantity(), new Money(c.valuePaise()));
        }
        return csv.download("consumption-" + from + "-to-" + to + ".csv");
    }

    static void period(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw ApiException.badRequest("Give both dates.", "from");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end date is before the start date.", "to");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw ApiException.badRequest("Give a period of at most " + MAX_DAYS + " days.", "to");
        }
    }

    /** Items the storekeeper should reorder, for the dashboard card. */
    long lowStockCount() {
        return stock.items().stream().filter(ItemView::low).count();
    }

    /** Assets in use that were never verified or not in the last year. */
    long verificationDue() {
        LocalDate cutoff = StockService.today().minusYears(1);
        return assets.findLive().stream()
                .filter(a -> a.getLastVerifiedOn() == null || a.getLastVerifiedOn().isBefore(cutoff)).count();
    }
}
