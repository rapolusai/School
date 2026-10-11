package com.akshara.library;

/**
 * WAITING in the title's queue; READY when a copy is kept for the member on the hold shelf; then FULFILLED when it is
 * issued to them, EXPIRED when they did not collect it in time, or CANCELLED.
 */
public enum ReservationStatus {
    WAITING, READY, FULFILLED, CANCELLED, EXPIRED;

    boolean isActive() {
        return this == WAITING || this == READY;
    }
}
