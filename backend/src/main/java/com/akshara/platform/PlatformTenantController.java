package com.akshara.platform;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/tenants")
public class PlatformTenantController {

    private final TenantDirectory tenants;

    public PlatformTenantController(TenantDirectory tenants) {
        this.tenants = tenants;
    }

    @GetMapping
    public List<TenantSummary> list() {
        return tenants.summaries();
    }
}
