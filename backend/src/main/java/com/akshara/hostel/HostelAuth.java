package com.akshara.hostel;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/** Who is using the hostel screens: the audit actor and the permissions behind each screen. */
final class HostelAuth {

    static final String READ = "hasAnyAuthority('hostel.read', 'hostel.manage')";
    static final String MANAGE = "hasAuthority('hostel.manage')";
    /** The warden's screens: wardens, and the admin standing in. */
    static final String WARDEN = "hasAnyAuthority('hostel.warden', 'hostel.manage')";
    /** Lists read by the office and worked by wardens (wardens see only their hostels). */
    static final String READ_OR_WARDEN = "hasAnyAuthority('hostel.read', 'hostel.manage', 'hostel.warden')";
    /** A student's hostel standing, shown on their student page too. */
    static final String READ_OR_WARDEN_OR_STUDENTS = "hasAnyAuthority('hostel.read', 'hostel.manage', 'hostel.warden', "
            + "'students.read')";
    static final String PARENT = "hasAuthority('child.view')";

    private HostelAuth() {
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
        return permissions().contains(Permissions.HOSTEL_MANAGE);
    }

    static boolean canRead() {
        Set<String> p = permissions();
        return p.contains(Permissions.HOSTEL_READ) || p.contains(Permissions.HOSTEL_MANAGE);
    }

    static boolean isWarden() {
        Set<String> p = permissions();
        return p.contains(Permissions.HOSTEL_WARDEN) || p.contains(Permissions.HOSTEL_MANAGE);
    }

    static boolean isParent() {
        return permissions().contains(Permissions.CHILD_VIEW);
    }
}
