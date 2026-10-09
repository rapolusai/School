package com.akshara.audit;

import java.util.List;
import java.util.Map;
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

/** Records who did what in a school. Writes join the caller's transaction, so a rolled-back action leaves no entry. */
@Service
public class AuditService {

    private final AuditRepository events;
    private final JsonMapper json;

    public AuditService(AuditRepository events, JsonMapper json) {
        this.events = events;
        this.json = json;
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
        TenantContext.require();
        events.save(new AuditEvent(actor.id(), actor.name(), action, entityType,
                entityId == null ? null : entityId.toString(), json.writeValueAsString(details), clientIp()));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> latest(int limit) {
        return events.findAllByOrderByAtDesc(Limit.of(limit));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseDetails(String details) {
        return json.readValue(details, Map.class);
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
