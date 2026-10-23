package com.akshara.assist;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;

/** The signed-in caller as the assistance controllers need them. */
final class AssistAccess {

    static final String USE = "hasAuthority('assist.use')";
    static final String MANAGE = "hasAuthority('assist.manage')";

    /** The actor of a job that runs with nobody signed in. */
    static final Actor SYSTEM = new Actor(null, "System");

    private AssistAccess() {
    }

    static Set<String> permissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? Set.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }
}
