package com.akshara.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.audit.AuditController;
import com.akshara.audit.AuditQuery;
import com.akshara.audit.PlatformAuditService;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantSummary;

/**
 * The Super Admin's Activity page: the platform trail across schools (docs/api/phase-3-subscriptions.md, section 6),
 * newest first, filtered by action, school, person or days, with "load more" by the last entry's {@code at} and
 * {@code id}, and as a CSV file (the export is itself recorded). Only platform admins reach it.
 */
@RestController
@RequestMapping("/api/platform/audit-events")
@PreAuthorize("hasAuthority('platform.admin')")
public class PlatformAuditController {

    private final PlatformAuditService audit;
    private final TenantDirectory tenants;

    PlatformAuditController(PlatformAuditService audit, TenantDirectory tenants) {
        this.audit = audit;
        this.tenants = tenants;
    }

    /** {@code platformAdmin}: a Super Admin did it (as opposed to the school, the renewal job or the gateway). */
    public record PlatformAuditEventView(UUID id, Instant at, String actorName, boolean platformAdmin, String action,
            UUID schoolId, String schoolName, String schoolCode, String entityType, String entityId,
            Map<String, Object> details, String ip) {
    }

    public record School(UUID id, String name, String code) {
    }

    public record Facets(List<String> actions, List<School> schools) {
    }

    @GetMapping
    public List<PlatformAuditEventView> list(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) UUID beforeId) {
        Map<UUID, TenantSummary> schools = schools();
        return audit.search(new AuditQuery(action, actorId, from, to, schoolId, before, beforeId), limit).stream()
                .map(e -> {
                    TenantSummary school = e.getTenantId() == null ? null : schools.get(e.getTenantId());
                    return new PlatformAuditEventView(e.getId(), e.getAt(), e.getActorName(), e.isPlatformAdmin(),
                            e.getAction(), e.getTenantId(), school == null ? null : school.name(),
                            school == null ? null : school.code(), e.getEntityType(), e.getEntityId(),
                            audit.parseDetails(e.getDetails()), e.getIp());
                })
                .toList();
    }

    @GetMapping("/facets")
    public Facets facets() {
        return new Facets(audit.actions(), tenants.summaries().stream()
                .map(s -> new School(s.id(), s.name(), s.code())).toList());
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Map<UUID, String> names = schools().values().stream()
                .collect(Collectors.toMap(TenantSummary::id, s -> s.name() + " (" + s.code() + ")"));
        String csv = audit.exportCsv(new AuditQuery(action, actorId, from, to, schoolId, null, null), names);
        return AuditController.csvFile(csv, "platform-activity.csv");
    }

    private Map<UUID, TenantSummary> schools() {
        return tenants.summaries().stream().collect(Collectors.toMap(TenantSummary::id, s -> s));
    }
}
