package com.akshara.inventory;

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

import com.akshara.support.InventoryFixtures;
import com.akshara.support.TestApi;

/**
 * Guards on the stock ledger and on dates: parallel issues and write-offs cannot take stock below zero, a return
 * cannot bring back more than was issued, a back-dated issue needs the stock on that date, absurd dates are refused,
 * and asset and order dates must be in order.
 */
class InventoryGuardsIT extends InventoryIntegrationTest {

    private static LocalDate today() {
        return InventoryFixtures.today();
    }

    private ResultActions move(InventorySchool s, String kind, String item, int qty, LocalDate on) throws Exception {
        String body = kind.equals("write-off")
                ? """
                        {"itemId":"%s","quantity":%d,"movedOn":"%s","reason":"Damaged"}""".formatted(item, qty, on)
                : kind.equals("receipt")
                        ? """
                                {"itemId":"%s","quantity":%d,"unitCostPaise":100,"movedOn":"%s"}"""
                                .formatted(item, qty, on)
                        : """
                                {"itemId":"%s","quantity":%d,"movedOn":"%s","departmentId":"%s","purpose":"Class"}"""
                                .formatted(item, qty, on, s.science);
        return api.post("/api/inventory/movements/" + kind, s.keeper.accessToken(), body);
    }

    private long stock(InventorySchool s, String item) throws Exception {
        return ((Number) com.jayway.jsonpath.JsonPath.read(body(api.get("/api/inventory/items/" + item,
                s.keeper.accessToken()).andExpect(status().isOk())), "$.stock")).longValue();
    }

