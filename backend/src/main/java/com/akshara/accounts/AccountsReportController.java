package com.akshara.accounts;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.accounts.AccountsCsv.Download;
import com.akshara.accounts.AccountsViews.Book;
import com.akshara.accounts.AccountsViews.BudgetsView;
import com.akshara.accounts.AccountsViews.DayBook;
import com.akshara.accounts.AccountsViews.GstSummary;
import com.akshara.accounts.AccountsViews.HeadWiseExpense;
import com.akshara.accounts.AccountsViews.IncomeVsExpense;
import com.akshara.accounts.AccountsViews.ReceiptsAndPayments;
import com.akshara.accounts.AccountsViews.VendorLedger;

/** The accounts reports (accounts.read), each as JSON and as a CSV for Excel. */
@RestController
@RequestMapping("/api/accounts/reports")
@PreAuthorize(AccountsAccess.READ)
public class AccountsReportController {

    private final AccountsReportService reports;

    AccountsReportController(AccountsReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/income-vs-expense")
    public IncomeVsExpense incomeVsExpense(@RequestParam(required = false) String financialYear) {
        return reports.incomeVsExpense(FinancialYear.parseOrCurrent(financialYear, "financialYear"));
    }

    @GetMapping("/income-vs-expense.csv")
    public ResponseEntity<byte[]> incomeVsExpenseCsv(@RequestParam(required = false) String financialYear) {
        return file(reports.incomeVsExpenseCsv(FinancialYear.parseOrCurrent(financialYear, "financialYear")));
    }

    @GetMapping("/receipts-and-payments")
    public ReceiptsAndPayments receiptsAndPayments(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.receiptsAndPayments(from, to);
    }

    @GetMapping("/receipts-and-payments.csv")
    public ResponseEntity<byte[]> receiptsAndPaymentsCsv(@RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return file(reports.receiptsAndPaymentsCsv(from, to));
    }

    @GetMapping("/budget-vs-actual")
    public BudgetsView budgetVsActual(@RequestParam(required = false) String financialYear) {
        return reports.budgetVsActual(FinancialYear.parseOrCurrent(financialYear, "financialYear"));
    }

    @GetMapping("/budget-vs-actual.csv")
    public ResponseEntity<byte[]> budgetVsActualCsv(@RequestParam(required = false) String financialYear) {
        return file(reports.budgetVsActualCsv(FinancialYear.parseOrCurrent(financialYear, "financialYear")));
    }

    @GetMapping("/head-wise")
    public HeadWiseExpense headWise(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.headWise(from, to);
    }

    @GetMapping("/head-wise.csv")
    public ResponseEntity<byte[]> headWiseCsv(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return file(reports.headWiseCsv(from, to));
    }

    @GetMapping("/vendor-ledger")
    public VendorLedger vendorLedger(@RequestParam UUID vendorId, @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return reports.vendorLedger(vendorId, from, to);
    }

    @GetMapping("/vendor-ledger.csv")
    public ResponseEntity<byte[]> vendorLedgerCsv(@RequestParam UUID vendorId, @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return file(reports.vendorLedgerCsv(vendorId, from, to));
    }

    /** The cash book (no bankAccountId) or a bank book. */
    @GetMapping("/books")
    public Book book(@RequestParam(required = false) UUID bankAccountId, @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return reports.book(bankAccountId, from, to);
    }

    @GetMapping("/books.csv")
    public ResponseEntity<byte[]> bookCsv(@RequestParam(required = false) UUID bankAccountId,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return file(reports.bookCsv(bankAccountId, from, to));
    }

    @GetMapping("/gst-summary")
    public GstSummary gstSummary(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.gstSummary(from, to);
    }

    @GetMapping("/gst-summary.csv")
    public ResponseEntity<byte[]> gstSummaryCsv(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return file(reports.gstSummaryCsv(from, to));
    }

    @GetMapping("/day-book")
    public DayBook dayBook(@RequestParam LocalDate date) {
        return reports.dayBook(date);
    }

    @GetMapping("/day-book.csv")
    public ResponseEntity<byte[]> dayBookCsv(@RequestParam LocalDate date) {
        return file(reports.dayBookCsv(date));
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
