package com.akshara.payroll;

/** A month's payroll: a DRAFT is recalculated freely; APPROVED and PAID runs are locked and never change. */
public enum RunStatus {
    DRAFT, APPROVED, PAID;

    public boolean locked() {
        return this != DRAFT;
    }
}
