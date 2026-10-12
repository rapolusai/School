package com.akshara.frontoffice;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/** Who is using the front office screens: the audit actor and the permissions behind each screen. */
final class FrontOfficeAuth {

    static final String READ = "hasAnyAuthority('frontoffice.read', 'frontoffice.manage')";
    static final String MANAGE = "hasAuthority('frontoffice.manage')";
    /** The gate screen: security, and the office when it stands in at the gate. */
    static final String GATE = "hasAnyAuthority('gate.pass', 'frontoffice.manage')";
    /** The visitor log is read by the office and worked by the gate. */
    static final String READ_OR_GATE = "hasAnyAuthority('frontoffice.read', 'frontoffice.manage', 'gate.pass')";
    static final String PARENT = "hasAuthority('child.view')";

    private FrontOfficeAuth() {
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
        return permissions().contains(Permissions.FRONTOFFICE_MANAGE);
    }

    static boolean canRead() {
        Set<String> p = permissions();
        return p.contains(Permissions.FRONTOFFICE_READ) || p.contains(Permissions.FRONTOFFICE_MANAGE);
    }

    static boolean atGate() {
        Set<String> p = permissions();
        return p.contains(Permissions.GATE_PASS) || p.contains(Permissions.FRONTOFFICE_MANAGE);
    }
}