    /** Fires the requests at once and returns their statuses. */
    private List<Integer> parallel(List<Callable<ResultActions>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (Callable<ResultActions> r : requests) {
                results.add(pool.submit(() -> {
                    start.await();
                    return r.call().andReturn().getResponse().getStatus();
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

    @Test
    void parallelIssuesAndWriteOffsNeverTakeStockBelowZero() throws Exception {
        InventorySchool s = new InventorySchool();
        String paper = s.consumable("A4 paper", 0);
        move(s, "receipt", paper, 12, today()).andExpect(status().isCreated());
        List<Callable<ResultActions>> issues = new ArrayList<>();
        for (int n = 0; n < 5; n++) {
            issues.add(() -> move(s, "issue", paper, 3, today()));
        }
        List<Integer> statuses = parallel(issues);
        assertThat(statuses).filteredOn(c -> c == 201).hasSize(4);
        assertThat(statuses).filteredOn(c -> c == 409).hasSize(1);
        assertThat(stock(s, paper)).isZero();

        // Issues and write-offs mixed: 4 in stock, five takers of 3 each, only one fits.
        String chalk = s.consumable("Chalk", 0);
        move(s, "receipt", chalk, 4, today()).andExpect(status().isCreated());
        List<Callable<ResultActions>> mixed = new ArrayList<>();
        for (int n = 0; n < 5; n++) {
            String kind = n % 2 == 0 ? "issue" : "write-off";
            mixed.add(() -> move(s, kind, chalk, 3, today()));
        }
        statuses = parallel(mixed);
        assertThat(statuses).filteredOn(c -> c == 201).hasSize(1);
        assertThat(statuses).filteredOn(c -> c == 409).hasSize(4);
        assertThat(stock(s, chalk)).isEqualTo(1);
    }

    @Test
    void returnsNeedAnIssueAndBackDatedIssuesNeedTheStockThen() throws Exception {
        InventorySchool s = new InventorySchool();
        String paper = s.consumable("A4 paper", 0);
        // Nothing was ever issued: nothing can come back.
        move(s, "return", paper, 50, today()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.quantity").exists());
        move(s, "receipt", paper, 10, today().minusDays(10)).andExpect(status().isCreated());
        // Nothing was in stock 20 days ago.
        move(s, "issue", paper, 4, today().minusDays(20)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.quantity").exists());
        move(s, "issue", paper, 4, today().minusDays(5)).andExpect(status().isCreated());
        // Back-dated to the receipt day: 10 then, but the later issue of 4 must still be covered.
        move(s, "write-off", paper, 7, today().minusDays(10)).andExpect(status().isConflict());
        move(s, "issue", paper, 6, today().minusDays(10)).andExpect(status().isCreated());
        assertThat(stock(s, paper)).isZero();
        // Returns: 10 issued in all, but only 6 of them by seven days ago.
        move(s, "return", paper, 7, today().minusDays(7)).andExpect(status().isConflict());
        move(s, "return", paper, 6, today().minusDays(7)).andExpect(status().isCreated());
        move(s, "return", paper, 5, today()).andExpect(status().isConflict());
        move(s, "return", paper, 4, today()).andExpect(status().isCreated());
        assertThat(stock(s, paper)).isEqualTo(10);
        // The running balance in the ledger never dips below zero.
        String ledger = body(api.get("/api/inventory/items/" + paper + "/ledger", s.keeper.accessToken()));
        List<Number> balances = com.jayway.jsonpath.JsonPath.read(ledger, "$.movements[*].balance");
        assertThat(balances).allSatisfy(b -> assertThat(b.longValue()).isNotNegative());
        // Absurd dates: 1900, and more than five years back.
        move(s, "receipt", paper, 1, LocalDate.of(1900, 1, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.movedOn").exists());
        move(s, "issue", paper, 1, today().minusYears(5).minusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.movedOn").exists());
        move(s, "receipt", paper, 1, today().minusYears(5)).andExpect(status().isCreated());
    }

    @Test
    void assetAndOrderDatesMustBeInOrder() throws Exception {
        InventorySchool s = new InventorySchool();
        LocalDate bought = today().minusMonths(6);
        api.post("/api/inventory/assets", s.keeper.accessToken(), """
                {"name":"X","categoryId":"%s","purchaseDate":"%s","costPaise":100,"condition":"GOOD",
                 "warrantyExpiresOn":"2000-01-01"}""".formatted(s.stationery, bought))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.warrantyExpiresOn").exists());
        String asset = s.fixtures.asset(s.keeper, "Microscope", s.stationery, s.store, s.teacherId, 28_500_00,
                bought);
        api.put("/api/inventory/assets/" + asset, s.keeper.accessToken(), """
                {"name":"Microscope","categoryId":"%s","locationId":"%s","purchaseDate":"%s","costPaise":100,
                 "condition":"GOOD","warrantyExpiresOn":"%s"}"""
                .formatted(s.stationery, s.store, bought, bought.minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.warrantyExpiresOn").exists());
        String lab = s.fixtures.location(s.keeper, "Science lab", "LAB");
        api.post("/api/inventory/assets/" + asset + "/transfer", s.keeper.accessToken(), """
                {"toLocationId":"%s","movedOn":"2000-01-01"}""".formatted(lab)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.movedOn").exists());
        api.post("/api/inventory/assets/" + asset + "/transfer", s.keeper.accessToken(), """
                {"toLocationId":"%s","movedOn":"%s"}""".formatted(s.store, today()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.toLocationId").exists());
        api.post("/api/inventory/assets/" + asset + "/transfer", s.keeper.accessToken(), """
                {"toCustodianId":"%s","movedOn":"%s"}""".formatted(s.teacherId, today()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.toCustodianId").exists());
        // Same place, new custodian is a real transfer; so is a new place on the purchase day.
        api.post("/api/inventory/assets/" + asset + "/transfer", s.keeper.accessToken(), """
                {"toLocationId":"%s","toCustodianId":"%s","movedOn":"%s"}""".formatted(s.store, s.headId, bought))
                .andExpect(status().isOk());
        api.post("/api/inventory/assets/" + asset + "/transfer", s.keeper.accessToken(), """
                {"toLocationId":"%s","movedOn":"%s"}""".formatted(lab, today())).andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers.length()").value(2));

        String order = """
                {"vendorId":"%s","orderedOn":"%s","expectedOn":%s,"lines":[{"description":"Paper","quantity":1,
                 "unit":"pc","unitPaise":100,"gstRateBp":1200}]}""";
        api.post("/api/inventory/purchase-orders", s.keeper.accessToken(),
                order.formatted(s.vendor, today(), "\"" + today().minusDays(1) + "\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.expectedOn").exists());
        api.post("/api/inventory/purchase-orders", s.keeper.accessToken(),
                order.formatted(s.vendor, "1900-01-01", "null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.orderedOn").exists());
        String po = TestApi.read(api.post("/api/inventory/purchase-orders", s.keeper.accessToken(),
                order.formatted(s.vendor, today(), "\"" + today() + "\"")).andExpect(status().isCreated()), "$.id");
        api.put("/api/inventory/purchase-orders/" + po, s.keeper.accessToken(),
                order.formatted(s.vendor, today(), "\"" + today().minusDays(3) + "\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.expectedOn").exists());
    }
}
