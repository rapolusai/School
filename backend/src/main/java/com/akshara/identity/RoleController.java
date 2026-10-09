package com.akshara.identity;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoleController {

    private final RoleRepository roles;

    public RoleController(RoleRepository roles) {
        this.roles = roles;
    }

    public record RoleView(String code, String name, List<String> permissions) {
    }

    @GetMapping("/api/roles")
    @PreAuthorize("hasAuthority('roles.read')")
    @Transactional(readOnly = true)
    public List<RoleView> list() {
        return roles.findAllByOrderByNameAsc().stream()
                .map(r -> new RoleView(r.getCode(), r.getName(), r.getPermissions()))
                .toList();
    }
}
