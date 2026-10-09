package com.akshara.identity;

import java.util.List;
import java.util.UUID;

import com.akshara.platform.TenantView;
import com.akshara.shared.Permissions;

/** The signed-in person as the web app sees them. {@code tenant} is null for platform admins. */
public record Me(UUID id, String name, String email, List<String> roles, List<String> permissions,
        boolean platformAdmin, TenantView tenant) {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    static Me of(UserAccount user, TenantView tenant) {
        return new Me(user.getId(), user.getName(), user.getEmail(), user.roleCodes(), user.permissions(), false,
                tenant);
    }

    static Me platformAdmin(UUID id, String name, String email) {
        return new Me(id, name, email, List.of(SUPER_ADMIN), List.of(Permissions.PLATFORM_ADMIN), true, null);
    }
}
