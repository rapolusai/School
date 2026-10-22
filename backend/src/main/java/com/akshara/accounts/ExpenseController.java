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

import com.akshara.accounts.AccountsForms.ApproveForm;
import com.akshara.accounts.AccountsForms.ExpenseForm;
import com.akshara.accounts.AccountsForms.GenerateForm;
import com.akshara.accounts.AccountsForms.PaidForm;
import com.akshara.accounts.AccountsForms.RecurringForm;
import com.akshara.accounts.AccountsForms.RejectForm;
import com.akshara.accounts.AccountsViews.ApprovalQueue;
import com.akshara.accounts.AccountsViews.ExpenseDetail;
import com.akshara.accounts.AccountsViews.ExpenseList;
import com.akshara.accounts.AccountsViews.GenerateResult;
import com.akshara.accounts.AccountsViews.RecurringView;
import com.akshara.files.FileUploads;
import com.akshara.shared.CurrentUser;

/** Expenses: drafts, submission, the approval chain, payment, bills and recurring expenses. */
@RestController
@RequestMapping("/api/accounts")
@PreAuthorize(AccountsAccess.READ_OR_APPROVE)
public class ExpenseController {

    private final ExpenseService expenses;

    ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @GetMapping("/expenses")
    public ExpenseList list(@RequestParam(required = false) ExpenseStatus status,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return expenses.list(status, from, to);
    }

    @GetMapping("/expenses/queue")
    @PreAuthorize(AccountsAccess.APPROVE)
    public ApprovalQueue queue() {
        return expenses.queue(CurrentUser.requireId());
    }

    @GetMapping("/expenses/{id}")
    public ExpenseDetail get(@PathVariable UUID id) {
        return expenses.detail(id, CurrentUser.requireId(), AccountsAccess.canApprove());
    }

    @PostMapping("/expenses")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseDetail create(@Valid @RequestBody ExpenseForm form,
            @RequestParam(defaultValue = "false") boolean submit) {
        return expenses.create(form, submit, AccountsAccess.actor());
    }

    @PutMapping("/expenses/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    public ExpenseDetail update(@PathVariable UUID id, @Valid @RequestBody ExpenseForm form) {
        return expenses.update(id, form, AccountsAccess.actor());
    }

    @PostMapping("/expenses/{id}/submit")
    @PreAuthorize(AccountsAccess.MANAGE)
    public ExpenseDetail submit(@PathVariable UUID id) {
        return expenses.submit(id, AccountsAccess.actor());
    }

    @PostMapping("/expenses/{id}/approve")
    @PreAuthorize(AccountsAccess.APPROVE)
    public ExpenseDetail approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApproveForm form) {
        return expenses.approve(id, form == null ? new ApproveForm(null) : form, AccountsAccess.actor());
    }

    @PostMapping("/expenses/{id}/reject")
    @PreAuthorize(AccountsAccess.APPROVE)
    public ExpenseDetail reject(@PathVariable UUID id, @Valid @RequestBody RejectForm form) {
        return expenses.reject(id, form, AccountsAccess.actor());
    }

    @PostMapping("/expenses/{id}/paid")
    @PreAuthorize(AccountsAccess.MANAGE)
    public ExpenseDetail markPaid(@PathVariable UUID id, @Valid @RequestBody PaidForm form) {
        return expenses.markPaid(id, form, AccountsAccess.actor());
    }

    @DeleteMapping("/expenses/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        expenses.delete(id, AccountsAccess.actor());
    }

    @PostMapping(value = "/expenses/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(AccountsAccess.MANAGE)
    public ExpenseDetail addFile(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return expenses.addFile(id, FileUploads.read(file, "file"), AccountsAccess.actor());
    }

    @DeleteMapping("/expenses/{id}/attachments/{fileId}")
    @PreAuthorize(AccountsAccess.MANAGE)
    public ExpenseDetail removeFile(@PathVariable UUID id, @PathVariable UUID fileId) {
        return expenses.removeFile(id, fileId, AccountsAccess.actor());
    }

    // ------------------------------------------------------------------ recurring

    @GetMapping("/recurring")
    @PreAuthorize(AccountsAccess.READ)
    public List<RecurringView> recurring() {
        return expenses.recurring();
    }

    @PostMapping("/recurring")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringView createRecurring(@Valid @RequestBody RecurringForm form) {
        return expenses.createRecurring(form, AccountsAccess.actor());
    }

    @PutMapping("/recurring/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    public RecurringView updateRecurring(@PathVariable UUID id, @Valid @RequestBody RecurringForm form) {
        return expenses.updateRecurring(id, form, AccountsAccess.actor());
    }

    @DeleteMapping("/recurring/{id}")
    @PreAuthorize(AccountsAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRecurring(@PathVariable UUID id) {
        expenses.deleteRecurring(id, AccountsAccess.actor());
    }

    @PostMapping("/recurring/generate")
    @PreAuthorize(AccountsAccess.MANAGE)
    public GenerateResult generate(@Valid @RequestBody GenerateForm form) {
        return expenses.generate(form, AccountsAccess.actor());
    }
}
