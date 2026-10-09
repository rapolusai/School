package com.akshara.attendance;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.attendance.AttendanceReportService.ChildAttendance;
import com.akshara.shared.CurrentUser;

/** A parent's view of their own child's attendance. Any other student is 404. */
@RestController
public class ParentAttendanceController {

    private final AttendanceReportService reports;

    ParentAttendanceController(AttendanceReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/api/me/children/{studentId}/attendance")
    @PreAuthorize("hasAuthority('child.view')")
    public ChildAttendance child(@PathVariable UUID studentId, @RequestParam(required = false) String month) {
        return reports.child(CurrentUser.requireId(), studentId, AttendanceController.month(month));
    }
}
