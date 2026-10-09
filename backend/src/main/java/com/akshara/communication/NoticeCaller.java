package com.akshara.communication;

import java.util.Set;
import java.util.UUID;

import com.akshara.audit.AuditService.Actor;

/**
 * Who is working with circulars and the calendar: their permissions as booleans, whether they are staff (any role
 * other than Parent and Student; staff see every calendar entry) and the sections they are class teacher of. Built
 * from the access token for requests; school-wide jobs such as the demo data build one directly.
 */
public record NoticeCaller(UUID userId, String name, boolean canSend, boolean canApprove, boolean canManageCalendar,
        boolean staff, Set<UUID> ownSectionIds) {

    public NoticeCaller {
        ownSectionIds = ownSectionIds == null ? Set.of() : Set.copyOf(ownSectionIds);
    }

    public Actor actor() {
        return new Actor(userId, name);
    }
}
