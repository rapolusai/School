package com.akshara.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.akshara.support.InventoryFixtures;
import com.akshara.support.TestApi;

/** Masters, items, the stock ledger, stock takes with variance, the reports and who may do what. */
class StockIT extends InventoryIntegrationTest {

    @Test
    void permissionsPerRole() throws Exception {
        InventorySchool s = new InventorySchool();
        // The accountant and the teacher read; neither manages.
        api.get("/api/inventory/items", s.accountant.accessToken()).andExpect(status().isOk());
        api.get("/api/inventory/items", s.teacher.accessToken()).andExpect(status().isOk());
        api.post("/api/inventory/categories", s.teacher.accessToken(), """
                {"name":"X","depreciationRateBp":0,"active":true}""").andExpect(status().isForbidden());
        api.post("/api/inventory/items", s.accountant.accessToken(), """
                {"name":"X","categoryId":"%s","unit":"pc","kind":"CONSUMABLE","reorderLevel":0,"active":true}"""
                .formatted(s.stationery)).andExpect(status().isForbidden());
        api.put("/api/inventory/approvers", s.principal.accessToken(), """
                {"approvers":[]}""").andExpect(status().isForbidden());
        // Someone without inventory.read sees nothing at all.
        api.createUser(s.admin, "Guard", s.email("guard"), java.util.List.of("SECURITY"));
        var guard = api.login(s.school.code(), s.email("guard"), TestApi.PASSWORD);
        api.get("/api/inventory/items", guard.accessToken()).andExpect(status().isForbidden());
        api.get("/api/inventory/reports/low-stock", guard.accessToken()).andExpect(status().isForbidden());
        // The storekeeper manages but cannot approve.
        api.get("/api/inventory/approvals/queue", s.keeper.accessToken()).andExpect(status().isForbidden());
        api.get("/api/inventory/approvals/queue", s.principal.accessToken()).andExpect(status().isOk());
    }

