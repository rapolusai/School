package com.akshara.staff;

/**
 * A staff member's day. ON_LEAVE is set only by approved leave (an approved half-day leave makes the day HALF_DAY).
 * Report letters: P present, A absent, H half day, L on leave.
 */
public enum StaffAttendanceStatus {
    PRESENT("P"), ABSENT("A"), HALF_DAY("H"), ON_LEAVE("L");

    private final String mark;

    StaffAttendanceStatus(String mark) {
        this.mark = mark;
    }

    public String mark() {
        return mark;
    }
}
