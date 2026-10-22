package com.akshara.accounts;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/** The signed-in caller as the accounts controllers need them, and common parameter checks. */
final class AccountsAccess {

    static final String READ = "hasAuthority('accounts.read')";
    static final String MANAGE = "hasAuthority('accounts.manage')";
    static final String APPROVE = "hasAuthority('accounts.approve')";
    static final String READ_OR_APPROVE = "hasAnyAuthority('accounts.read', 'accounts.approve')";

    /** The longest period a report accepts. */
    static final int MAX_DAYS = 400;

    private AccountsAccess() {
    }

    static Set<String> permissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? Set.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    static boolean has(String permission) {
        return permissions().contains(permission);
    }

    static boolean canApprove() {
        return has(Permissions.ACCOUNTS_APPROVE);
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }

    /** A from–to period of at most {@link #MAX_DAYS} days (400 with a field error otherwise). */
    static void period(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw ApiException.badRequest("Give both dates.", "from");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end date is before the start date.", "to");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw ApiException.badRequest("Give a period of at most " + MAX_DAYS + " days.", "to");
        }
    }

    static LocalDate notFuture(LocalDate date, String field) {
        if (date == null) {
            throw ApiException.badRequest("Give the date.", field);
        }
        if (date.isAfter(FinancialYear.today())) {
            throw ApiException.badRequest("The date cannot be in the future.", field);
        }
        return date;
    }
}
