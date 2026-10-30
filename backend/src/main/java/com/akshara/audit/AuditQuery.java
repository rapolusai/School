package com.akshara.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.akshara.shared.ApiException;

/**
 * Filters of an audit trail, newest first: one action, one person, a range of days in India ({@code from} to
 * {@code to}, both inclusive), one school (the platform trail only), and a cursor: entries older than the
 * {@code before} / {@code beforeId} of the last entry already shown. Every field is optional.
 */
public record AuditQuery(String action, UUID actorId, LocalDate from, LocalDate to, UUID tenantId, Instant before,
        UUID beforeId) {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("at"), Sort.Order.desc("id"));

    /** The longest range one query may ask for. */
    static final int MAX_RANGE_DAYS = 366 * 3;

    public AuditQuery {
        action = action == null || action.isBlank() ? null : action.trim();
        if (action != null && action.length() > 80) {
            throw ApiException.badRequest("Choose an action from the list.", "action");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("The first day must not be after the last day.", "from");
        }
        if (from != null && to != null && from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw ApiException.badRequest("Choose at most three years at a time.", "to");
        }
        if (beforeId != null && before == null) {
            throw ApiException.badRequest("beforeId needs before.", "before");
        }
    }

    static AuditQuery none() {
        return new AuditQuery(null, null, null, null, null, null, null);
    }

    /** The filters as a JPA specification on an entity with at, id, action, actorId (and tenantId). */
    <T> Specification<T> specification(boolean withTenant) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (action != null) {
                where.add(cb.equal(root.get("action"), action));
            }
            if (actorId != null) {
                where.add(cb.equal(root.get("actorId"), actorId));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.<Instant>get("at"), from.atStartOfDay(INDIA).toInstant()));
            }
            if (to != null) {
                where.add(cb.lessThan(root.<Instant>get("at"), to.plusDays(1).atStartOfDay(INDIA).toInstant()));
            }
            if (withTenant && tenantId != null) {
                where.add(cb.equal(root.get("tenantId"), tenantId));
            }
            if (before != null) {
                if (beforeId == null) {
                    where.add(cb.lessThan(root.<Instant>get("at"), before));
                } else {
                    where.add(cb.or(cb.lessThan(root.<Instant>get("at"), before),
                            cb.and(cb.equal(root.get("at"), before), cb.lessThan(root.<UUID>get("id"), beforeId))));
                }
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** The filters for the audit trail itself (an export records what was exported). */
    Map<String, Object> describe() {
        Map<String, Object> d = new LinkedHashMap<>();
        if (action != null) {
            d.put("action", action);
        }
        if (actorId != null) {
            d.put("actorId", actorId.toString());
        }
        if (from != null) {
            d.put("from", from.toString());
        }
        if (to != null) {
            d.put("to", to.toString());
        }
        if (tenantId != null) {
            d.put("tenantId", tenantId.toString());
        }
        return d;
    }
}