    @Test
    void approversNeedThePermissionAndAreListedOnce() throws Exception {
        InventorySchool s = new InventorySchool();
        s.fixtures.approvers(s.admin, """
                {"approvers":[{"userId":"%s","limitPaise":100}]}""".formatted(s.keeperId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['approvers[0].userId']").exists());
        s.fixtures.approvers(s.admin, """
                {"approvers":[{"userId":"%s","limitPaise":100},{"userId":"%s","limitPaise":200}]}"""
                .formatted(s.principalId, s.principalId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['approvers[1].userId']").exists());
        s.limits(50_000_00);
        api.get("/api/inventory/approvers", s.keeper.accessToken()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].limitPaise").value(5000000))
                .andExpect(jsonPath("$[1].limitPaise").doesNotExist());
        assertThat(audit(s.admin, "purchase_approvers.updated")).isNotEmpty();
    }

    @Test
    void mastersAreUniqueAndInUseOnesCannotBeDeleted() throws Exception {
        InventorySchool s = new InventorySchool();
        api.post("/api/inventory/categories", s.keeper.accessToken(), """
                {"name":"stationery","depreciationRateBp":0,"active":true}""").andExpect(status().isConflict());
        api.post("/api/inventory/locations", s.keeper.accessToken(), """
                {"name":"MAIN STORE","kind":"STORE","active":true}""").andExpect(status().isConflict());
        api.post("/api/inventory/categories", s.keeper.accessToken(), """
                {"name":"Lab","depreciationRateBp":12000,"active":true}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.depreciationRateBp").exists());
        String item = s.consumable("A4 paper", 5);
        api.delete("/api/inventory/categories/" + s.stationery, s.keeper.accessToken())
                .andExpect(status().isConflict());
        api.put("/api/inventory/categories/" + s.stationery, s.keeper.accessToken(), """
                {"name":"Stationery and paper","depreciationRateBp":0,"active":true}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.inUse").value(true));
        api.delete("/api/inventory/items/" + item, s.keeper.accessToken()).andExpect(status().isNoContent());
        api.delete("/api/inventory/categories/" + s.stationery, s.keeper.accessToken())
                .andExpect(status().isNoContent());
        api.get("/api/inventory/categories", s.keeper.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        // Unknown ids: 404 in the path, 400 on the field in a body.
        api.put("/api/inventory/locations/" + java.util.UUID.randomUUID(), s.keeper.accessToken(), """
                {"name":"Lab","kind":"LAB","active":true}""").andExpect(status().isNotFound());
        api.post("/api/inventory/items", s.keeper.accessToken(), """
                {"name":"X","categoryId":"%s","unit":"pc","kind":"CONSUMABLE","reorderLevel":0,
                 "preferredVendorId":"%s","active":true}""".formatted(java.util.UUID.randomUUID(), s.vendor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.categoryId").exists());
        assertThat(audit(s.admin, "inventory_category.deleted")).hasSize(1);
    }

    @Test
    void stockIsTheSumOfMovementsAndNeverGoesBelowZero() throws Exception {
        InventorySchool s = new InventorySchool();
        String paper = s.consumable("A4 paper", 10);
        api.get("/api/inventory/items/" + paper, s.keeper.accessToken()).andExpect(jsonPath("$.stock").value(0))
                .andExpect(jsonPath("$.low").value(true));
        s.fixtures.receive(s.keeper, paper, s.store, 30, 260_00).andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(30));
        s.fixtures.issue(s.keeper, paper, 12, s.science, s.teacherId, "Worksheets").andExpect(status().isCreated())
                .andExpect(jsonPath("$.departmentName").value("Science"))
                .andExpect(jsonPath("$.issuedToName").value("Rahul Verma"))
                .andExpect(jsonPath("$.balance").value(18));
        // Too many: 409 on the quantity.
        s.fixtures.issue(s.keeper, paper, 19, s.science, null, "Too much").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.quantity").exists());
        // Neither a department nor a person: 400.
        s.fixtures.issue(s.keeper, paper, 1, null, null, "Nobody").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.departmentId").exists());
        // Another school's department is foreign: 400 on the field.
        InventorySchool other = new InventorySchool();
        s.fixtures.issue(s.keeper, paper, 1, other.science, null, "Foreign").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.departmentId").exists());
        api.post("/api/inventory/movements/return", s.keeper.accessToken(), """
                {"itemId":"%s","quantity":2,"movedOn":"%s","departmentId":"%s","purpose":"Spare"}"""
                .formatted(paper, InventoryFixtures.today(), s.science)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(20));
        api.post("/api/inventory/movements/write-off", s.keeper.accessToken(), """
                {"itemId":"%s","quantity":3,"movedOn":"%s","reason":"Water damage"}"""
                .formatted(paper, InventoryFixtures.today())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(17));
        api.post("/api/inventory/movements/write-off", s.keeper.accessToken(), """
                {"itemId":"%s","quantity":3,"movedOn":"%s","reason":"Tomorrow"}"""
                .formatted(paper, InventoryFixtures.today().plusDays(1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.movedOn").exists());
        api.get("/api/inventory/items/" + paper + "/ledger", s.keeper.accessToken())
                .andExpect(jsonPath("$.item.stock").value(17)).andExpect(jsonPath("$.item.low").value(false))
                .andExpect(jsonPath("$.movements", hasSize(4)))
                .andExpect(jsonPath("$.movements[3].kind").value("WRITE_OFF"))
                .andExpect(jsonPath("$.movements[3].balance").value(17));
        // An asset item is not stock.
        String desk = s.fixtures.item(s.keeper, "Desk", s.stationery, "ASSET", 0, null);
        s.fixtures.receive(s.keeper, desk, s.store, 1, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.itemId").exists());
        // Its kind cannot change once there are movements; the paper's cannot either.
        api.put("/api/inventory/items/" + paper, s.keeper.accessToken(), """
                {"name":"A4 paper","categoryId":"%s","unit":"ream","kind":"ASSET","reorderLevel":10,"active":true}"""
                .formatted(s.stationery)).andExpect(status().isConflict());
        api.delete("/api/inventory/items/" + paper, s.keeper.accessToken()).andExpect(status().isConflict());
        // Another school cannot see it.
        api.get("/api/inventory/items/" + paper, other.keeper.accessToken()).andExpect(status().isNotFound());
        api.get("/api/inventory/items", other.keeper.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        assertThat(audit(s.admin, "stock.issued")).hasSize(1);
        assertThat(audit(s.admin, "stock.written_off")).hasSize(1);
    }

    @Test
    void stockTakePostsTheVarianceAsAdjustments() throws Exception {
        InventorySchool s = new InventorySchool();
        String paper = s.consumable("A4 paper", 5);
        String chalk = s.consumable("Chalk", 5);
        s.fixtures.receive(s.keeper, paper, s.store, 30, 260_00).andExpect(status().isCreated());
        s.fixtures.receive(s.keeper, chalk, s.store, 10, 45_00).andExpect(status().isCreated());
        String take = TestApi.read(api.post("/api/inventory/stock-takes", s.keeper.accessToken(), """
                {"locationId":"%s","notes":"Quarter end"}""".formatted(s.store)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines").value(2)).andExpect(jsonPath("$.status").value("OPEN")), "$.id");
        api.post("/api/inventory/stock-takes/" + take + "/count", s.keeper.accessToken(), """
                {"counts":[{"itemId":"%s","countedQty":27},{"itemId":"%s","countedQty":12}]}"""
                .formatted(paper, chalk)).andExpect(status().isOk()).andExpect(jsonPath("$.counted").value(2))
                .andExpect(jsonPath("$.variances").value(2));
        api.post("/api/inventory/stock-takes/" + take + "/count", s.keeper.accessToken(), """
                {"counts":[{"itemId":"%s","countedQty":1}]}""".formatted(java.util.UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['counts[0].itemId']").exists());
        api.post("/api/inventory/stock-takes/" + take + "/close", s.keeper.accessToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        api.post("/api/inventory/stock-takes/" + take + "/close", s.keeper.accessToken(), null)
                .andExpect(status().isConflict());
        api.get("/api/inventory/items/" + paper, s.keeper.accessToken()).andExpect(jsonPath("$.stock").value(27));
        api.get("/api/inventory/items/" + chalk + "/ledger", s.keeper.accessToken())
                .andExpect(jsonPath("$.item.stock").value(12))
                .andExpect(jsonPath("$.movements[1].kind").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.movements[1].direction").value(1))
                .andExpect(jsonPath("$.movements[1].stockTakeId").value(take));
        assertThat(audit(s.admin, "stock.adjusted")).hasSize(2);
        assertThat(audit(s.admin, "stock_take.closed")).hasSize(1);
    }

    @Test
    void reportsAndCsv() throws Exception {
        InventorySchool s = new InventorySchool();
        String paper = s.consumable("A4 paper", 10);
        String chalk = s.consumable("Chalk", 5);
        s.fixtures.receive(s.keeper, paper, s.store, 30, 260_00).andExpect(status().isCreated());
        s.fixtures.receive(s.keeper, chalk, s.store, 4, 45_00).andExpect(status().isCreated());
        s.fixtures.issue(s.keeper, paper, 5, s.science, null, "Worksheets").andExpect(status().isCreated());
        api.get("/api/inventory/reports/stock-on-hand", s.accountant.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(1)))
                .andExpect(jsonPath("$.categories[0].stock").value(29))
                .andExpect(jsonPath("$.categories[0].valuePaise").value(25 * 260_00 + 4 * 45_00))
                .andExpect(jsonPath("$.items", hasSize(2)));
        api.get("/api/inventory/reports/low-stock", s.principal.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].name").value("Chalk"));
        String from = InventoryFixtures.today().withDayOfMonth(1).toString();
        String to = InventoryFixtures.today().toString();
        api.get("/api/inventory/reports/consumption?from=" + from + "&to=" + to, s.keeper.accessToken())
                .andExpect(status().isOk()).andExpect(jsonPath("$.departments[0]").value("Science"))
                .andExpect(jsonPath("$.cells[0].quantity").value(5))
                .andExpect(jsonPath("$.cells[0].valuePaise").value(5 * 260_00));
        api.get("/api/inventory/reports/consumption?from=" + to + "&to=" + from, s.keeper.accessToken())
                .andExpect(status().isBadRequest());
        String csv = body(api.get("/api/inventory/reports/low-stock.csv", s.keeper.accessToken())
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(
                        "low-stock-"))));
        assertThat(csv).startsWith("﻿Item,Category").contains("Chalk,Stationery,pc,4,5,1,Vijaya Stationers");
        api.get("/api/inventory/movements?from=" + from + "&to=" + to + "&kind=ISSUE", s.keeper.accessToken())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void dashboardCardForStorekeepers() throws Exception {
        InventorySchool s = new InventorySchool();
        s.consumable("Chalk", 5);
        api.get("/api/dashboard", s.keeper.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.inventory.lowStockItems").value(1))
                .andExpect(jsonPath("$.inventory.ordersWaiting").value(0));
        api.get("/api/dashboard", s.teacher.accessToken()).andExpect(jsonPath("$.inventory").doesNotExist());
    }
}
