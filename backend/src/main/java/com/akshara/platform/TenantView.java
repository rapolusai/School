package com.akshara.platform;

import java.time.Instant;
import java.util.UUID;

/** The parts of a school that other modules (sign-in, the "me" endpoint) need. */
public record TenantView(UUID id, String name, String code, TenantStatus status, Plan plan, Board board,
        String city, Instant trialEndsAt) {

    public static TenantView of(Tenant tenant) {
        return new TenantView(tenant.getId(), tenant.getName(), tenant.getCode(), tenant.getStatus(),
                tenant.getPlan(), tenant.getBoard(), tenant.getCity(), tenant.getTrialEndsAt());
    }
}
