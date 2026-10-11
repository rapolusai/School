package com.akshara.library;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.library.LibraryViews.IssuedReport;
import com.akshara.library.LibraryViews.LibrarySummary;
import com.akshara.library.LibraryViews.MemberHistory;
import com.akshara.library.LibraryViews.OverdueReport;
import com.akshara.library.LibraryViews.PopularReport;

/**
 * The library's reports for the reports hub, as JSON and as CSV files (UTF-8 with a byte order mark, which Excel
 * opens), and the librarian's dashboard figures. They name students and staff, so they need library.manage.
 */
@RestController
@RequestMapping("/api/library")
@PreAuthorize(LibraryAuth.MANAGE)
public class LibraryReportController {

    private final LibraryReportService reports;

    LibraryReportController(LibraryReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/summary")
    public LibrarySummary summary() {
        return reports.summary(LoanMath.today());
    }

    @GetMapping("/reports/overdue")
    public OverdueReport overdue(@RequestParam(required = false) MemberType memberType,
            @RequestParam(required = false) UUID classId) {
        return reports.overdue(memberType, classId, LoanMath.today());
    }

    @GetMapping("/reports/overdue.csv")
    public ResponseEntity<byte[]> overdueCsv(@RequestParam(required = false) MemberType memberType,
            @RequestParam(required = false) UUID classId) {
        LocalDate today = LoanMath.today();
        return LibraryCsv.download(reports.overdueCsv(memberType, classId, today), "library-overdue-" + today
                + ".csv");
    }

    @GetMapping("/reports/issued")
    public IssuedReport issued(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return reports.issued(date, LoanMath.today());
    }

    @GetMapping("/reports/issued.csv")
    public ResponseEntity<byte[]> issuedCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        LocalDate today = LoanMath.today();
        return LibraryCsv.download(reports.issuedCsv(date, today), "library-issued-" + (date == null ? today : date)
                + ".csv");
    }

    @GetMapping("/reports/popular")
    public PopularReport popular(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return reports.popular(from, to, LoanMath.today());
    }

    @GetMapping("/reports/popular.csv")
    public ResponseEntity<byte[]> popularCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return LibraryCsv.download(reports.popularCsv(from, to, LoanMath.today()), "library-most-borrowed.csv");
    }

    @GetMapping("/reports/member-history")
    public MemberHistory memberHistory(@RequestParam MemberType memberType, @RequestParam UUID memberId) {
        return reports.memberHistory(new MemberRef(memberType, memberId), LoanMath.today());
    }

    @GetMapping("/reports/member-history.csv")
    public ResponseEntity<byte[]> memberHistoryCsv(@RequestParam MemberType memberType,
            @RequestParam UUID memberId) {
        return LibraryCsv.download(reports.memberHistoryCsv(new MemberRef(memberType, memberId), LoanMath.today()),
                "library-member-history.csv");
    }
}
