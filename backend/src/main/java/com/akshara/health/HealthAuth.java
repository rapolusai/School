package com.akshara.health;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/** Who is using the health and safety screens: the audit actor and the permissions behind each screen. */
final class HealthAuth {

    static final String READ = "hasAnyAuthority('health.read', 'health.manage')";
    static final String MANAGE = "hasAuthority('health.manage')";
    static final String SAFETY = "hasAuthority('safety.manage')";
    /** The POCSO committee: the permission, or membership checked in the service. Every staff sign-in may ask. */
    static final String STAFF = "hasAuthority('leave.request')";
    static final String PARENT = "hasAuthority('child.view')";

    private HealthAuth() {
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }

    static Set<String> permissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    static boolean canManage() {
        return permissions().contains(Permissions.HEALTH_MANAGE);
    }

    static boolean hasPocsoPermission() {
        return permissions().contains(Permissions.POCSO_COMMITTEE);
    }
}
