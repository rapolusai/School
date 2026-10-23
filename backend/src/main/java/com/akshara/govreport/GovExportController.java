package com.akshara.govreport;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.govreport.GovReportTypes.ExportKind;
import com.akshara.govreport.GovReportViews.ExportPreview;
import com.akshara.shared.ApiException;

/**
 * The UDISE+, CBSE OASIS and CBSE registration exports: a preview and the files. Every download is audited. Nothing
 * is sent to a government system; the school uploads the file after checking it against the portal's template.
 * govreport.read.
 */
@RestController
@RequestMapping("/api/govreport/exports")
@PreAuthorize(GovReportAuth.READ)
public class GovExportController {

    private final GovExportService exports;

    GovExportController(GovExportService exports) {
        this.exports = exports;
    }

    // ------------------------------------------------------------------ UDISE+

    @GetMapping("/udise/preview")
    public ExportPreview udisePreview(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) LocalDate asOf) {
        return exports.udise(yearId, asOf);
    }

    @GetMapping("/udise.xlsx")
    public ResponseEntity<byte[]> udiseWorkbook(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) LocalDate asOf) {
        ExportPreview preview = exports.udise(yearId, asOf);
        exports.recordExport(ExportKind.UDISE_XLSX, preview, null, GovReportAuth.actor());
        return GovCsv.xlsx(exports.workbook(preview), "udise-plus-" + preview.asOf() + ".xlsx");
    }

    @GetMapping("/udise/{table}.csv")
    public ResponseEntity<byte[]> udiseTable(@PathVariable String table,
            @RequestParam(required = false) UUID yearId, @RequestParam(required = false) LocalDate asOf) {
        if (!GovExportService.UDISE_TABLES.contains(table)) {
            throw ApiException.notFound("Export table");
        }
        ExportPreview preview = exports.udise(yearId, asOf);
        exports.recordExport(ExportKind.UDISE_CSV, preview, table, GovReportAuth.actor());
        return GovCsv.download(exports.csv(exports.table(preview, table)),
                "udise-plus-" + table + "-" + preview.asOf() + ".csv");
    }

    // ------------------------------------------------------------------ CBSE OASIS

    @GetMapping("/oasis/preview")
    public ExportPreview oasisPreview(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) LocalDate asOf) {
        return exports.oasis(yearId, asOf);
    }

    @GetMapping("/oasis.xlsx")
    public ResponseEntity<byte[]> oasisWorkbook(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) LocalDate asOf) {
        ExportPreview preview = exports.oasis(yearId, asOf);
        exports.recordExport(ExportKind.OASIS_XLSX, preview, null, GovReportAuth.actor());
        return GovCsv.xlsx(exports.workbook(preview), "cbse-oasis-" + preview.asOf() + ".xlsx");
    }

    // ------------------------------------------------------------------ CBSE registration (classes 9 and 11)

    @GetMapping("/cbse-registration/classes")
    public List<ClassView> registrationClasses() {
        return exports.registrationClasses();
    }

    @GetMapping("/cbse-registration/preview")
    public ExportPreview registrationPreview(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) LocalDate asOf) {
        return exports.cbseRegistration(yearId, classId, asOf);
    }

    @GetMapping("/cbse-registration.xlsx")
    public ResponseEntity<byte[]> registrationWorkbook(@RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) LocalDate asOf) {
        ExportPreview preview = exports.cbseRegistration(yearId, classId, asOf);
        exports.recordExport(ExportKind.CBSE_REGISTRATION_XLSX, preview, null, GovReportAuth.actor());
        return GovCsv.xlsx(exports.workbook(preview), "cbse-registration-" + preview.asOf() + ".xlsx");
    }
}
