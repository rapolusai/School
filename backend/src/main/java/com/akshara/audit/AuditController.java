package com.akshara.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

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

/**
 * The school's audit trail, newest first. Without filters it is the latest entries; with them, one action, one person
 * or a range of days. "Load more" passes the last entry's {@code at} and {@code id} as {@code before} and
 * {@code beforeId}. The export is a CSV file of up to {@link AuditService#MAX_EXPORT_ROWS} entries and is itself
 * audited.
 */
@RestController
@RequestMapping("/api/audit-events")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    public record AuditEventView(UUID id, Instant at, String actorName, String action, String entityType,
            String entityId, Map<String, Object> details) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit.read')")
    public List<AuditEventView> latest(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) UUID beforeId) {
        AuditQuery query = new AuditQuery(action, actorId, from, to, null, before, beforeId);
        return audit.search(query, limit).stream()
                .map(e -> new AuditEventView(e.getId(), e.getAt(), e.getActorName(), e.getAction(), e.getEntityType(),
                        e.getEntityId(), audit.parseDetails(e.getDetails())))
                .toList();
    }

    /** The actions and people the school's trail holds, for the audit page's filters. */
    @GetMapping("/facets")
    @PreAuthorize("hasAuthority('audit.read')")
    public AuditService.Facets facets() {
        return audit.facets();
    }

    /** The trail as a CSV file for Excel, with the same filters (the export is recorded in the trail). */
    @GetMapping("/export.csv")
    @PreAuthorize("hasAuthority('audit.read')")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = audit.exportCsv(new AuditQuery(action, actorId, from, to, null, null, null));
        return csvFile(csv, "audit-trail.csv");
    }

    /** A CSV download: UTF-8 with a byte order mark so Excel shows Indian names and the rupee sign. */
    public static ResponseEntity<byte[]> csvFile(String csv, String fileName) {
        byte[] body = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
