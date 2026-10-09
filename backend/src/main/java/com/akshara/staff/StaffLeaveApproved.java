package com.akshara.staff;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Published (as a Spring application event, inside the approving transaction) when a staff member's leave is approved,
 * so the timetable can propose substitute teachers for their periods. {@code workingDays} are the school days the
 * person is away (Sundays not counted); with {@code halfDay} they are away for half of that one day. Listeners that
 * must not act on a rolled-back approval should use {@code @TransactionalEventListener}.
 */
public record StaffLeaveApproved(UUID tenantId, UUID requestId, UUID userId, UUID leaveTypeId, LocalDate fromDate,
        LocalDate toDate, boolean halfDay, BigDecimal days, List<LocalDate> workingDays, UUID approvedById,
        Instant at) {

    public StaffLeaveApproved {
        workingDays = List.copyOf(workingDays);
    }
}
