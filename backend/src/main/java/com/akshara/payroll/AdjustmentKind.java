package com.akshara.payroll;

/** A manual change to one person's pay in a draft run, always with a reason. */
public enum AdjustmentKind {
    /** Pay owed for earlier months (taxable; counts towards ESI wages, not PF wages). */
    ARREARS,
    /** A bonus (taxable; outside PF and ESI wages). */
    BONUS,
    /** A one-off deduction from net pay. */
    DEDUCTION,
    /** Adds (positive) or removes (negative) loss-of-pay days, in whole or half days. */
    LOP_CORRECTION;

    public boolean money() {
        return this != LOP_CORRECTION;
    }
}
