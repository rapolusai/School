package com.akshara.billing;

import java.util.UUID;

import com.akshara.platform.TenantStatus;

/** The Super Admin lifted a school's suspension; {@code status} is the status it went back to. */
public record SchoolReactivated(UUID tenantId, TenantStatus status) {
}
