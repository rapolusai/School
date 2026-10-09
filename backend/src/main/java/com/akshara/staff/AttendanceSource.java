package com.akshara.staff;

/**
 * Where a staff attendance day came from: SELF check-in on the web app, the ADMIN daily sheet, or approved LEAVE.
 * A biometric or RFID device would be one more source (see docs/api/phase-1-staff.md).
 */
public enum AttendanceSource {
    SELF, ADMIN, LEAVE
}
