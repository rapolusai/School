package com.akshara.attendance;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.attendance.AttendanceReportService.MonthRegister;
import com.akshara.attendance.AttendanceReportService.StudentSummary;
import com.akshara.attendance.AttendanceReportService.TodaySummary;
import com.akshara.attendance.AttendanceService.EntryInput;
import com.akshara.attendance.AttendanceService.RegisterView;
import com.akshara.attendance.AttendanceService.SaveResult;
import com.akshara.attendance.AttendanceService.SectionsForDay;
import com.akshara.shared.ApiException;

/**
 * Day-wise attendance. attendance.read to look (teachers see only the sections they are class teacher of);
 * attendance.manage marks any section; attendance.mark alone marks only the caller's own sections.
 */
@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    static final String READ = "hasAuthority('attendance.read')";
    static final String MARK = "hasAnyAuthority('attendance.mark', 'attendance.manage')";

    private final AttendanceAccess access;
    private final AttendanceService attendance;
    private final AttendanceReportService reports;

    AttendanceController(AttendanceAccess access, AttendanceService attendance, AttendanceReportService reports) {
        this.access = access;
        this.attendance = attendance;
        this.reports = reports;
    }

    public record EntryRequest(@NotNull UUID studentId, @NotNull AttendanceStatus status) {
    }

    public record SaveRequest(
            @NotNull @Size(max = AttendanceService.MAX_ENTRIES) List<@NotNull @Valid EntryRequest> entries) {
    }

    /** The sections the caller can see, with whether each is marked on the day (today by default). */
    @GetMapping("/sections")
    @PreAuthorize(READ)
    public SectionsForDay sections(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendance.sectionsFor(access.current(), date != null ? date : AttendanceService.today());
    }

    @GetMapping("/registers/{sectionId}/{date}")
    @PreAuthorize(READ)
    public RegisterView register(@PathVariable UUID sectionId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendance.register(access.current(), sectionId, date);
    }

    /** Marks (or re-marks) every student of the section for the day. */
    @PutMapping("/registers/{sectionId}/{date}")
    @PreAuthorize(MARK)
    public SaveResult save(@PathVariable UUID sectionId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody SaveRequest request) {
        List<EntryInput> entries = request.entries().stream()
                .map(e -> new EntryInput(e.studentId(), e.status()))
                .toList();
        return attendance.save(access.current(), sectionId, date, entries, null, Instant.now());
    }

    /** The month register of a section: a column per day with P/A/L/H/E marks. Month is YYYY-MM. */
    @GetMapping("/sections/{sectionId}/month")
    @PreAuthorize(READ)
    public MonthRegister month(@PathVariable UUID sectionId, @RequestParam(required = false) String month) {
        return reports.month(access.current(), sectionId, month(month));
    }

    @GetMapping(value = "/sections/{sectionId}/month.csv", produces = "text/csv")
    @PreAuthorize(READ)
    public ResponseEntity<String> monthCsv(@PathVariable UUID sectionId,
            @RequestParam(required = false) String month) {
        YearMonth m = month(month);
        AttendanceScope scope = access.current();
        String csv = reports.monthCsv(scope, sectionId, m);
        String name = "attendance-" + attendance.section(sectionId).label().replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("(^-|-$)", "").toLowerCase(Locale.ROOT) + "-" + m + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
                        .toString())
                .body(csv);
    }

    /** One student's marks between two dates (the current year to date by default). */
    @GetMapping("/students/{studentId}/summary")
    @PreAuthorize(READ)
    public StudentSummary student(@PathVariable UUID studentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.student(access.current(), studentId, from, to);
    }

    /** Today's attendance across the sections the caller can see, for the dashboard. */
    @GetMapping("/today")
    @PreAuthorize(READ)
    public TodaySummary today() {
        return reports.today(access.current());
    }

    static YearMonth month(String value) {
        if (value == null || value.isBlank()) {
            return YearMonth.now(AttendanceService.INDIA);
        }
        try {
            YearMonth m = YearMonth.parse(value.strip());
            if (m.getYear() < 2000 || m.getYear() > 2100) {
                throw ApiException.badRequest("Pick a month between 2000 and 2100.", "month");
            }
            return m;
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Give the month as YYYY-MM, for example 2026-07.", "month");
        }
    }
}
