package com.akshara.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.AccountsFixtures;
import com.akshara.support.FeeFixtures;

/**
 * Guards found by negative testing: parallel petty cash spends cannot overdraw the box, other income cannot be
 * booked on a fee head, and parallel cancellations of one receipt answer one 200 and the rest 409 (never 500).
 */
class AccountsGuardsIT extends AccountsIntegrationTest {

    static final LocalDate TODAY = AccountsFixtures.today();

    @Test
    void parallelPettyCashSpendsCannotOverdrawTheBox() throws Exception {
        AccountsSchool s = new AccountsSchool();
        s.fixtures.pettyCash(s.accountant, "TOP_UP", 1_000_00, null, "Float");
        // Each spend is 60% of the box: only one can fit, whatever the order.
        List<Integer> statuses = parallel(5, () -> api.post("/api/accounts/petty-cash", s.accountant.accessToken(), """
                {"entryDate":"%s","kind":"SPENT","amountPaise":60000,"headId":"%s","description":"Repairs"}"""
                .formatted(TODAY, s.repairs)));
        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(c -> c == 201).hasSize(1);
        api.get("/api/accounts/petty-cash?from=" + TODAY + "&to=" + TODAY, s.accountant.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.balancePaise").value(40000));
    }

    @Test
    void otherIncomeIsRefusedOnFeeHeads() throws Exception {
        AccountsSchool s = new AccountsSchool();
        new FeeFixtures(api, mvc).defaultHeads(s.admin);
        api.post("/api/accounts/heads/standard", s.accountant.accessToken(), null).andExpect(status().isOk());
        ResultActions heads = api.get("/api/accounts/heads", s.accountant.accessToken());
        String feeIncome = AccountsFixtures.first(heads, "$[?(@.systemCode == 'FEES')].id");
        String tuition = AccountsFixtures.first(heads, "$[?(@.feeHeadId != null)].id");
        for (String head : List.of(feeIncome, tuition)) {
            api.post("/api/accounts/income", s.accountant.accessToken(), """
                    {"kind":"MISC","headId":"%s","receivedOn":"%s","amountPaise":500,"payerName":"Ramesh",
                     "mode":"CASH"}""".formatted(head, TODAY))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.headId").exists());
        }
        // Other income heads still take receipts.
        s.fixtures.income(s.accountant, s.rentals, "RENTAL", 500, "CASH", null);
    }

    @Test
    void parallelCancellationsOfOneReceiptAnswerOneSuccessAndConflicts() throws Exception {
        AccountsSchool s = new AccountsSchool();
        String receipt = s.fixtures.income(s.accountant, s.rentals, "RENTAL", 120000, "CASH", null);
        List<Integer> statuses = parallel(5, () -> api.post("/api/accounts/income/" + receipt + "/cancel",
                s.accountant.accessToken(), """
                {"reason":"Entered twice"}"""));
        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses).filteredOn(c -> c == 200).hasSize(1);
        assertThat(audit(s.admin, "income_receipt.cancelled")).hasSize(1);
    }

    /** Fires the same request from n threads at once and returns the status codes. */
    static List<Integer> parallel(int n, Callable<ResultActions> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return request.call().andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }
}
