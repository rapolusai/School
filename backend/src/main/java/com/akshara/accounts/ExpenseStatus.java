package com.akshara.accounts;

/** DRAFT (editable) → SUBMITTED (waiting for an approver) → APPROVED → PAID (locked); or REJECTED with a reason. */
public enum ExpenseStatus {
    DRAFT, SUBMITTED, APPROVED, REJECTED, PAID;

    /** A paid expense never changes. */
    public boolean locked() {
        return this == PAID;
    }
}
