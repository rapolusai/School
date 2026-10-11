package com.akshara.chat;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/** The signed-in person as the chat and PTM services see them. */
final class ChatAccess {

    /** The caller in a conversation: their sign-in, the name shown on their messages, and their side. */
    record Me(UUID id, String name, Side side) {

        Actor actor() {
            return new Actor(id, name);
        }
    }

    private ChatAccess() {
    }

    static Set<String> permissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? Set.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    static boolean can(String permission) {
        return permissions().contains(permission);
    }

    static boolean oversees() {
        return can(Permissions.CHAT_OVERSEE);
    }

    static boolean managesPtm() {
        return can(Permissions.PTM_MANAGE);
    }

    static Actor actor() {
        return new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse(null));
    }

    static Me staff() {
        return new Me(CurrentUser.requireId(), CurrentUser.name().orElse(""), Side.STAFF);
    }

    static Me parent() {
        return new Me(CurrentUser.requireId(), CurrentUser.name().orElse(""), Side.PARENT);
    }
}
