package com.akshara.billing;

/**
 * How a school paid an invoice. The Super Admin records what reached Akshara's bank account by hand (bank transfer,
 * UPI, cheque, card); ONLINE payments are recorded only by Akshara's payment gateway, when a School Admin pays online.
 */
public enum PaymentMode {
    BANK_TRANSFER, UPI, CHEQUE, CARD, ONLINE
}
