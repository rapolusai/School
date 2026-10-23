package com.akshara.inventory;

import java.time.LocalDate;
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

import com.akshara.inventory.InventoryForms.CountForm;
import com.akshara.inventory.InventoryForms.IssueForm;
import com.akshara.inventory.InventoryForms.ItemForm;
import com.akshara.inventory.InventoryForms.ReceiptForm;
import com.akshara.inventory.InventoryForms.StockTakeForm;
import com.akshara.inventory.InventoryForms.WriteOffForm;
import com.akshara.inventory.InventoryViews.ItemLedger;
import com.akshara.inventory.InventoryViews.ItemView;
import com.akshara.inventory.InventoryViews.MovementView;
import com.akshara.inventory.InventoryViews.StockTakeView;

/** Items, the stock ledger (receipts, issues, returns, write-offs) and stock takes. */
@RestController
@RequestMapping("/api/inventory")
@PreAuthorize(InventoryAccess.READ)
public class StockController {

    private final StockService stock;

    StockController(StockService stock) {
        this.stock = stock;
    }

    @GetMapping("/items")
    public List<ItemView> items() {
        return stock.items();
    }

    @GetMapping("/items/{id}")
    public ItemView item(@PathVariable UUID id) {
        return stock.item(id);
    }

    @GetMapping("/items/{id}/ledger")
    public ItemLedger ledger(@PathVariable UUID id) {
        return stock.ledger(id);
    }

    @PostMapping("/items")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ItemView create(@Valid @RequestBody ItemForm form) {
        return stock.create(form, InventoryAccess.actor());
    }

    @PutMapping("/items/{id}")
    @PreAuthorize(InventoryAccess.MANAGE)
    public ItemView update(@PathVariable UUID id, @Valid @RequestBody ItemForm form) {
        return stock.update(id, form, InventoryAccess.actor());
    }

    @DeleteMapping("/items/{id}")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        stock.delete(id, InventoryAccess.actor());
    }

    @GetMapping("/movements")
    public List<MovementView> movements(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(required = false) MovementKind kind) {
        InventoryReportService.period(from, to);
        return stock.movements(from, to, kind);
    }

    @PostMapping("/movements/receipt")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public MovementView receive(@Valid @RequestBody ReceiptForm form) {
        return stock.receive(form, InventoryAccess.actor());
    }

    @PostMapping("/movements/issue")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public MovementView issue(@Valid @RequestBody IssueForm form) {
        return stock.issue(form, InventoryAccess.actor());
    }

    @PostMapping("/movements/return")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public MovementView giveBack(@Valid @RequestBody IssueForm form) {
        return stock.giveBack(form, InventoryAccess.actor());
    }

    @PostMapping("/movements/write-off")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public MovementView writeOff(@Valid @RequestBody WriteOffForm form) {
        return stock.writeOff(form, InventoryAccess.actor());
    }

    @GetMapping("/stock-takes")
    public List<StockTakeView> stockTakes() {
        return stock.stockTakes();
    }

    @GetMapping("/stock-takes/{id}")
    public StockTakeView stockTake(@PathVariable UUID id) {
        return stock.stockTake(id);
    }

    @PostMapping("/stock-takes")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public StockTakeView startStockTake(@Valid @RequestBody(required = false) StockTakeForm form) {
        return stock.startStockTake(form == null ? new StockTakeForm(null, null) : form, InventoryAccess.actor());
    }

    @PostMapping("/stock-takes/{id}/count")
    @PreAuthorize(InventoryAccess.MANAGE)
    public StockTakeView count(@PathVariable UUID id, @Valid @RequestBody CountForm form) {
        return stock.count(id, form, InventoryAccess.actor());
    }

    @PostMapping("/stock-takes/{id}/close")
    @PreAuthorize(InventoryAccess.MANAGE)
    public StockTakeView close(@PathVariable UUID id) {
        return stock.closeStockTake(id, InventoryAccess.actor());
    }
}
