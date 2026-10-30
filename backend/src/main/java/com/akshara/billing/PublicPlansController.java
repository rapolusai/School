package com.akshara.billing;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.billing.BillingViews.PlanView;

/** The plan catalogue for anyone (the sign-up page lists it before the school has an account). */
@RestController
public class PublicPlansController {

    @GetMapping("/api/public/plans")
    public List<PlanView> plans() {
        return PlanCatalog.all().stream().map(p -> PlanView.of(p, null)).toList();
    }
}
