package com.akshara.billing;

import java.time.Instant;
import java.util.UUID;

/**
 * The Super Admin suspended a school: nobody of it can sign in or refresh a session, and its public pages refuse.
 * Published inside the transaction that made the change, for modules that later need to pause their own work for the
 * school (scheduled messages, exports).
 */
public record SchoolSuspended(UUID tenantId, Instant suspendedAt) {
}
