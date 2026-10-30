package com.akshara.billing;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.akshara.support.BillingFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Production before Akshara's live Razorpay keys: {@code akshara.billing.payments.gateway=off}. No school can pay an
 * invoice online (so none can mark its own invoice paid through the sandbox): the Billing page says so, a payment order
 * is refused and the sandbox checkout does not exist. The Super Admin still records bank transfers.
 */
@TestPropertySource(properties = "akshara.billing.payments.gateway=off")
class OnlinePaymentsOffIT extends IntegrationTest {

    @Test
    void withTheGatewayOffInvoicesArePaidByBankTransferOnly() throws Exception {
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        School school = api.signup();
        Session admin = api.login(school);
        String invoiceId = billing.paying(root, school.tenantId(), "36");

        api.get("/api/billing", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onlinePayments").value(false));
        api.post("/api/billing/invoices/" + invoiceId + "/orders", admin.accessToken(), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Online payment is off"));
        api.get("/api/billing/sandbox/orders/order_SBXanything", admin.accessToken())
                .andExpect(status().isNotFound());
        api.post("/api/billing/sandbox/orders/order_SBXanything/complete", admin.accessToken(), """
                {"outcome":"SUCCESS"}""").andExpect(status().isNotFound());

        billing.pay(root, school.tenantId(), invoiceId);
        api.get("/api/billing/invoices/" + invoiceId, admin.accessToken())
                .andExpect(jsonPath("$.status").value("PAID"));
    }
}
