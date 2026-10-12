package com.akshara.accounts;

/** How an expense was paid or an income receipt was received. CASH moves the cash book; the others a bank account. */
public enum PayMode {
    CASH, BANK, UPI, CHEQUE;

    public boolean cash() {
        return this == CASH;
    }
}
