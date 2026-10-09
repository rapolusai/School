package com.akshara.fees;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.fees.FeeViews.CheckoutResult;
import com.akshara.fees.FeeViews.SandboxCheckout;
import com.akshara.fees.OnlinePaymentService.SandboxOutcome;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/**
 * The sandbox gateway's checkout, used by the web app's pretend payment page. Stands in for the page a real gateway
 * would show; it never moves money. Only the person who started the order, or staff with fees.collect, can use it.
 */
@RestController
@RequestMapping("/api/payments/sandbox/orders/{gatewayOrderId}")
@PreAuthorize("hasAuthority('child.view') or hasAuthority('fees.collect')")
public class SandboxCheckoutController {

    public record CompleteRequest(@NotNull SandboxOutcome outcome) {
    }

    private final OnlinePaymentService online;

    public SandboxCheckoutController(OnlinePaymentService online) {
        this.online = online;
    }

    @GetMapping
    public SandboxCheckout checkout(@PathVariable String gatewayOrderId, Authentication auth) {
        return online.sandboxCheckout(gatewayOrderId, CurrentUser.requireId(), staff(auth));
    }

    @PostMapping("/complete")
    public CheckoutResult complete(@PathVariable String gatewayOrderId, @Valid @RequestBody CompleteRequest request,
            Authentication auth) {
        return online.completeSandbox(gatewayOrderId, request.outcome(), CurrentUser.requireId(), staff(auth));
    }

    private static boolean staff(Authentication auth) {
        return auth.getAuthorities().contains(new SimpleGrantedAuthority(Permissions.FEES_COLLECT));
    }
}
