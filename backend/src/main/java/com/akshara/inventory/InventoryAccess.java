package com.akshara.inventory;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/** The signed-in caller as the inventory controllers need them. */
final class InventoryAccess {

    static final String READ = "hasAuthority('inventory.read')";
    static final String MANAGE = "hasAuthority('inventory.manage')";
    static final String APPROVE = "hasAuthority('purchase.approve')";
    static final String READ_OR_APPROVE = "hasAnyAuthority('inventory.read', 'purchase.approve')";

    private InventoryAccess() {
    }

    static Set<String> permissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? Set.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    static boolean canManage() {
        return permissions().contains(Permissions.INVENTORY_MANAGE);
    }

    static boolean canApprove() {
        return permissions().contains(Permissions.PURCHASE_APPROVE);
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }
}
