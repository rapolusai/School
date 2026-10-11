package com.akshara.transport;

/** A document is VALID, EXPIRING within 30 days, EXPIRED, or MISSING (no expiry date on record). */
public enum DocumentState {
    VALID, EXPIRING, EXPIRED, MISSING
}
