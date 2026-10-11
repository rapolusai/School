package com.akshara.transport;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.CurrentUser;
import com.akshara.transport.TransportViews.MyTransport;

/** The signed-in family's bus: a parent's children's routes and today's trips, or a student's own. */
@RestController
public class MyTransportController {

    private final FamilyTransportService family;

    MyTransportController(FamilyTransportService family) {
        this.family = family;
    }

    @GetMapping("/api/me/transport")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public MyTransport mine() {
        return family.mine(CurrentUser.requireId());
    }
}
