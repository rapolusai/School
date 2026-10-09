package com.akshara.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService.Actor;
import com.akshara.billing.BillingForms.StartForm;
import com.akshara.platform.Plan;
import com.akshara.shared.TenantContext;
import com.akshara.support.BillingFixtures;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Akshara's invoice numbers: one gap-free series per financial year across every school, safe under concurrent
 * issuing, with a rolled-back invoice giving its number back. Other test classes issue invoices too, so the
 * assertions count from the counter's value before each test.
 */
class InvoiceNumberingIT extends IntegrationTest {

    @Autowired
    BillingService service;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void schoolsConvertingAtTheSameMomentGetConsecutiveUniqueNumbers() throws Exception {
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        List<School> schools = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            School school = api.signup();
            // Half in the seller's state, half elsewhere: one series whatever the tax.
            billing.details(root, school.tenantId(), i % 2 == 0 ? "36" : "29", null);
            schools.add(school);
        }
        String year = GstMath.financialYear(BillingDay.today());
        int before = lastSeq(year);

        ExecutorService pool = Executors.newFixedThreadPool(schools.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (School school : schools) {
                Callable<String> convert = () -> {
                    start.await();
                    return TestApi.read(api.post(BillingFixtures.school(school.tenantId()) + "/subscription",
                            root.accessToken(), """
                                    {"plan":"STARTER","billingCycle":"MONTHLY","billedStudents":40}""")
                            .andExpect(status().isCreated()), "$.invoices[0].invoiceNo");
                };
                results.add(pool.submit(convert));
            }
            start.countDown();
            List<String> numbers = new ArrayList<>();
            for (Future<String> f : results) {
                numbers.add(f.get());
            }
            List<String> expected = new ArrayList<>();
            for (int seq = before + 1; seq <= before + schools.size(); seq++) {
                expected.add(GstMath.invoiceNo("AKS", year, seq));
            }
            assertThat(numbers).containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            pool.shutdownNow();
        }
        assertThat(lastSeq(year)).isEqualTo(before + schools.size());
    }

    @Test
    void anInvoiceThatRollsBackGivesItsNumberBack() throws Exception {
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        School first = api.signup();
        School second = api.signup();
        billing.details(root, first.tenantId(), "36", null);
        billing.details(root, second.tenantId(), "36", null);
        String year = GstMath.financialYear(BillingDay.today());
        int before = lastSeq(year);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String rolledBack = TenantContext.runAs(first.tenantId(), () -> tx.execute(status -> {
            String number = service.start(new StartForm(Plan.GROWTH, BillingCycle.YEARLY, 50, null),
                    new Actor(null, "Test")).invoices().getFirst().invoiceNo();
            status.setRollbackOnly();
            return number;
        }));
        assertThat(rolledBack).isEqualTo(GstMath.invoiceNo("AKS", year, before + 1));
        assertThat(lastSeq(year)).isEqualTo(before);
        // Nothing of the rolled-back conversion is left: the school is still on trial.
        Session admin = api.login(first);
        api.get("/api/billing", admin.accessToken())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.invoices.length()").value(0));

        String next = TestApi.read(api.post(BillingFixtures.school(second.tenantId()) + "/subscription",
                root.accessToken(), """
                        {"plan":"GROWTH","billingCycle":"YEARLY","billedStudents":50}""")
                .andExpect(status().isCreated()), "$.invoices[0].invoiceNo");
        assertThat(next).isEqualTo(rolledBack);
    }

    @Test
    void anIssuedInvoiceCannotBeRewrittenOrDeletedByTheApplication() throws Exception {
        BillingFixtures billing = new BillingFixtures(api);
        Session root = billing.root();
        School school = api.signup();
        String invoiceId = billing.paying(root, school.tenantId(), "36");
        try (Connection app = appConnection()) {
            try (PreparedStatement s = app.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                s.setString(1, school.tenantId().toString());
                s.execute();
            }
            for (String sql : List.of("update billing.invoice set total_paise = 1 where id = ?",
                    "update billing.invoice set invoice_no = 'X' where id = ?",
                    "delete from billing.invoice where id = ?",
                    "delete from billing.invoice_payment where invoice_id = ?",
                    "update billing.invoice_payment set amount_paise = 1 where invoice_id = ?")) {
                assertThatThrownBy(() -> {
                    try (PreparedStatement s = app.prepareStatement(sql)) {
                        s.setObject(1, UUID.fromString(invoiceId));
                        s.executeUpdate();
                    }
                }).as(sql).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            }
            // The counter is the application's to advance, but not to remove.
            assertThatThrownBy(() -> app.createStatement().executeUpdate("delete from billing.invoice_counter"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    private static int lastSeq(String financialYear) throws SQLException {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement(
                        "select last_seq from billing.invoice_counter where financial_year = ?")) {
            s.setString(1, financialYear);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
