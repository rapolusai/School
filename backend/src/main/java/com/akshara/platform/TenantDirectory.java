package com.akshara.platform;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.ApiException;

/** Looks up and registers schools. The tenant table is platform data and is not row-level secured. */
@Service
@Transactional(readOnly = true)
public class TenantDirectory {

    private final TenantRepository tenants;

    public TenantDirectory(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public Optional<TenantView> findByCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return tenants.findByCode(code.trim().toLowerCase(Locale.ROOT)).map(TenantView::of);
    }

    public Optional<TenantView> findById(UUID id) {
        return tenants.findById(id).map(TenantView::of);
    }

    public List<TenantSummary> summaries() {
        Map<UUID, Long> counts = userCounts();
        return tenants.findAllByOrderByCreatedAtDesc().stream()
                .map(t -> TenantSummary.of(t, counts.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    public Optional<TenantSummary> summary(UUID id) {
        return tenants.findById(id).map(t -> TenantSummary.of(t, userCounts().getOrDefault(id, 0L)));
    }

    /** Adds a school. Fails with 409 when the code is taken. */
    @Transactional
    public TenantView register(String code, String name, Board board, String city, Plan plan, TenantStatus status,
            Instant trialEndsAt) {
        if (tenants.existsByCode(code)) {
            throw codeTaken();
        }
        try {
            Tenant tenant = tenants.saveAndFlush(new Tenant(code, name, board, city, plan, status, trialEndsAt));
            return TenantView.of(tenant);
        } catch (DataIntegrityViolationException e) {
            throw codeTaken();
        }
    }

    /** Removes a school that has no data yet. Used to undo a sign-up that failed half way. */
    @Transactional
    public void removeEmpty(UUID id) {
        tenants.deleteById(id);
    }

    private Map<UUID, Long> userCounts() {
        return tenants.userCounts().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Number) row[1]).longValue()));
    }

    private static ApiException codeTaken() {
        return ApiException.conflict("That school code is already taken. Try another.", "schoolCode");
    }
}
