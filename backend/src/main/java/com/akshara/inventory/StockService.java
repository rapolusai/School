package com.akshara.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsViews.VendorView;
import com.akshara.accounts.FinancialYear;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.inventory.InventoryForms.CountForm;
import com.akshara.inventory.InventoryForms.CountInput;
import com.akshara.inventory.InventoryForms.IssueForm;
import com.akshara.inventory.InventoryForms.ItemForm;
import com.akshara.inventory.InventoryForms.ReceiptForm;
import com.akshara.inventory.InventoryForms.StockTakeForm;
import com.akshara.inventory.InventoryForms.WriteOffForm;
import com.akshara.inventory.InventoryViews.ItemLedger;
import com.akshara.inventory.InventoryViews.ItemView;
import com.akshara.inventory.InventoryViews.MovementView;
import com.akshara.inventory.InventoryViews.StockTakeLineView;
import com.akshara.inventory.InventoryViews.StockTakeView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffPayrollInputs.PayrollPerson;
import com.akshara.staff.StaffService.DepartmentRef;

/**
 * The item master and the stock ledger. Stock is never stored: it is the sum of an item's movements. Receipts add,
 * issues and write-offs take (never below zero), returns bring back, and a closed stock take posts the variance as
 * adjustments. Every change is audited.
 */
@Service
@Transactional
public class StockService {

    private final ItemRepository items;
    private final StockMovementRepository movements;
    private final StockTakeRepository takes;
    private final StockTakeLineRepository takeLines;
    private final InventorySetupService setup;
    private final AuditService audit;

    StockService(ItemRepository items, StockMovementRepository movements, StockTakeRepository takes,
            StockTakeLineRepository takeLines, InventorySetupService setup, AuditService audit) {
        this.items = items;
        this.movements = movements;
        this.takes = takes;
        this.takeLines = takeLines;
        this.setup = setup;
        this.audit = audit;
    }

    static LocalDate today() {
        return FinancialYear.today();
    }

    // ------------------------------------------------------------------ items

    @Transactional(readOnly = true)
    public List<ItemView> items() {
        TenantContext.require();
        return views(items.findAllOrdered());
    }

    @Transactional(readOnly = true)
    public ItemView item(UUID id) {
        TenantContext.require();
        return view(items.findById(id).orElseThrow(() -> ApiException.notFound("Item")));
    }

    public ItemView create(ItemForm form, Actor actor) {
        TenantContext.require();
        Fields f = fields(form, new UUID(0, 0));
        Item i = items.saveAndFlush(new Item(f.name(), f.category().getId(), form.unit().strip(), form.kind(),
                form.reorderLevel(), f.vendor() == null ? null : f.vendor().id(), form.active()));
        audit.record(actor, "inventory_item.created", "inventory_item", i.getId(), details(i, f));
        return view(i);
    }

    public ItemView update(UUID id, ItemForm form, Actor actor) {
        TenantContext.require();
        Item i = items.findById(id).orElseThrow(() -> ApiException.notFound("Item"));
        Fields f = fields(form, id);
        if (i.getKind() != form.kind() && movements.anyForItem(id)) {
            throw ApiException.conflict("This item has stock movements; its kind cannot change.", "kind");
        }
        i.setName(f.name());
        i.setCategoryId(f.category().getId());
        i.setUnit(form.unit().strip());
        i.setKind(form.kind());
        i.setReorderLevel(form.reorderLevel());
        i.setPreferredVendorId(f.vendor() == null ? null : f.vendor().id());
        i.setActive(form.active());
        items.flush();
        audit.record(actor, "inventory_item.updated", "inventory_item", id, details(i, f));
        return view(i);
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        Item i = items.findById(id).orElseThrow(() -> ApiException.notFound("Item"));
        if (movements.anyForItem(id)) {
            throw ApiException.conflict("This item has stock movements; mark it inactive instead.", "id");
        }
        items.delete(i);
        items.flush();
        audit.record(actor, "inventory_item.deleted", "inventory_item", id, Map.of("name", i.getName()));
    }

    private record Fields(String name, Category category, VendorView vendor) {
    }

    private Fields fields(ItemForm form, UUID exceptId) {
        String name = form.name().strip();
        if (items.nameTaken(name, exceptId)) {
            throw ApiException.conflict("An item with this name exists.", "name");
        }
        return new Fields(name, setup.category(form.categoryId(), "categoryId"),
                setup.vendor(form.preferredVendorId(), "preferredVendorId"));
    }

