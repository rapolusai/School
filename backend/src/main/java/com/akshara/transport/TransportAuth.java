package com.akshara.transport;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/** Who is using the transport screens: the audit actor, and whether they may manage transport. */
final class TransportAuth {

    static final String READ = "hasAuthority('transport.read')";
    static final String MANAGE = "hasAuthority('transport.manage')";
    static final String TRIP = "hasAuthority('transport.trip')";

    private TransportAuth() {
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }

    /** True for users with transport.manage, who may run any route's trip. */
    static boolean canManage() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(Permissions.TRANSPORT_MANAGE::equals);
    }
}
