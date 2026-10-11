package com.akshara.transport;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the bus attendant reports that a trip is running late, inside the transaction that records it. The
 * transport module already queues the messages to waiting families (when the school has delay messages on).
 */
public record TransportTripDelayed(UUID tenantId, UUID tripId, UUID routeId, TripKind tripKind, int minutes,
        String reason, Instant at) {
}
