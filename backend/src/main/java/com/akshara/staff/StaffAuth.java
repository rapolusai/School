package com.akshara.staff;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/** The signed-in caller as the staff controllers need them. */
final class StaffAuth {

    private StaffAuth() {
    }

    static boolean has(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(permission::equals);
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse(null));
    }

    /** leave.approve holders decide anyone's leave (but their own); others only what is routed to them. */
    static LeaveService.Caller caller() {
        return new LeaveService.Caller(actor(), has(Permissions.LEAVE_APPROVE));
    }

    /** "YYYY-MM", the current month in India when empty. */
    static YearMonth month(String value) {
        if (value == null || value.isBlank()) {
            return YearMonth.now(StaffAttendanceService.INDIA);
        }
        try {
            YearMonth m = YearMonth.parse(value.strip());
            if (m.getYear() < 2000 || m.getYear() > 2100) {
                throw ApiException.badRequest("Pick a month between 2000 and 2100.", "month");
            }
            return m;
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Give the month as YYYY-MM, for example 2026-07.", "month");
        }
    }
}
