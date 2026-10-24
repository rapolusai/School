package com.akshara.bulkimport;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.akshara.audit.AuditService.Actor;
import com.akshara.bulkimport.SchoolImportService.ImportResult;
import com.akshara.reports.Xlsx;
import com.akshara.shared.ApiException;
import com.akshara.shared.ApiExceptionHandler;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/**
 * The school import (docs/api/phase-3-bulk-import.md): the template and sample workbooks, and the upload that checks
 * (dryRun=true, the default) or imports a workbook of students and staff. Needs students.manage and staff.manage;
 * letting the import create classes and sections also needs settings.manage.
 */
@RestController
@RequestMapping("/api/school-import")
public class SchoolImportController {

    static final String MANAGE = "hasAuthority('students.manage') and hasAuthority('staff.manage')";
    /** The same limit as every other upload (spring.servlet.multipart.max-file-size). */
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final SchoolImportService importer;
    private final ImportWorkbooks workbooks;

    SchoolImportController(SchoolImportService importer, ImportWorkbooks workbooks) {
        this.importer = importer;
        this.workbooks = workbooks;
    }

    @GetMapping("/template.xlsx")
    @PreAuthorize(MANAGE)
    public ResponseEntity<byte[]> template() {
        return xlsx(workbooks.template(), "school-import-template.xlsx");
    }

    @GetMapping("/sample.xlsx")
    @PreAuthorize(MANAGE)
    public ResponseEntity<byte[]> sample() {
        return xlsx(workbooks.sample(), "school-import-sample.xlsx");
    }

    /**
     * Checks or imports a workbook (multipart part "file"). A real import of a file with any problem writes nothing
     * and answers 400 "Check the file" with the full check in {@code result}.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(MANAGE)
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean createMissing) {
        if (createMissing && !has(Permissions.SETTINGS_MANAGE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed",
                    "Only someone who manages School setup can let the import add classes and sections.");
        }
        if (file.isEmpty()) {
            throw ApiException.badRequest("The file is empty.", "file");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "File too large", "Files can be at most 5 MB each.",
                    Map.of("file", "Files can be at most 5 MB each."));
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The file could not be read. Try again.", "file");
        }
        Actor actor = new Actor(CurrentUser.requireId(), CurrentUser.name().orElse(null));
        ImportResult result = importer.run(bytes, dryRun, createMissing, actor);
        if (!dryRun && !result.committed()) {
            int invalid = result.invalidRows() + result.sheetProblems();
            ResponseEntity<ProblemDetail> problem = ApiExceptionHandler.problem(HttpStatus.BAD_REQUEST,
                    "Check the file", (invalid == 1 ? "1 problem needs" : invalid + " problems need")
                            + " fixing. Nothing was imported.", Map.of());
            problem.getBody().setProperty("result", result);
            return problem;
        }
        return ResponseEntity.ok(result);
    }

    private static ResponseEntity<byte[]> xlsx(byte[] bytes, String name) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(Xlsx.CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bytes);
    }

    private static boolean has(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(permission::equals);
    }
}
