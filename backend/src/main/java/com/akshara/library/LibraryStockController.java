package com.akshara.library;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.library.LibraryForms.ScanForm;
import com.akshara.library.LibraryForms.StockCheckForm;
import com.akshara.library.LibraryViews.ScanResult;
import com.akshara.library.LibraryViews.StockCheckDetail;
import com.akshara.library.LibraryViews.StockCheckRow;

/** Stock verification: start a check, scan the shelves, close it and list the copies not seen. library.manage. */
@RestController
@RequestMapping("/api/library/stock-checks")
@PreAuthorize(LibraryAuth.MANAGE)
public class LibraryStockController {

    private final StockCheckService checks;
    private final LibraryReportService reports;

    LibraryStockController(StockCheckService checks, LibraryReportService reports) {
        this.checks = checks;
        this.reports = reports;
    }

    @GetMapping
    public List<StockCheckRow> list() {
        return checks.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StockCheckRow start(@Valid @RequestBody StockCheckForm form) {
        return checks.start(form, LibraryAuth.actor(), Instant.now());
    }

    @GetMapping("/{id}")
    public StockCheckDetail detail(@PathVariable UUID id) {
        return checks.detail(id);
    }

    @PostMapping("/{id}/scans")
    public ScanResult scan(@PathVariable UUID id, @Valid @RequestBody ScanForm form) {
        return checks.scan(id, form, LibraryAuth.actor(), Instant.now());
    }

    @PostMapping("/{id}/close")
    public StockCheckDetail close(@PathVariable UUID id) {
        return checks.close(id, LibraryAuth.actor(), Instant.now());
    }

    /** The copies not seen, as a CSV file (UTF-8 with a byte order mark, which Excel opens). */
    @GetMapping("/{id}/missing.csv")
    public ResponseEntity<byte[]> missingCsv(@PathVariable UUID id) {
        return LibraryCsv.download(reports.missingCsv(id), "library-missing-copies.csv");
    }
}
