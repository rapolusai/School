package com.akshara.hostel;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published in the transaction that vacates a bed (a room change publishes it for the old bed and
 * {@link HostelAllotted} for the new one). The fees module can stop the room's monthly fee after {@code toDate}.
 */
public record HostelVacated(UUID tenantId, UUID allotmentId, UUID studentId, UUID hostelId, UUID roomId,
        LocalDate toDate, String reason) {
}
