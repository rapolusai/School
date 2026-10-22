package com.akshara.hostel;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published in the transaction that allots a bed (or moves a boarder to another bed). The fees module has no hostel
 * fee head yet; when it gains one it can listen here and add the room's monthly fee to the student's dues from
 * {@code fromDate}. {@code previousAllotmentId} is set on a room change.
 */
public record HostelAllotted(UUID tenantId, UUID allotmentId, UUID previousAllotmentId, UUID studentId, UUID hostelId,
        String hostelName, UUID roomId, String roomNumber, long monthlyFeePaise, LocalDate fromDate) {
}
