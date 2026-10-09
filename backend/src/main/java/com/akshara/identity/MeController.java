package com.akshara.identity;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.platform.PlatformAdmin;
import com.akshara.platform.PlatformAdminRepository;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.TenantContext;

@RestController
public class MeController {

    private final UserRepository users;
    private final TenantDirectory tenants;
    private final PlatformAdminRepository platformAdmins;

    public MeController(UserRepository users, TenantDirectory tenants, PlatformAdminRepository platformAdmins) {
        this.users = users;
        this.tenants = tenants;
        this.platformAdmins = platformAdmins;
    }

    @GetMapping("/api/me")
    @Transactional(readOnly = true)
    public Me me() {
        if (CurrentUser.isPlatformAdmin()) {
            PlatformAdmin admin = platformAdmins.findById(CurrentUser.requireId())
                    .filter(PlatformAdmin::isActive)
                    .orElseThrow(() -> ApiException.unauthorized("Sign in again."));
            return Me.platformAdmin(admin.getId(), admin.getName(), admin.getEmail());
        }
        UserAccount user = users.findByIdWithRoles(CurrentUser.requireId())
                .filter(UserAccount::isActive)
                .orElseThrow(() -> ApiException.unauthorized("Sign in again."));
        return Me.of(user, tenants.findById(TenantContext.require())
                .orElseThrow(() -> ApiException.unauthorized("Sign in again.")));
    }
}
