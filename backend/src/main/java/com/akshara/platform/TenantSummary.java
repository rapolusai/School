package com.akshara.platform;

import java.time.Instant;
import java.util.UUID;

/** A school as the Super Admin console lists it. */
public record TenantSummary(UUID id, String name, String code, TenantStatus status, Plan plan, Board board,
        String city, Instant createdAt, Instant trialEndsAt, long userCount) {

    static TenantSummary of(Tenant t, long userCount) {
        return new TenantSummary(t.getId(), t.getName(), t.getCode(), t.getStatus(), t.getPlan(), t.getBoard(),
                t.getCity(), t.getCreatedAt(), t.getTrialEndsAt(), userCount);
    }
}
