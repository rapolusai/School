package com.akshara.attendance;

/** Where a child's leave request stands. Only PENDING and APPROVED requests block an overlapping new one. */
public enum ChildLeaveStatus {
    PENDING, APPROVED, REJECTED, CANCELLED;

    boolean isOpen() {
        return this == PENDING || this == APPROVED;
    }
}
