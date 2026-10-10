package com.akshara.reports;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.admissions.ApplicationSource;
import com.akshara.attendance.AttendanceInsights.Absentees;
import com.akshara.attendance.AttendanceInsights.SectionReport;
import com.akshara.homework.HomeworkInsights.Completion;
import com.akshara.reports.ReportsService.Download;
import com.akshara.reports.ReportsService.FunnelReport;
import com.akshara.staff.LeaveInsights.LeaveTaken;

/**
 * The reports the hub adds, each as JSON for the screen and as an Excel workbook (.xlsx). Each report is guarded by
 * its module's read permission: attendance.read, homework.manage, admissions.read and staff.read. Teachers see only
 * their own sections' attendance and homework.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportsController {

    static final String ATTENDANCE = "hasAuthority('attendance.read')";
    static final String HOMEWORK = "hasAuthority('homework.manage')";
    static final String ADMISSIONS = "hasAuthority('admissions.read')";
    static final String STAFF = "hasAuthority('staff.read')";

    private final ReportsService reports;

    ReportsController(ReportsService reports) {
        this.reports = reports;
    }

    @GetMapping("/attendance/absentees")
    @PreAuthorize(ATTENDANCE)
    public Absentees absentees(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date, @RequestParam(required = false) UUID classId,
            @RequestParam(defaultValue = "false") boolean includeLeave) {
        return reports.absentees(date, classId, includeLeave);
    }

    @GetMapping("/attendance/absentees.xlsx")
    @PreAuthorize(ATTENDANCE)
    public ResponseEntity<byte[]> absenteesXlsx(@RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID classId, @RequestParam(defaultValue = "false") boolean includeLeave) {
        return xlsx(reports.absenteesXlsx(date, classId, includeLeave));
    }

    @GetMapping("/attendance/sections")
    @PreAuthorize(ATTENDANCE)
    public SectionReport sections(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID classId) {
        return reports.sections(from, to, classId);
    }

    @GetMapping("/attendance/sections.xlsx")
    @PreAuthorize(ATTENDANCE)
    public ResponseEntity<byte[]> sectionsXlsx(@RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID classId) {
        return xlsx(reports.sectionsXlsx(from, to, classId));
    }

    @GetMapping("/homework/completion")
    @PreAuthorize(HOMEWORK)
    public Completion homework(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) UUID subjectId) {
        return reports.homework(from, to, classId, subjectId);
    }

    @GetMapping("/homework/completion.xlsx")
    @PreAuthorize(HOMEWORK)
    public ResponseEntity<byte[]> homeworkXlsx(@RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) UUID subjectId) {
        return xlsx(reports.homeworkXlsx(from, to, classId, subjectId));
    }

    @GetMapping("/admissions/funnel")
    @PreAuthorize(ADMISSIONS)
    public FunnelReport funnel(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) ApplicationSource source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.funnel(yearId, classId, source, from, to);
    }

    @GetMapping("/admissions/funnel.xlsx")
    @PreAuthorize(ADMISSIONS)
    public ResponseEntity<byte[]> funnelXlsx(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) ApplicationSource source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return xlsx(reports.funnelXlsx(yearId, classId, source, from, to));
    }

    @GetMapping("/staff/leave")
    @PreAuthorize(STAFF)
    public LeaveTaken leave(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID departmentId, @RequestParam(required = false) UUID leaveTypeId) {
        return reports.leave(yearId, departmentId, leaveTypeId);
    }

    @GetMapping("/staff/leave.xlsx")
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> leaveXlsx(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID departmentId, @RequestParam(required = false) UUID leaveTypeId) {
        return xlsx(reports.leaveXlsx(yearId, departmentId, leaveTypeId));
    }

    static ResponseEntity<byte[]> xlsx(Download download) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(Xlsx.CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(download.bytes());
    }
}
