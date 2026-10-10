package com.akshara.billing;

/** How a school paid an invoice. No gateway: the Super Admin records what reached Akshara's bank account. */
public enum PaymentMode {
    BANK_TRANSFER, UPI, CHEQUE, CARD
}
