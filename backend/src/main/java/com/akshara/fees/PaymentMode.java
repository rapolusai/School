package com.akshara.fees;

/** How a fee was paid. ONLINE is used only for payments made through a payment gateway. */
public enum PaymentMode {
    CASH, CHEQUE, UPI, CARD, BANK_TRANSFER, ONLINE
}
