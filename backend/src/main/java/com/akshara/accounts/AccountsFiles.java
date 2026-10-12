package com.akshara.accounts;

import org.springframework.stereotype.Component;

import com.akshara.files.FileAccessPolicy;
import com.akshara.files.StoredFileInfo;

/** Who may download an expense's bill: anyone who can read, manage or approve accounts. */
@Component
class AccountsFiles implements FileAccessPolicy {

    private final ExpenseService expenses;

    AccountsFiles(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @Override
    public String ownerModule() {
        return ExpenseService.MODULE;
    }

    @Override
    public boolean canRead(StoredFileInfo file) {
        return ExpenseService.BILL.equals(file.ownerType()) && ExpenseService.canRead(AccountsAccess.permissions())
                && expenses.exists(file.ownerId());
    }
}
