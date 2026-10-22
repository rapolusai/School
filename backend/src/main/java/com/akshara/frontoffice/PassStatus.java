package com.akshara.frontoffice;

/**
 * An early pickup pass: REQUESTED until the office decides; APPROVED (OTP sent to the primary guardian) until the gate
 * releases the child (RELEASED); or REJECTED by the office, CANCELLED by the parent, EXPIRED (OTP older than 4 hours)
 * or LOCKED (5 wrong OTPs at the gate).
 */
public enum PassStatus {
    REQUESTED, APPROVED, RELEASED, REJECTED, CANCELLED, EXPIRED, LOCKED;

    /** Still waiting for the office or the gate. */
    public boolean open() {
        return this == REQUESTED || this == APPROVED;
    }
}
