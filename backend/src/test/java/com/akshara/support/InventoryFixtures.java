package com.akshara.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.test.web.servlet.ResultActions;

import com.akshara.support.TestApi.Session;

/** Builds inventory records through the API, as a school's storekeeper would, for the integration tests. */
public final class InventoryFixtures {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final TestApi api;

    public InventoryFixtures(TestApi api) {
        this.api = api;
    }

    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    public ResultActions approvers(Session session, String json) throws Exception {
        return api.put("/api/inventory/approvers", session.accessToken(), json);
    }

    public String category(Session session, String name, int rateBp) throws Exception {
        return TestApi.read(api.post("/api/inventory/categories", session.accessToken(), """
                {"name":"%s","depreciationRateBp":%d,"active":true}""".formatted(name, rateBp))
                .andExpect(status().isCreated()), "$.id");
    }

    public String location(Session session, String name, String kind) throws Exception {
        return TestApi.read(api.post("/api/inventory/locations", session.accessToken(), """
                {"name":"%s","kind":"%s","active":true}""".formatted(name, kind)).andExpect(status().isCreated()),
                "$.id");
    }

    public String item(Session session, String name, String category, String kind, int reorder, String vendor)
            throws Exception {
        return TestApi.read(api.post("/api/inventory/items", session.accessToken(), """
                {"name":"%s","categoryId":"%s","unit":"pc","kind":"%s","reorderLevel":%d,"preferredVendorId":%s,
                 "active":true}""".formatted(name, category, kind, reorder, vendor == null ? "null" : "\"" + vendor
                        + "\"")).andExpect(status().isCreated()), "$.id");
    }

    public ResultActions receive(Session session, String item, String location, int qty, long unitCost)
            throws Exception {
        return api.post("/api/inventory/movements/receipt", session.accessToken(), """
                {"itemId":"%s","locationId":%s,"quantity":%d,"unitCostPaise":%d,"movedOn":"%s","purpose":"Opening"}"""
                .formatted(item, location == null ? "null" : "\"" + location + "\"", qty, unitCost, today()));
    }

    public ResultActions issue(Session session, String item, int qty, String departmentId, String personId,
            String purpose) throws Exception {
        return api.post("/api/inventory/movements/issue", session.accessToken(), """
                {"itemId":"%s","quantity":%d,"movedOn":"%s","departmentId":%s,"issuedToId":%s,"purpose":"%s"}"""
                .formatted(item, qty, today(), departmentId == null ? "null" : "\"" + departmentId + "\"",
                        personId == null ? "null" : "\"" + personId + "\"", purpose));
    }

    public String asset(Session session, String name, String category, String location, String custodian,
            long cost, LocalDate bought) throws Exception {
        return TestApi.read(api.post("/api/inventory/assets", session.accessToken(), """
                {"name":"%s","categoryId":"%s","locationId":%s,"custodianId":%s,"purchaseDate":"%s",
                 "costPaise":%d,"condition":"GOOD"}""".formatted(name, category,
                        location == null ? "null" : "\"" + location + "\"",
                        custodian == null ? "null" : "\"" + custodian + "\"", bought, cost))
                .andExpect(status().isCreated()), "$.asset.id");
    }

    public String requisition(Session session, String departmentId, String item, int qty, long unitPaise,
            boolean submit) throws Exception {
        return TestApi.read(api.post("/api/inventory/requisitions?submit=" + submit, session.accessToken(), """
                {"departmentId":%s,"purpose":"Class needs","lines":[{"itemId":%s,"description":"Thing",
                 "quantity":%d,"unit":"pc","estimatedUnitPaise":%d}]}"""
                .formatted(departmentId == null ? "null" : "\"" + departmentId + "\"",
                        item == null ? "null" : "\"" + item + "\"", qty, unitPaise)).andExpect(status().isCreated()),
                "$.id");
    }

