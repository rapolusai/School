package com.akshara.transport;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the bus attendant marks a student boarded, dropped or absent on a trip, inside the transaction that
 * records it. The transport module already queues the parent's message (when the school has it on); listeners that act
 * outside the database should use {@code @TransactionalEventListener} (after commit). {@code stopId} is null for a
 * student boarding at school on the afternoon trip.
 */
public record TransportBoardingRecorded(UUID tenantId, UUID tripId, UUID routeId, TripKind tripKind,
        UUID studentId, UUID stopId, BoardingStatus status, Instant at) {
}
