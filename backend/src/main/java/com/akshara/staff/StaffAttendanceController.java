package com.akshara.staff;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.Permissions;
import com.akshara.staff.StaffAttendanceService.DaySheet;
import com.akshara.staff.StaffAttendanceService.MonthReport;
import com.akshara.staff.StaffAttendanceService.MyDay;
import com.akshara.staff.StaffAttendanceService.SaveResult;
import com.akshara.staff.StaffAttendanceService.TodaySummary;
import com.akshara.staff.StaffForms.CheckNote;
import com.akshara.staff.StaffForms.SaveDay;

/**
 * Staff attendance. Everyone with leave.request checks themselves in and out; staff_attendance.manage marks and
 * corrects a day; staff.read (or staff_attendance.manage) sees the daily sheet, the month report and today's numbers.
 */
@RestController
@RequestMapping("/api/staff-attendance")
public class StaffAttendanceController {

    static final String SELF = "hasAuthority('leave.request')";
    static final String READ = "hasAnyAuthority('staff.read', 'staff_attendance.manage')";
    static final String MANAGE = "hasAuthority('staff_attendance.manage')";

    private final StaffAttendanceService attendance;

    StaffAttendanceController(StaffAttendanceService attendance) {
        this.attendance = attendance;
    }

    @GetMapping("/me/today")
    @PreAuthorize(SELF)
    public MyDay myToday() {
        return attendance.myToday(StaffAuth.actor().id());
    }

    @PostMapping("/me/check-in")
    @PreAuthorize(SELF)
    public MyDay checkIn(@Valid @RequestBody(required = false) CheckNote request) {
        return attendance.checkIn(StaffAuth.actor(), request == null ? null : request.note(), Instant.now());
    }

    @PostMapping("/me/check-out")
    @PreAuthorize(SELF)
    public MyDay checkOut(@Valid @RequestBody(required = false) CheckNote request) {
        return attendance.checkOut(StaffAuth.actor(), request == null ? null : request.note(), Instant.now());
    }

    /** The daily sheet: everyone on the staff that day with their mark and times. */
    @GetMapping("/days/{date}")
    @PreAuthorize(READ)
    public DaySheet day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendance.day(date, StaffAuth.has(Permissions.STAFF_ATTENDANCE_MANAGE));
    }

    /** Marks or corrects the day for the listed people (audited). */
    @PutMapping("/days/{date}")
    @PreAuthorize(MANAGE)
    public SaveResult save(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody SaveDay request) {
        return attendance.saveDay(date, request.entries(), StaffAuth.actor());
    }

    /** The month report (YYYY-MM, this month by default), optionally for one department. */
    @GetMapping("/month")
    @PreAuthorize(READ)
    public MonthReport month(@RequestParam(required = false) String month,
            @RequestParam(required = false) UUID departmentId) {
        return attendance.month(StaffAuth.month(month), departmentId);
    }

    @GetMapping(value = "/month.csv", produces = "text/csv")
    @PreAuthorize(READ)
    public ResponseEntity<String> monthCsv(@RequestParam(required = false) String month,
            @RequestParam(required = false) UUID departmentId) {
        YearMonth m = StaffAuth.month(month);
        String csv = attendance.monthCsv(m, departmentId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("staff-attendance-" + m + ".csv").build().toString())
                .body(csv);
    }

    /** Today's numbers for the dashboard. */
    @GetMapping("/today")
    @PreAuthorize(READ)
    public TodaySummary today() {
        return attendance.todaySummary();
    }
}
