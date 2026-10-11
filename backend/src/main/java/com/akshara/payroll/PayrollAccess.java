package com.akshara.payroll;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;

/** The signed-in caller as the payroll controllers need them, and the month parameter. */
final class PayrollAccess {

    static final String READ = "hasAuthority('payroll.read')";
    static final String MANAGE = "hasAuthority('payroll.manage')";
    static final String APPROVE = "hasAuthority('payroll.approve')";

    private PayrollAccess() {
    }

    static boolean has(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(permission::equals);
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }

    /** "YYYY-MM" between 2000 and 2100 (400 with a field error otherwise). */
    static YearMonth month(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(PayrollForms.MONTH_MESSAGE, field);
        }
        try {
            YearMonth m = YearMonth.parse(value.strip());
            if (m.getYear() < 2000 || m.getYear() > 2100) {
                throw ApiException.badRequest("Pick a month between 2000 and 2100.", field);
            }
            return m;
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(PayrollForms.MONTH_MESSAGE, field);
        }
    }

    /** "2026-27": the financial year from April 2026 to March 2027 (400 otherwise). */
    static YearMonth financialYearStart(String value, String field) {
        if (value == null || !value.strip().matches("^[0-9]{4}-[0-9]{2}$")) {
            throw ApiException.badRequest("Give the financial year as YYYY-YY, for example 2026-27.", field);
        }
        int start = Integer.parseInt(value.strip().substring(0, 4));
        int end = Integer.parseInt(value.strip().substring(5));
        if (start < 2000 || start > 2100 || (start + 1) % 100 != end) {
            throw ApiException.badRequest("Give the financial year as YYYY-YY, for example 2026-27.", field);
        }
        return YearMonth.of(start, 4);
    }
}
