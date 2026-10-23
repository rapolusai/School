package com.akshara.hostel;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.hostel.HostelViews.BoarderDuesReport;
import com.akshara.hostel.HostelViews.OccupancyReport;
import com.akshara.hostel.HostelViews.OutingsReport;
import com.akshara.hostel.HostelViews.RollCallReport;

/**
 * The hostel's reports for the reports hub, as JSON and as CSV files (UTF-8 with a byte order mark, which Excel
 * opens). They need hostel.read.
 */
@RestController
@RequestMapping("/api/hostel/reports")
@PreAuthorize(HostelAuth.READ)
public class HostelReportController {

    private final HostelReportService reports;

    HostelReportController(HostelReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/occupancy")
    public OccupancyReport occupancy() {
        return reports.occupancy();
    }

    @GetMapping("/occupancy.csv")
    public ResponseEntity<byte[]> occupancyCsv() {
        return HostelCsv.download(reports.occupancyCsv(), "hostel-occupancy.csv");
    }

    @GetMapping("/roll-call")
    public RollCallReport rollCall(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return reports.rollCall(from, to);
    }

    @GetMapping("/roll-call.csv")
    public ResponseEntity<byte[]> rollCallCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return HostelCsv.download(reports.rollCallCsv(from, to), "hostel-roll-call.csv");
    }

    @GetMapping("/outings")
    public OutingsReport outings(@RequestParam(required = false) String month) {
        return reports.outings(month);
    }

    @GetMapping("/outings.csv")
    public ResponseEntity<byte[]> outingsCsv(@RequestParam(required = false) String month) {
        return HostelCsv.download(reports.outingsCsv(month), "hostel-outings.csv");
    }

    @GetMapping("/fee-dues")
    public BoarderDuesReport feeDues() {
        return reports.feeDues();
    }

    @GetMapping("/fee-dues.csv")
    public ResponseEntity<byte[]> feeDuesCsv() {
        return HostelCsv.download(reports.feeDuesCsv(), "hostel-fee-dues.csv");
    }
}
