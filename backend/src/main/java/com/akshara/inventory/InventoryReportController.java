package com.akshara.inventory;

import java.time.LocalDate;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.inventory.InventoryCsv.Download;
import com.akshara.inventory.InventoryViews.AssetRegister;
import com.akshara.inventory.InventoryViews.AssetsByCustodian;
import com.akshara.inventory.InventoryViews.Consumption;
import com.akshara.inventory.InventoryViews.LowStock;
import com.akshara.inventory.InventoryViews.OrdersReport;
import com.akshara.inventory.InventoryViews.StockOnHand;

/** The inventory reports, each as JSON and as CSV. */
@RestController
@RequestMapping("/api/inventory/reports")
@PreAuthorize(InventoryAccess.READ)
public class InventoryReportController {

    private final InventoryReportService reports;

    InventoryReportController(InventoryReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/stock-on-hand")
    public StockOnHand stockOnHand() {
        return reports.stockOnHand();
    }

    @GetMapping("/stock-on-hand.csv")
    public ResponseEntity<byte[]> stockOnHandCsv() {
        return file(reports.stockOnHandCsv());
    }

    @GetMapping("/low-stock")
    public LowStock lowStock() {
        return reports.lowStock();
    }

    @GetMapping("/low-stock.csv")
    public ResponseEntity<byte[]> lowStockCsv() {
        return file(reports.lowStockCsv());
    }

    @GetMapping("/asset-register")
    public AssetRegister assetRegister() {
        return reports.assetRegister();
    }

    @GetMapping("/asset-register.csv")
    public ResponseEntity<byte[]> assetRegisterCsv() {
        return file(reports.assetRegisterCsv());
    }

    @GetMapping("/assets-by-custodian")
    public AssetsByCustodian assetsByCustodian() {
        return reports.assetsByCustodian();
    }

    @GetMapping("/assets-by-custodian.csv")
    public ResponseEntity<byte[]> assetsByCustodianCsv() {
        return file(reports.assetsByCustodianCsv());
    }

    @GetMapping("/purchase-orders")
    public OrdersReport orders(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.orders(from, to);
    }

    @GetMapping("/purchase-orders.csv")
    public ResponseEntity<byte[]> ordersCsv(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return file(reports.ordersCsv(from, to));
    }

    @GetMapping("/consumption")
    public Consumption consumption(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.consumption(from, to);
    }

    @GetMapping("/consumption.csv")
    public ResponseEntity<byte[]> consumptionCsv(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return file(reports.consumptionCsv(from, to));
    }

    static ResponseEntity<byte[]> file(Download download) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(download.content());
    }
}
