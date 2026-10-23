package com.akshara.hostel;

/**
 * The life of an outing or home-leave pass: REQUESTED (waiting for the warden), APPROVED (may leave), OUT (the warden
 * marked the boarder out), RETURNED (back; {@code late} says whether after the return time), REJECTED or CANCELLED
 * (by the parent before leaving).
 */
public enum HostelPassStatus {
    REQUESTED, APPROVED, OUT, RETURNED, REJECTED, CANCELLED;

    /** Still waiting for the warden, or approved and not yet used. */
    public boolean pending() {
        return this == REQUESTED || this == APPROVED;
    }

    /** The boarder is, or may be, away on it. */
    public boolean open() {
        return this == REQUESTED || this == APPROVED || this == OUT;
    }
}
