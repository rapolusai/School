package com.akshara.library;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/** Who is using the library screens: the audit actor, and whether they may manage the library. */
final class LibraryAuth {

    static final String READ = "hasAuthority('library.read')";
    static final String MANAGE = "hasAuthority('library.manage')";

    private LibraryAuth() {
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }

    static boolean canManage() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(Permissions.LIBRARY_MANAGE::equals);
    }
}
