package com.akshara.onboarding;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService.Actor;
import com.akshara.billing.BillingAccounts;
import com.akshara.billing.BillingCycle;
import com.akshara.billing.BillingForms.DetailsForm;
import com.akshara.billing.BillingForms.PaymentForm;
import com.akshara.billing.BillingForms.StartForm;
import com.akshara.billing.BillingService;
import com.akshara.billing.BillingViews.SchoolAccount;
import com.akshara.billing.PaymentMode;
import com.akshara.platform.Plan;
import com.akshara.shared.TenantContext;

/**
 * The demo school's subscription with Akshara: billing details in Telangana (the sample seller's state, so the
 * invoice shows CGST and SGST), a yearly Growth subscription for every student on roll from 1 April of the current
 * financial year, and its invoice, paid in full by bank transfer. The school becomes ACTIVE. Runs once, after the rest
 * of the demo data.
 */
@Component
class DemoBillingData {

    private static final Logger log = LoggerFactory.getLogger(DemoBillingData.class);

    static final String LEGAL_NAME = "Akshara Demo School Society";
    static final String UTR = "DEMO-UTR-000001";

    private final BillingService billing;
    private final BillingAccounts accounts;
    private final TransactionTemplate tx;

    DemoBillingData(BillingService billing, BillingAccounts accounts, PlatformTransactionManager transactionManager) {
        this.billing = billing;
        this.accounts = accounts;
        this.tx = new TransactionTemplate(transactionManager);
    }

    void seed(UUID tenantId) {
        Actor demo = new Actor(null, "Demo data");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate periodStart = LocalDate.of(today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1, 4, 1);
        String invoiceNo = TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            billing.updateDetails(new DetailsForm(LEGAL_NAME, "Plot 12, Road No. 3, Banjara Hills, Hyderabad 500034",
                    "36", null), demo);
            int students = (int) Math.max(1, accounts.account().activeStudents());
            SchoolAccount account = billing.start(new StartForm(Plan.GROWTH, BillingCycle.YEARLY, students,
                    periodStart), demo);
            var invoice = account.invoices().getFirst();
            billing.recordPayment(invoice.id(), new PaymentForm(null, PaymentMode.BANK_TRANSFER, UTR, today), demo);
            return invoice.invoiceNo();
        }));
        log.info("Seeded the demo school's Growth subscription with paid invoice {}", invoiceNo);
    }
}
