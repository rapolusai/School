package com.akshara.audit;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * Records who did what in a school. Writes join the caller's transaction, so a rolled-back action leaves no entry.
 *
 * <p>Some entries are also copied to the Super Admin's own trail across schools ({@link PlatformAuditService}): every
 * entry a Super Admin causes (working as the school), and every subscription, invoice, billing and school status
 * change whoever made it (a School Admin, the renewal job, the payment gateway). See {@link #mirrored(String)}.
 */
@Service
public class AuditService {

    /** At most this many rows in one CSV export. */
    public static final int MAX_EXPORT_ROWS = 10_000;

    /** Actions copied to the platform trail whoever made them: billing and the school's status. */
    private static final List<String> PLATFORM_PREFIXES = List.of("subscription.", "invoice.", "invoice_order.",
            "billing_details.");

    private static final Set<String> PLATFORM_ACTIONS = Set.of("school.created", "school.status_changed",
            "school.suspended", "school.reactivated");

    static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(AuditQuery.INDIA);

    private final AuditRepository events;
    private final JsonMapper json;
    private final PlatformAuditRepository platformEvents;

    public AuditService(AuditRepository events, JsonMapper json, PlatformAuditRepository platformEvents) {
        this.events = events;
        this.json = json;
        this.platformEvents = platformEvents;
    }

    public record Actor(UUID id, String name) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId, Map<String, ?> details) {
        record(new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(null)), action, entityType,
                entityId, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, String action, String entityType, Object entityId, Map<String, ?> details) {
        UUID tenantId = TenantContext.require();
        String id = entityId == null ? null : entityId.toString();
        String text = json.writeValueAsString(details);
        String ip = clientIp();
        events.save(new AuditEvent(actor.id(), actor.name(), action, entityType, id, text, ip));
        boolean platformAdmin = CurrentUser.isPlatformAdmin();
        if (platformAdmin || mirrored(action)) {
            platformEvents.save(new PlatformAuditEvent(actor.id(), actor.name(), platformAdmin, action, tenantId,
                    entityType, id, text, ip));
        }
    }

    /** Whether an action is copied to the platform trail whoever made it. */
    static boolean mirrored(String action) {
        return PLATFORM_ACTIONS.contains(action) || PLATFORM_PREFIXES.stream().anyMatch(action::startsWith);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> latest(int limit) {
        return events.findAllByOrderByAtDesc(Limit.of(limit));
    }

    /** The current school's entries matching the query, newest first, at most {@code limit}. */
    @Transactional(readOnly = true)
    public List<AuditEvent> search(AuditQuery query, int limit) {
        TenantContext.require();
        return events.findBy(query.<AuditEvent>specification(false),
                q -> q.sortBy(AuditQuery.NEWEST_FIRST).limit(limit).all());
    }

    /** What the school's audit page can filter by: the actions and the people in its trail. */
    @Transactional(readOnly = true)
    public Facets facets() {
        TenantContext.require();
        return new Facets(events.distinctActions(), events.distinctActors().stream()
                .map(row -> new Person((UUID) row[0], (String) row[1])).toList());
    }

    public record Person(UUID id, String name) {
    }

    public record Facets(List<String> actions, List<Person> people) {
    }

    /**
     * The current school's entries matching the query as CSV (newest first, at most {@link #MAX_EXPORT_ROWS}), with
     * times in India. The export itself is audited ({@code audit.exported}, with the filters and the row count).
     */
    @Transactional
    public String exportCsv(AuditQuery query) {
        TenantContext.require();
        List<AuditEvent> rows = search(query, MAX_EXPORT_ROWS);
        AuditCsv csv = new AuditCsv("Time (India)", "Person", "Action", "Entity", "Entity id", "Details");
        for (AuditEvent e : rows) {
            csv.row(CSV_TIME.format(e.getAt()), e.getActorName(), e.getAction(), e.getEntityType(), e.getEntityId(),
                    e.getDetails());
        }
        Map<String, Object> details = new LinkedHashMap<>(query.describe());
        details.put("rows", rows.size());
        record("audit.exported", "audit", null, details);
        return csv.toString();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseDetails(String details) {
        return json.readValue(details, Map.class);
    }

    static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
