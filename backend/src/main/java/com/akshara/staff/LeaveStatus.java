package com.akshara.staff;

/** Where a leave request stands. Only PENDING and APPROVED requests hold days and block overlapping requests. */
public enum LeaveStatus {
    PENDING, APPROVED, REJECTED, CANCELLED;

    boolean holdsDays() {
        return this == PENDING || this == APPROVED;
    }
}
