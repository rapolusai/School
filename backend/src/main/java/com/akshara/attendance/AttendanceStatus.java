package com.akshara.attendance;

/** A student's attendance on one school day. Each has the one-letter mark used in registers and the CSV export. */
public enum AttendanceStatus {
    PRESENT("P"),
    ABSENT("A"),
    LATE("L"),
    HALF_DAY("H"),
    /** Excused leave, applied for in advance. */
    LEAVE("E");

    private final String mark;

    AttendanceStatus(String mark) {
        this.mark = mark;
    }

    public String mark() {
        return mark;
    }
}
