package com.akshara.reports;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.reports.DashboardService.Summary;

/**
 * The staff dashboard's figures in one call. dashboard.view to ask; each card inside needs its own module's
 * permission and is null without it (see {@link DashboardService}).
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('dashboard.view')")
    public Summary summary() {
        return dashboard.summary();
    }
}
