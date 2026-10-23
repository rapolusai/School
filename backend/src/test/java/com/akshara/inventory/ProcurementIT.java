package com.akshara.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.InventoryFixtures;
import com.akshara.support.TestApi;

/**
 * Procurement: requisitions through the department head and the purchase approver with limits and escalation,
 * RFQs with at most three quotes, purchase orders with GST, approval, partial receipts, the invoice and the
 * PurchaseReceived event.
 */
@RecordApplicationEvents
class ProcurementIT extends InventoryIntegrationTest {

    @Autowired
    ApplicationEvents events;

    @Test
    void requisitionGoesToTheDepartmentHeadThenThePurchaseApprover() throws Exception {
        InventorySchool s = new InventorySchool();
        s.limits(50_000_00);
        String chalk = s.consumable("Chalk", 5);
        // Rahul (Science) raises one for his department; he cannot raise for another department.
        String other = TestApi.read(api.post("/api/staff/departments", s.admin.accessToken(), """
                {"name":"Languages"}""").andExpect(status().isCreated()), "$.id");
        api.post("/api/inventory/requisitions", s.teacher.accessToken(), """
                {"departmentId":"%s","purpose":"x","lines":[{"description":"Pens","quantity":1,"unit":"pc",
                 "estimatedUnitPaise":100}]}""".formatted(other)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.departmentId").exists());
        String id = s.fixtures.requisition(s.teacher, null, chalk, 20, 45_00, false);
        api.get("/api/inventory/requisitions/" + id, s.teacher.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.number").doesNotExist())
                .andExpect(jsonPath("$.departmentName").value("Science"))
                .andExpect(jsonPath("$.estimatedPaise").value(900_00))
                .andExpect(jsonPath("$.lines[0].itemName").value("Chalk"));
        // Only its owner (or the storekeeper) sees a draft; the head not yet, the principal always.
        api.get("/api/inventory/requisitions/" + id, s.head.accessToken()).andExpect(status().isNotFound());
        api.get("/api/inventory/requisitions/" + id, s.principal.accessToken()).andExpect(status().isOk());
        api.post("/api/inventory/requisitions/" + id + "/approve", s.principal.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/inventory/requisitions/" + id + "/submit", s.teacher.accessToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.number").value("REQ/" + com.akshara.support.AccountsFixtures.financialYear()
                        + "/000001")).andExpect(jsonPath("$.headName").value("Anjali Deshmukh"));
        api.put("/api/inventory/requisitions/" + id, s.teacher.accessToken(), """
                {"purpose":"changed","lines":[{"description":"Pens","quantity":1,"unit":"pc",
                 "estimatedUnitPaise":100}]}""").andExpect(status().isConflict());
        // The head sees it and decides; nobody else may act at this stage.
        api.get("/api/inventory/requisitions", s.head.accessToken()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].canAct").value(true));
        api.post("/api/inventory/requisitions/" + id + "/head-approve", s.teacher.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/inventory/requisitions/" + id + "/approve", s.principal.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/inventory/requisitions/" + id + "/head-approve", s.head.accessToken(), """
                {"note":"Needed for practicals"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEAD_APPROVED"))
                .andExpect(jsonPath("$.approvals[0].stage").value("HEAD"))
                .andExpect(jsonPath("$.approvals[0].action").value("APPROVED"));
        // Now the principal (within the limit) approves; the storekeeper cannot.
        api.post("/api/inventory/requisitions/" + id + "/approve", s.keeper.accessToken(), null)
                .andExpect(status().isForbidden());
        api.get("/api/inventory/approvals/queue", s.principal.accessToken())
                .andExpect(jsonPath("$.requisitions", hasSize(1))).andExpect(jsonPath("$.limitPaise").value(5000000));
        api.post("/api/inventory/requisitions/" + id + "/approve", s.principal.accessToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvals", hasSize(2)))
                .andExpect(jsonPath("$.approvals[1].limitPaise").value(5000000));
        // Another school sees none of it.
        InventorySchool o = new InventorySchool();
        api.get("/api/inventory/requisitions/" + id, o.admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/inventory/requisitions", o.admin.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        assertThat(audit(s.admin, "requisition.submitted")).hasSize(1);
        assertThat(audit(s.admin, "requisition.head_approved")).hasSize(1);
        assertThat(audit(s.admin, "requisition.approved")).hasSize(1);
    }

    @Test
    void bigRequisitionsArePassedOnAboveTheLimitAndRejectionNeedsAReason() throws Exception {
        InventorySchool s = new InventorySchool();
        s.limits(50_000_00);
        // The keeper (no department) submits straight to the purchase stage.
        String big = s.fixtures.requisition(s.keeper, null, null, 10, 8_000_00, true);
        api.get("/api/inventory/requisitions/" + big, s.keeper.accessToken())
                .andExpect(jsonPath("$.status").value("HEAD_APPROVED"))
                .andExpect(jsonPath("$.headUserId").doesNotExist());
        api.post("/api/inventory/requisitions/" + big + "/approve", s.principal.accessToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HEAD_APPROVED"))
                .andExpect(jsonPath("$.escalatedAbovePaise").value(5000000))
                .andExpect(jsonPath("$.approvals[0].action").value("ESCALATED"))
                .andExpect(jsonPath("$.canAct").value(false));
        api.post("/api/inventory/requisitions/" + big + "/approve", s.principal.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/inventory/requisitions/" + big + "/reject", s.principal.accessToken(), """
                {"reason":"no"}""").andExpect(status().isConflict());
        api.post("/api/inventory/requisitions/" + big + "/reject", s.admin.accessToken(), """
                {"reason":""}""").andExpect(status().isBadRequest());
        api.post("/api/inventory/requisitions/" + big + "/approve", s.admin.accessToken(), """
                {"note":"Within the year's budget"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // Nobody above: the principal alone with a limit.
        s.fixtures.approvers(s.admin, """
                {"approvers":[{"userId":"%s","limitPaise":%d},{"userId":"%s","limitPaise":%d}]}"""
                .formatted(s.principalId, 50_000_00, s.adminId, 60_000_00)).andExpect(status().isOk());
        String huge = s.fixtures.requisition(s.keeper, null, null, 10, 9_000_00, true);
        api.post("/api/inventory/requisitions/" + huge + "/approve", s.admin.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/inventory/requisitions/" + huge + "/reject", s.principal.accessToken(), """
                {"reason":"Far above what we planned"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Far above what we planned"));
        // Drafts can be deleted by their owner only.
        String draft = s.fixtures.requisition(s.teacher, null, null, 1, 100, false);
        api.delete("/api/inventory/requisitions/" + draft, s.head.accessToken()).andExpect(status().isNotFound());
        api.delete("/api/inventory/requisitions/" + draft, s.teacher.accessToken()).andExpect(status().isNoContent());
        api.delete("/api/inventory/requisitions/" + big, s.keeper.accessToken()).andExpect(status().isConflict());
        assertThat(audit(s.admin, "requisition.escalated")).hasSize(1);
        assertThat(audit(s.admin, "requisition.rejected")).hasSize(1);
    }

    @Test
    void rfqTakesAtMostThreeQuotesAndRecordsTheChoice() throws Exception {
        InventorySchool s = new InventorySchool();
        String v2 = new com.akshara.support.AccountsFixtures(api).vendor(s.admin, "Sharma Electricals");
        String v3 = new com.akshara.support.AccountsFixtures(api).vendor(s.admin, "Kalyan Traders");
        String v4 = new com.akshara.support.AccountsFixtures(api).vendor(s.admin, "Fourth Vendor");
        String rfq = TestApi.read(api.post("/api/inventory/rfqs", s.keeper.accessToken(), """
                {"title":"Projector for Class 9 B"}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("RFQ/" + com.akshara.support.AccountsFixtures.financialYear()
                        + "/000001")), "$.id");
        for (String v : List.of(s.vendor, v2, v3)) {
            api.post("/api/inventory/rfqs/" + rfq + "/quotes", s.keeper.accessToken(), """
                    {"vendorId":"%s","amountPaise":4500000,"deliveryDays":7}""".formatted(v))
                    .andExpect(status().isCreated());
        }
        api.post("/api/inventory/rfqs/" + rfq + "/quotes", s.keeper.accessToken(), """
                {"vendorId":"%s","amountPaise":4400000}""".formatted(v2)).andExpect(status().isConflict());
        api.post("/api/inventory/rfqs/" + rfq + "/quotes", s.keeper.accessToken(), """
                {"vendorId":"%s","amountPaise":4400000}""".formatted(v4)).andExpect(status().isConflict());
        api.post("/api/inventory/rfqs/" + rfq + "/quotes", s.keeper.accessToken(), """
                {"vendorId":"%s","amountPaise":4400000}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.vendorId").exists());
        String quote = TestApi.read(api.get("/api/inventory/rfqs/" + rfq, s.teacher.accessToken())
                .andExpect(jsonPath("$.quotes", hasSize(3))), "$.quotes[1].id");
        api.post("/api/inventory/rfqs/" + rfq + "/select", s.keeper.accessToken(), """
                {"quoteId":"%s","reason":"Includes installation"}""".formatted(quote)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SELECTED"))
                .andExpect(jsonPath("$.quotes[1].selected").value(true));
        api.post("/api/inventory/rfqs/" + rfq + "/cancel", s.keeper.accessToken(), null)
                .andExpect(status().isConflict());
        assertThat(audit(s.admin, "rfq.selected")).hasSize(1);
    }

    @Test
    void purchaseOrderIsApprovedReceivedInPartsAndInvoiced() throws Exception {
        InventorySchool s = new InventorySchool();
        s.limits(50_000_00);
        String paper = s.consumable("A4 paper", 10);
        InventorySchool o = new InventorySchool();
        // A foreign vendor or item: 400 on the field.
        api.post("/api/inventory/purchase-orders", s.keeper.accessToken(), """
                {"vendorId":"%s","orderedOn":"%s","lines":[{"description":"Paper","quantity":1,"unit":"pc",
                 "unitPaise":100,"gstRateBp":1200}]}""".formatted(o.vendor, InventoryFixtures.today()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.vendorId").exists());
        api.post("/api/inventory/purchase-orders", s.keeper.accessToken(), """
                {"vendorId":"%s","orderedOn":"%s","lines":[{"itemId":"%s","description":"Paper","quantity":1,
                 "unit":"pc","unitPaise":100,"gstRateBp":1200}]}"""
                .formatted(s.vendor, InventoryFixtures.today(), UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['lines[0].itemId']").exists());
        // 40 reams at ₹260 + 12% GST, and toner at ₹3,200 + 18%.
        String po = TestApi.read(api.post("/api/inventory/purchase-orders", s.keeper.accessToken(), """
                {"vendorId":"%s","orderedOn":"%s","expectedOn":"%s","terms":"Net 30",
                 "lines":[{"itemId":"%s","description":"A4 paper 75 GSM","quantity":40,"unit":"ream",
                 "unitPaise":26000,"gstRateBp":1200},{"description":"Toner HP 88A","quantity":2,"unit":"pc",
                 "unitPaise":320000,"gstRateBp":1800}]}"""
                .formatted(s.vendor, InventoryFixtures.today(), InventoryFixtures.today().plusDays(10), paper))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.number").value("PO/" + com.akshara.support.AccountsFixtures.financialYear()
                        + "/000001"))
                .andExpect(jsonPath("$.subtotalPaise").value(1040000 + 640000))
                .andExpect(jsonPath("$.taxPaise").value(124800 + 115200))
                .andExpect(jsonPath("$.totalPaise").value(1920000))
                .andExpect(jsonPath("$.vendorGstin").value("36AABCU9603R1ZX")), "$.id");
        api.post("/api/inventory/purchase-orders/" + po + "/approve", s.principal.accessToken(), null)
                .andExpect(status().isConflict());
        api.post("/api/inventory/purchase-orders/" + po + "/submit", s.keeper.accessToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        api.get("/api/inventory/approvals/queue", s.principal.accessToken())
                .andExpect(jsonPath("$.orders", hasSize(1)));
        api.get("/api/dashboard", s.keeper.accessToken()).andExpect(jsonPath("$.inventory.ordersWaiting").value(1));
        String line = TestApi.read(api.get("/api/inventory/purchase-orders/" + po, s.principal.accessToken())
                .andExpect(jsonPath("$.canAct").value(true)), "$.lines[0].id");
        String toner = TestApi.read(api.get("/api/inventory/purchase-orders/" + po, s.keeper.accessToken()),
                "$.lines[1].id");
        // Nothing can be received before approval.
        s.fixtures.receiveOrder(s.keeper, po, line, 10, s.store, null).andExpect(status().isConflict());
        api.post("/api/inventory/purchase-orders/" + po + "/approve", s.principal.accessToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvals[0].approverName").value("Lakshmi Iyer"));
        // Part one: 25 reams; the stock follows and the event carries the amount with GST.
        s.fixtures.receiveOrder(s.keeper, po, line, 25, s.store, "VS-1187").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(25))
                .andExpect(jsonPath("$.receipts", hasSize(1)))
                .andExpect(jsonPath("$.receipts[0].number").value("GRN/"
                        + com.akshara.support.AccountsFixtures.financialYear() + "/000001"))
                .andExpect(jsonPath("$.receipts[0].totalPaise").value(25 * 26000 + 78000))
                .andExpect(jsonPath("$.invoiceNumber").value("VS-1187"));
        api.get("/api/inventory/items/" + paper + "/ledger", s.keeper.accessToken())
                .andExpect(jsonPath("$.item.stock").value(25))
                .andExpect(jsonPath("$.movements[0].purchaseOrderId").value(po))
                .andExpect(jsonPath("$.movements[0].unitCostPaise").value(26000));
        assertThat(events.stream(PurchaseReceived.class).filter(e -> e.purchaseOrderId().toString().equals(po)))
                .singleElement().satisfies(e -> {
                    assertThat(e.amountPaise()).isEqualTo(25 * 26000 + 78000);
                    assertThat(e.invoiceNumber()).isEqualTo("VS-1187");
                    assertThat(e.vendorName()).isEqualTo("Vijaya Stationers");
                    assertThat(e.complete()).isFalse();
                    assertThat(e.items()).singleElement().satisfies(i -> {
                        assertThat(i.quantity()).isEqualTo(25);
                        assertThat(i.gstRateBp()).isEqualTo(1200);
                    });
                });
        // Too many: 409 on the line's quantity; a foreign line: 400.
        s.fixtures.receiveOrder(s.keeper, po, line, 16, s.store, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors['lines[0].quantity']").exists());
        s.fixtures.receiveOrder(s.keeper, po, UUID.randomUUID().toString(), 1, s.store, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['lines[0].poLineId']").exists());
        api.post("/api/inventory/purchase-orders/" + po + "/cancel", s.keeper.accessToken(), """
                {"reason":"x"}""").andExpect(status().isConflict());
        // The rest, in one go.
        api.post("/api/inventory/purchase-orders/" + po + "/receipts", s.keeper.accessToken(), """
                {"receivedOn":"%s","locationId":"%s","lines":[{"poLineId":"%s","quantity":15},
                 {"poLineId":"%s","quantity":2}]}""".formatted(InventoryFixtures.today(), s.store, line, toner))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receipts", hasSize(2)));
        assertThat(events.stream(PurchaseReceived.class).filter(PurchaseReceived::complete)).hasSize(1);
        s.fixtures.receiveOrder(s.keeper, po, line, 1, s.store, null).andExpect(status().isConflict());
        api.post("/api/inventory/purchase-orders/" + po + "/invoice", s.keeper.accessToken(), """
                {"invoiceNumber":"VS-1187","invoiceDate":"%s","invoicePaise":1920000}"""
                .formatted(InventoryFixtures.today())).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoicePaise").value(1920000));
        api.get("/api/inventory/reports/purchase-orders?from=" + InventoryFixtures.today().withDayOfMonth(1) + "&to="
                + InventoryFixtures.today(), s.accountant.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.byVendor[0].vendorName").value("Vijaya Stationers"))
                .andExpect(jsonPath("$.byVendor[0].totalPaise").value(1920000))
                .andExpect(jsonPath("$.orders", hasSize(1)));
        // Another school: 404 and an empty list.
        api.get("/api/inventory/purchase-orders/" + po, o.keeper.accessToken()).andExpect(status().isNotFound());
        api.get("/api/inventory/purchase-orders", o.keeper.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        assertThat(audit(s.admin, "purchase_order.received")).hasSize(2);
        assertThat(audit(s.admin, "purchase_order.invoiced")).hasSize(1);
    }

    @Test
    void orderApprovalFollowsTheLimitsAndRejectionAndCancellation() throws Exception {
        InventorySchool s = new InventorySchool();
        s.limits(50_000_00);
        String big = s.fixtures.order(s.keeper, s.vendor, null, 10, 6_000_00, true);
        api.post("/api/inventory/purchase-orders/" + big + "/approve", s.principal.accessToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.escalatedAbovePaise").value(5000000));
        api.post("/api/inventory/purchase-orders/" + big + "/reject", s.principal.accessToken(), """
                {"reason":"no"}""").andExpect(status().isConflict());
        api.post("/api/inventory/purchase-orders/" + big + "/reject", s.admin.accessToken(), """
                {"reason":"Buy after the audit"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        api.post("/api/inventory/purchase-orders/" + big + "/submit", s.keeper.accessToken(), null)
                .andExpect(status().isConflict());
        String draft = s.fixtures.order(s.keeper, s.vendor, null, 1, 100, false);
        api.put("/api/inventory/purchase-orders/" + draft, s.keeper.accessToken(), """
                {"vendorId":"%s","orderedOn":"%s","lines":[{"description":"Pens","quantity":3,"unit":"pc",
                 "unitPaise":1000,"gstRateBp":0}]}""".formatted(s.vendor, InventoryFixtures.today()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalPaise").value(3000))
                .andExpect(jsonPath("$.number").value("PO/" + com.akshara.support.AccountsFixtures.financialYear()
                        + "/000002"));
        api.post("/api/inventory/purchase-orders/" + draft + "/cancel", s.keeper.accessToken(), """
                {"reason":"Not needed"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        api.post("/api/inventory/purchase-orders/" + draft + "/submit", s.teacher.accessToken(), null)
                .andExpect(status().isForbidden());
        api.get("/api/inventory/purchase-orders?status=REJECTED", s.keeper.accessToken())
                .andExpect(jsonPath("$", hasSize(1)));
        assertThat(audit(s.admin, "purchase_order.escalated")).hasSize(1);
        assertThat(audit(s.admin, "purchase_order.rejected")).hasSize(1);
        assertThat(audit(s.admin, "purchase_order.cancelled")).hasSize(1);
    }
}