    private static Map<String, Object> details(Item i, Fields f) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", i.getName());
        d.put("category", f.category().getName());
        d.put("kind", i.getKind().name());
        d.put("unit", i.getUnit());
        d.put("reorderLevel", i.getReorderLevel());
        if (f.vendor() != null) {
            d.put("preferredVendor", f.vendor().name());
        }
        d.put("active", i.isActive());
        return d;
    }

    /**
     * The item, which must be active and a consumable, for a stock movement; 400 on the field otherwise. The item's
     * row is locked until the transaction ends, so concurrent movements of one item are checked one after another
     * and a race cannot take the stock below zero.
     */
    Item consumable(UUID id, String field) {
        Item i = items.findForUpdate(id).filter(Item::isActive)
                .orElseThrow(() -> ApiException.badRequest("Pick an item.", field));
        if (i.getKind() != ItemKind.CONSUMABLE) {
            throw ApiException.badRequest("Only consumables are counted as stock; assets go in the register.", field);
        }
        return i;
    }

    Map<UUID, Item> itemsById() {
        return items.findAll().stream().collect(Collectors.toMap(Item::getId, Function.identity()));
    }

    /** Stock per item, from every movement. */
    Map<UUID, Long> stockByItem() {
        Map<UUID, Long> stock = new HashMap<>();
        for (StockMovement m : movements.findAllOrdered()) {
            stock.merge(m.getItemId(), (long) m.getDirection() * m.getQuantity(), Long::sum);
        }
        return stock;
    }

    long stockOf(UUID itemId) {
        return InventoryMath.stock(movements.findByItem(itemId));
    }

    List<ItemView> views(List<Item> list) {
        Map<UUID, Category> categories = setup.categoriesById();
        Map<UUID, VendorView> vendors = setup.vendorsById();
        Map<UUID, Long> stock = stockByItem();
        return list.stream().map(i -> view(i, categories, vendors, stock.getOrDefault(i.getId(), 0L))).toList();
    }

    ItemView view(Item i) {
        return view(i, setup.categoriesById(), setup.vendorsById(), stockOf(i.getId()));
    }

    static ItemView view(Item i, Map<UUID, Category> categories, Map<UUID, VendorView> vendors, long stock) {
        Category c = categories.get(i.getCategoryId());
        return new ItemView(i.getId(), i.getName(), i.getCategoryId(), c == null ? null : c.getName(), i.getUnit(),
                i.getKind(), i.getReorderLevel(), i.getPreferredVendorId(),
                InventorySetupService.vendorName(vendors, i.getPreferredVendorId()), i.isActive(), stock,
                i.getKind() == ItemKind.CONSUMABLE && i.isActive() && stock <= i.getReorderLevel());
    }

    // ------------------------------------------------------------------ ledger and movements

    @Transactional(readOnly = true)
    public ItemLedger ledger(UUID itemId) {
        TenantContext.require();
        Item i = items.findById(itemId).orElseThrow(() -> ApiException.notFound("Item"));
        Map<UUID, Location> locations = setup.locationsById();
        List<MovementView> rows = new ArrayList<>();
        long balance = 0;
        for (StockMovement m : movements.findByItem(itemId)) {
            balance += (long) m.getDirection() * m.getQuantity();
            rows.add(view(m, i, locations, balance));
        }
        return new ItemLedger(view(i), rows);
    }

    @Transactional(readOnly = true)
    public List<MovementView> movements(LocalDate from, LocalDate to, MovementKind kind) {
        TenantContext.require();
        Map<UUID, Item> byId = itemsById();
        Map<UUID, Location> locations = setup.locationsById();
        return movements.findBetween(from, to).stream().filter(m -> kind == null || m.getKind() == kind)
                .map(m -> view(m, byId.get(m.getItemId()), locations, 0)).toList();
    }

    static MovementView view(StockMovement m, Item i, Map<UUID, Location> locations, long balance) {
        return new MovementView(m.getId(), m.getItemId(), i == null ? null : i.getName(),
                i == null ? null : i.getUnit(), m.getKind(), m.getDirection(), m.getQuantity(),
                m.getUnitCostPaise(), m.getMovedOn(), m.getLocationId(),
                InventorySetupService.nameOf(locations, m.getLocationId()), m.getDepartmentId(),
                m.getDepartmentName(), m.getIssuedToId(), m.getIssuedToName(), m.getPurpose(),
                m.getPurchaseOrderId(), m.getGoodsReceiptId(), m.getStockTakeId(), m.getCreatedByName(), balance);
    }

    /** A direct receipt, not against a purchase order. */
    public MovementView receive(ReceiptForm form, Actor actor) {
        TenantContext.require();
        Item i = consumable(form.itemId(), "itemId");
        Location l = setup.location(form.locationId(), "locationId");
        movementDate(form.movedOn(), "movedOn");
        StockMovement m = new StockMovement(i.getId(), l == null ? null : l.getId(), MovementKind.RECEIPT, 1,
                form.quantity(), form.unitCostPaise(), form.movedOn(), null, null, null, null,
                InventoryForms.clean(form.purpose()), null, null, null, actor.id(), actor.name());
        return post(m, i, "stock.received", actor);
    }

    /** Goods received against a purchase order: one movement per line (called by the procurement service). */
    StockMovement receiveFromOrder(Item i, Location l, int quantity, Long unitPaise, LocalDate on, UUID orderId,
            UUID receiptId, String purpose, Actor actor) {
        StockMovement m = new StockMovement(i.getId(), l == null ? null : l.getId(), MovementKind.RECEIPT, 1,
                quantity, unitPaise, on, null, null, null, null, purpose, orderId, receiptId, null, actor.id(),
                actor.name());
        return movements.saveAndFlush(m);
    }

    public MovementView issue(IssueForm form, Actor actor) {
        return move(form, MovementKind.ISSUE, "stock.issued", actor);
    }

    public MovementView giveBack(IssueForm form, Actor actor) {
        return move(form, MovementKind.RETURN, "stock.returned", actor);
    }

    private MovementView move(IssueForm form, MovementKind kind, String action, Actor actor) {
        TenantContext.require();
        Item i = consumable(form.itemId(), "itemId");
        Location l = setup.location(form.locationId(), "locationId");
        DepartmentRef d = setup.department(form.departmentId(), "departmentId");
        PayrollPerson p = setup.person(form.issuedToId(), "issuedToId");
        if (d == null && p == null) {
            throw ApiException.badRequest("Give the department or the person.", "departmentId");
        }
        movementDate(form.movedOn(), "movedOn");
        int direction = InventoryMath.direction(kind, false);
        if (direction < 0) {
            enough(i, form.quantity(), form.movedOn());
        } else {
            issuedEnough(i, form.quantity(), form.movedOn());
        }
        StockMovement m = new StockMovement(i.getId(), l == null ? null : l.getId(), kind, direction,
                form.quantity(), null, form.movedOn(), d == null ? null : d.id(), d == null ? null : d.name(),
                p == null ? null : p.userId(), p == null ? null : p.name(), form.purpose().strip(), null, null, null,
                actor.id(), actor.name());
        return post(m, i, action, actor);
    }

    public MovementView writeOff(WriteOffForm form, Actor actor) {
        TenantContext.require();
        Item i = consumable(form.itemId(), "itemId");
        Location l = setup.location(form.locationId(), "locationId");
        movementDate(form.movedOn(), "movedOn");
        enough(i, form.quantity(), form.movedOn());
        StockMovement m = new StockMovement(i.getId(), l == null ? null : l.getId(), MovementKind.WRITE_OFF, -1,
                form.quantity(), null, form.movedOn(), null, null, null, null, form.reason().strip(), null, null,
                null, actor.id(), actor.name());
        return post(m, i, "stock.written_off", actor);
    }

    /**
     * 409 on the quantity when taking it out on that date would leave the stock below zero on that day or on any
     * later day of the ledger (a back-dated issue must have had the stock then, and must not starve later movements).
     * For a movement dated on or after the ledger's last day this is simply the stock on hand. The item is locked.
     */
    private void enough(Item i, int quantity, LocalDate on) {
        List<StockMovement> ledger = movements.findByItem(i.getId());
        long available = lowestFrom(ledger, on, m -> (long) m.getDirection() * m.getQuantity());
        if (available < quantity) {
            boolean backDated = !ledger.isEmpty() && on.isBefore(ledger.get(ledger.size() - 1).getMovedOn());
            throw ApiException.conflict("Only " + Math.max(available, 0) + " " + i.getUnit() + " in stock"
                    + (backDated ? " from " + on + " onwards." : "."), "quantity");
        }
    }

    /**
     * 409 on the quantity when a return would bring back more than was issued and not yet returned, on that date or
     * on any later day of the ledger: stock that never left the store cannot come back to it.
     */
    private void issuedEnough(Item i, int quantity, LocalDate on) {
        long outstanding = lowestFrom(movements.findByItem(i.getId()), on, m -> switch (m.getKind()) {
            case ISSUE -> (long) m.getQuantity();
            case RETURN -> -(long) m.getQuantity();
            default -> 0L;
        });
        if (outstanding < quantity) {
            throw ApiException.conflict("Only " + Math.max(outstanding, 0) + " " + i.getUnit()
                    + " issued and not yet returned.", "quantity");
        }
    }

    /**
     * The lowest running total of the ledger (ordered by date, then entry) where a new movement dated {@code on}
     * would go (after every movement of that day) and at every later point.
     */
    static long lowestFrom(List<StockMovement> ledger, LocalDate on, Function<StockMovement, Long> amount) {
        long running = 0;
        int n = 0;
        while (n < ledger.size() && !ledger.get(n).getMovedOn().isAfter(on)) {
            running += amount.apply(ledger.get(n++));
        }
        long lowest = running;
        for (; n < ledger.size(); n++) {
            running += amount.apply(ledger.get(n));
            lowest = Math.min(lowest, running);
        }
        return lowest;
    }

    private MovementView post(StockMovement m, Item i, String action, Actor actor) {
        movements.saveAndFlush(m);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("item", i.getName());
        d.put("quantity", m.getQuantity());
        d.put("unit", i.getUnit());
        d.put("movedOn", m.getMovedOn().toString());
        if (m.getDepartmentName() != null) {
            d.put("department", m.getDepartmentName());
        }
        if (m.getIssuedToName() != null) {
            d.put("issuedTo", m.getIssuedToName());
        }
        if (m.getPurpose() != null) {
            d.put("purpose", m.getPurpose());
        }
        audit.record(actor, action, "stock_movement", m.getId(), d);
        return view(m, i, setup.locationsById(), stockOf(i.getId()));
    }

    static void notFuture(LocalDate date, String field) {
        if (date.isAfter(today())) {
            throw ApiException.badRequest("The date cannot be in the future.", field);
        }
    }

    /** How many years back a stock movement or a purchase order may be dated. */
    static final int MAX_YEARS_BACK = 5;

    /** A stock movement's or an order's date: not in the future and not more than five years back; 400 otherwise. */
    static void movementDate(LocalDate date, String field) {
        notFuture(date, field);
        if (date.isBefore(today().minusYears(MAX_YEARS_BACK))) {
            throw ApiException.badRequest("The date is more than " + MAX_YEARS_BACK + " years back.", field);
        }
    }

    // ------------------------------------------------------------------ stock takes

    @Transactional(readOnly = true)
    public List<StockTakeView> stockTakes() {
        TenantContext.require();
        Map<UUID, Location> locations = setup.locationsById();
        return takes.findAllNewestFirst().stream().map(t -> view(t, locations, false)).toList();
    }

    @Transactional(readOnly = true)
    public StockTakeView stockTake(UUID id) {
        TenantContext.require();
        return view(takes.findById(id).orElseThrow(() -> ApiException.notFound("Stock take")),
                setup.locationsById(), true);
    }

    /** Starts a stock take with every active consumable's expected quantity frozen. */
    public StockTakeView startStockTake(StockTakeForm form, Actor actor) {
        TenantContext.require();
        Location l = setup.location(form.locationId(), "locationId");
        StockTake t = takes.saveAndFlush(new StockTake(l == null ? null : l.getId(), StockTakeStatus.OPEN, today(),
                null, InventoryForms.clean(form.notes()), actor.name()));
        Map<UUID, Long> stock = stockByItem();
        List<StockTakeLine> lines = new ArrayList<>();
        for (Item i : items.findAllOrdered()) {
            if (i.isActive() && i.getKind() == ItemKind.CONSUMABLE) {
                lines.add(new StockTakeLine(t.getId(), i.getId(), (int) (long) stock.getOrDefault(i.getId(), 0L),
                        null));
            }
        }
        takeLines.saveAllAndFlush(lines);
        audit.record(actor, "stock_take.started", "stock_take", t.getId(),
                Map.of("items", lines.size(), "location", l == null ? "all" : l.getName()));
        return view(t, setup.locationsById(), true);
    }

    public StockTakeView count(UUID id, CountForm form, Actor actor) {
        TenantContext.require();
        StockTake t = takes.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Stock take"));
        open(t);
        Map<UUID, StockTakeLine> lines = takeLines.findByTake(id).stream()
                .collect(Collectors.toMap(StockTakeLine::getItemId, Function.identity()));
        for (int n = 0; n < form.counts().size(); n++) {
            CountInput c = form.counts().get(n);
            StockTakeLine line = lines.get(c.itemId());
            if (line == null) {
                throw ApiException.badRequest("This item is not in the stock take.", "counts[" + n + "].itemId");
            }
            line.setCountedQty(c.countedQty());
        }
        takeLines.flush();
        audit.record(actor, "stock_take.counted", "stock_take", id, Map.of("items", form.counts().size()));
        return view(t, setup.locationsById(), true);
    }

    /** Closes the stock take and posts each counted variance as an adjustment. Uncounted items are left alone. */
    public StockTakeView closeStockTake(UUID id, Actor actor) {
        TenantContext.require();
        StockTake t = takes.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Stock take"));
        open(t);
        // Lock the counted items, in a fixed order, so no issue slips in between reading the stock and adjusting it.
        takeLines.findByTake(id).stream().filter(l -> l.getCountedQty() != null).map(StockTakeLine::getItemId)
                .sorted().forEach(items::findForUpdate);
        Map<UUID, Item> byId = itemsById();
        Map<UUID, Long> stock = stockByItem();
        int adjustments = 0;
        for (StockTakeLine line : takeLines.findByTake(id)) {
            if (line.getCountedQty() == null) {
                continue;
            }
            long current = stock.getOrDefault(line.getItemId(), 0L);
            long variance = line.getCountedQty() - current;
            if (variance == 0) {
                continue;
            }
            Item i = byId.get(line.getItemId());
            movements.save(new StockMovement(line.getItemId(), t.getLocationId(), MovementKind.ADJUSTMENT,
                    variance > 0 ? 1 : -1, (int) Math.abs(variance), null, today(), null, null, null, null,
                    "Stock take variance", null, null, t.getId(), actor.id(), actor.name()));
            audit.record(actor, "stock.adjusted", "stock_movement", null, Map.of("item", i.getName(),
                    "variance", variance, "stockTakeId", t.getId().toString()));
            adjustments++;
        }
        t.setStatus(StockTakeStatus.CLOSED);
        t.setClosedAt(Instant.now());
        takes.flush();
        movements.flush();
        audit.record(actor, "stock_take.closed", "stock_take", id, Map.of("adjustments", adjustments));
        return view(t, setup.locationsById(), true);
    }

    private static void open(StockTake t) {
        if (t.getStatus() != StockTakeStatus.OPEN) {
            throw ApiException.conflict("This stock take is closed.", "id");
        }
    }

    private StockTakeView view(StockTake t, Map<UUID, Location> locations, boolean withLines) {
        List<StockTakeLine> lines = takeLines.findByTake(t.getId());
        int counted = 0;
        int variances = 0;
        List<StockTakeLineView> rows = new ArrayList<>();
        Map<UUID, Item> byId = withLines ? itemsById() : Map.of();
        for (StockTakeLine l : lines) {
            Integer variance = l.getCountedQty() == null ? null : l.getCountedQty() - l.getExpectedQty();
            if (l.getCountedQty() != null) {
                counted++;
                if (variance != 0) {
                    variances++;
                }
            }
            if (withLines) {
                Item i = byId.get(l.getItemId());
                rows.add(new StockTakeLineView(l.getItemId(), i == null ? null : i.getName(),
                        i == null ? null : i.getUnit(), l.getExpectedQty(), l.getCountedQty(), variance));
            }
        }
        rows.sort((a, b) -> String.valueOf(a.itemName()).compareToIgnoreCase(String.valueOf(b.itemName())));
        return new StockTakeView(t.getId(), t.getLocationId(), InventorySetupService.nameOf(locations,
                t.getLocationId()), t.getStatus(), t.getStartedOn(), t.getClosedAt(), t.getNotes(),
                t.getCreatedByName(), lines.size(), counted, variances, rows);
    }
}