    /** A one-line order (GST 18%) for the vendor, submitted when asked. */
    public String order(Session session, String vendor, String item, int qty, long unitPaise, boolean submit)
            throws Exception {
        return TestApi.read(api.post("/api/inventory/purchase-orders?submit=" + submit, session.accessToken(), """
                {"vendorId":"%s","orderedOn":"%s","terms":"Net 30","lines":[{"itemId":%s,"description":"Thing",
                 "quantity":%d,"unit":"pc","unitPaise":%d,"gstRateBp":1800}]}"""
                .formatted(vendor, today(), item == null ? "null" : "\"" + item + "\"", qty, unitPaise))
                .andExpect(status().isCreated()), "$.id");
    }

    public ResultActions receiveOrder(Session session, String order, String poLine, int qty, String location,
            String invoice) throws Exception {
        return api.post("/api/inventory/purchase-orders/" + order + "/receipts", session.accessToken(), """
                {"receivedOn":"%s","locationId":%s,"invoiceNumber":%s,"lines":[{"poLineId":"%s","quantity":%d}]}"""
                .formatted(today(), location == null ? "null" : "\"" + location + "\"",
                        invoice == null ? "null" : "\"" + invoice + "\"", poLine, qty));
    }

    /** One row in every inventory table: masters, a stock take, an asset with a transfer and a verification, a
     * requisition approved, an RFQ with a quote, and an order received. The accounts vendor must exist already. */
    public void everything(Session session, String userId, String staffId) throws Exception {
        approvers(session, """
                {"approvers":[{"userId":"%s","limitPaise":null}]}""".formatted(userId)).andExpect(status().isOk());
        String vendor = AccountsFixtures.first(api.get("/api/accounts/vendors", session.accessToken()), "$[0].id");
        String category = category(session, "Stationery", 0);
        String store = location(session, "Main store", "STORE");
        String item = item(session, "A4 paper", category, "CONSUMABLE", 5, vendor);
        receive(session, item, store, 20, 25000).andExpect(status().isCreated());
        issue(session, item, 3, null, staffId, "Worksheets").andExpect(status().isCreated());
        String take = TestApi.read(api.post("/api/inventory/stock-takes", session.accessToken(), """
                {"locationId":"%s"}""".formatted(store)).andExpect(status().isCreated()), "$.id");
        api.post("/api/inventory/stock-takes/" + take + "/count", session.accessToken(), """
                {"counts":[{"itemId":"%s","countedQty":16}]}""".formatted(item)).andExpect(status().isOk());
        api.post("/api/inventory/stock-takes/" + take + "/close", session.accessToken(), null)
                .andExpect(status().isOk());
        // Bought without a location, then moved into the store (a transfer to where it already is is refused).
        String asset = asset(session, "Projector", category, null, staffId, 4200000, today().minusYears(1));
        api.post("/api/inventory/assets/" + asset + "/transfer", session.accessToken(), """
                {"toLocationId":"%s","reason":"Moved","movedOn":"%s"}""".formatted(store, today()))
                .andExpect(status().isOk());
        String round = TestApi.read(api.post("/api/inventory/verification-rounds", session.accessToken(), """
                {"name":"Annual check"}""").andExpect(status().isCreated()), "$.id");
        api.post("/api/inventory/verification-rounds/" + round + "/verify", session.accessToken(), """
                {"assetId":"%s","result":"FOUND"}""".formatted(asset)).andExpect(status().isOk());
        String requisition = requisition(session, null, item, 10, 25000, true);
        api.post("/api/inventory/requisitions/" + requisition + "/approve", session.accessToken(), null)
                .andExpect(status().isOk());
        String rfq = TestApi.read(api.post("/api/inventory/rfqs", session.accessToken(), """
                {"title":"Paper","requisitionId":"%s"}""".formatted(requisition)).andExpect(status().isCreated()),
                "$.id");
        api.post("/api/inventory/rfqs/" + rfq + "/quotes", session.accessToken(), """
                {"vendorId":"%s","amountPaise":240000,"deliveryDays":5}""".formatted(vendor))
                .andExpect(status().isCreated());
        String order = order(session, vendor, item, 10, 24000, true);
        api.post("/api/inventory/purchase-orders/" + order + "/approve", session.accessToken(), null)
                .andExpect(status().isOk());
        String line = TestApi.read(api.get("/api/inventory/purchase-orders/" + order, session.accessToken()),
                "$.lines[0].id");
        receiveOrder(session, order, line, 10, store, "INV-1").andExpect(status().isCreated());
    }
}
