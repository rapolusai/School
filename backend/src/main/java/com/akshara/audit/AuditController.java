package com.akshara.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public List<AuditEventView> latest(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return audit.latest(limit).stream()
                .map(e -> new AuditEventView(e.getId(), e.getAt(), e.getActorName(), e.getAction(), e.getEntityType(),
                        e.getEntityId(), audit.parseDetails(e.getDetails())))
                .toList();
    }
}
