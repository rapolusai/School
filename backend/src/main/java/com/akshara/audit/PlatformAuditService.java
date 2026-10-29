package com.akshara.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;

import tools.jackson.databind.json.JsonMapper;

/**
 * The Super Admin's own trail across schools ({@code audit.platform_event}). {@link AuditService} copies school
 * entries here (see {@link AuditService#mirrored(String)}); this service records what happens outside any school
 * (Super Admin sign-ins and sign-outs) and reads the trail for the platform console.
 */
@Service
public class PlatformAuditService {

    private final PlatformAuditRepository events;
    private final JsonMapper json;

    public PlatformAuditService(PlatformAuditRepository events, JsonMapper json) {
        this.events = events;
        this.json = json;
    }

    /** Records a platform event in the caller's transaction (or a new one when there is none). */
    @Transactional
    public void record(Actor actor, String action, UUID tenantId, String entityType, Object entityId,
            Map<String, ?> details) {
        save(actor, action, tenantId, entityType, entityId, details);
    }

    /**
     * Records a platform event in a transaction of its own, so it stays even when the caller's transaction rolls back
     * (a failed sign-in).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNow(Actor actor, String action, UUID tenantId, String entityType, Object entityId,
            Map<String, ?> details) {
        save(actor, action, tenantId, entityType, entityId, details);
    }

    private void save(Actor actor, String action, UUID tenantId, String entityType, Object entityId,
            Map<String, ?> details) {
        events.save(new PlatformAuditEvent(actor.id(), actor.name(), true, action, tenantId, entityType,
                entityId == null ? null : entityId.toString(), json.writeValueAsString(details),
                AuditService.clientIp()));
    }

    /** Entries matching the query, newest first, at most {@code limit}. */
    @Transactional(readOnly = true)
    public List<PlatformAuditEvent> search(AuditQuery query, int limit) {
        return events.findBy(query.<PlatformAuditEvent>specification(true),
                q -> q.sortBy(AuditQuery.NEWEST_FIRST).limit(limit).all());
    }

    @Transactional(readOnly = true)
    public List<String> actions() {
        return events.distinctActions();
    }

    /**
     * Entries matching the query as CSV (newest first, at most {@link AuditService#MAX_EXPORT_ROWS}), times in India.
     * {@code schoolNames} turns school ids into names. The export is itself recorded ({@code platform.audit_exported}).
     */
    @Transactional
    public String exportCsv(AuditQuery query, Map<UUID, String> schoolNames) {
        List<PlatformAuditEvent> rows = search(query, AuditService.MAX_EXPORT_ROWS);
        AuditCsv csv = new AuditCsv("Time (India)", "Person", "Super Admin", "Action", "School", "Entity", "Entity id",
                "Details", "IP");
        for (PlatformAuditEvent e : rows) {
            csv.row(AuditService.CSV_TIME.format(e.getAt()), e.getActorName(), e.isPlatformAdmin() ? "yes" : "no",
                    e.getAction(), e.getTenantId() == null ? null : schoolNames.getOrDefault(e.getTenantId(),
                            e.getTenantId().toString()),
                    e.getEntityType(), e.getEntityId(), e.getDetails(), e.getIp());
        }
        Map<String, Object> details = new LinkedHashMap<>(query.describe());
        details.put("rows", rows.size());
        save(new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(null)), "platform.audit_exported",
                null, "audit", null, details);
        return csv.toString();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseDetails(String details) {
        return json.readValue(details, Map.class);
    }
}
