package com.akshara.accounts;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.akshara.accounts.AccountsForms.BankAccountForm;
import com.akshara.accounts.AccountsForms.ClearForm;
import com.akshara.accounts.AccountsForms.OpeningBalancesForm;
import com.akshara.accounts.AccountsForms.StatementImportForm;
import com.akshara.accounts.AccountsViews.BankAccountView;
import com.akshara.accounts.AccountsViews.Book;
import com.akshara.accounts.AccountsViews.CashPosition;
import com.akshara.accounts.AccountsViews.ClearResult;
import com.akshara.accounts.AccountsViews.OpeningBalancesView;
import com.akshara.accounts.AccountsViews.Reconciliation;
import com.akshara.accounts.AccountsViews.StatementImportResult;
import com.akshara.accounts.AccountsViews.StatementPreview;
import com.akshara.shared.ApiException;

/** Bank accounts, opening balances, the cash and bank books, cash position and bank reconciliation. */
@RestController
@RequestMapping("/api/accounts")
@PreAuthorize(AccountsAccess.READ)
public class BankController {

    static final long MAX_STATEMENT_BYTES = 2L * 1024 * 1024;

    private final BankService bank;

    BankController(BankService bank) {
        this.bank = bank;
    }

    @GetMapping("/bank-accounts")
    @PreAuthorize(AccountsAccess.READ_OR_APPROVE)
    public List<BankAccountView> accounts() {
        return bank.accounts();
    }

    @PostMapping("/bank-accounts")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public BankAccountView create(@Valid @RequestBody BankAccountForm form) {
        return bank.create(form, AccountsAccess.actor());
    }

    @PutMapping("/bank-accounts/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    public BankAccountView update(@PathVariable UUID id, @Valid @RequestBody BankAccountForm form) {
        return bank.update(id, form, AccountsAccess.actor());
    }

    @DeleteMapping("/bank-accounts/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        bank.delete(id, AccountsAccess.actor());
    }

    @GetMapping("/opening-balances/{financialYear}")
    public OpeningBalancesView openingBalances(@PathVariable String financialYear) {
        return bank.openingBalances(FinancialYear.parse(financialYear, "financialYear"));
    }

    @PutMapping("/opening-balances/{financialYear}")
    @PreAuthorize(AccountsAccess.MANAGE)
    public OpeningBalancesView saveOpeningBalances(@PathVariable String financialYear,
            @Valid @RequestBody OpeningBalancesForm form) {
        return bank.saveOpeningBalances(FinancialYear.parse(financialYear, "financialYear"), form,
                AccountsAccess.actor());
    }

    /** The cash book (no bankAccountId) or a bank book. */
    @GetMapping("/books")
    public Book book(@RequestParam(required = false) UUID bankAccountId, @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return bank.book(bankAccountId, from, to);
    }

    @GetMapping("/cash-position")
    @PreAuthorize(AccountsAccess.READ_OR_APPROVE)
    public CashPosition cashPosition() {
        return bank.cashPosition();
    }

    @GetMapping("/bank-accounts/{id}/reconciliation")
    public Reconciliation reconciliation(@PathVariable UUID id,
            @RequestParam(required = false) LocalDate statementDate) {
        return bank.reconciliation(id, statementDate == null ? FinancialYear.today() : statementDate);
    }

    @PostMapping("/bank-accounts/{id}/clear")
    @PreAuthorize(AccountsAccess.MANAGE)
    public ClearResult clear(@PathVariable UUID id, @Valid @RequestBody ClearForm form) {
        return bank.clear(id, form, AccountsAccess.actor());
    }

    @PostMapping(value = "/bank-accounts/{id}/statement/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(AccountsAccess.MANAGE)
    public StatementPreview preview(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose the statement CSV.", "file");
        }
        if (file.getSize() > MAX_STATEMENT_BYTES) {
            throw ApiException.badRequest("The statement is larger than 2 MB.", "file");
        }
        try {
            return bank.preview(id, file.getBytes());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not read the statement", e);
        }
    }

    @PostMapping("/bank-accounts/{id}/statement/import")
    @PreAuthorize(AccountsAccess.MANAGE)
    public StatementImportResult importStatement(@PathVariable UUID id,
            @Valid @RequestBody StatementImportForm form) {
        return bank.importStatement(id, form, AccountsAccess.actor());
    }
}
