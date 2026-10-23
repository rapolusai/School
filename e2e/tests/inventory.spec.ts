import { expect, test, type APIRequestContext } from "@playwright/test";
import { adminCredentials, expectNoHorizontalScroll, loginViaApi, loginViaUi, mainNav, newSchool, PASSWORD, signOut, signupViaApi, type Credentials } from "./support/helpers";

type InventorySchool = { admin: Credentials; keeper: Credentials; teacher: Credentials; principal: Credentials };

/**
 * A new school through the API: a storekeeper, a principal who approves purchases up to ₹50,000, a teacher in the
 * Primary department (no head, so the principal approves straight away), a stationery vendor, a category and a
 * store (docs/api/phase-3-inventory.md).
 */
async function setUpInventory(request: APIRequestContext, tag: string): Promise<InventorySchool> {
  const school = newSchool(tag);
  await signupViaApi(request, school);
  const admin = adminCredentials(school);
  const adminToken = await loginViaApi(request, admin);
  const headers = { Authorization: `Bearer ${adminToken}` };
  const post = async <T>(path: string, data?: unknown, status = 201): Promise<T> => {
    const res = await request.post(path, { headers, data });
    expect(res.status(), `${path}: ${await res.text()}`).toBe(status);
    return (await res.json()) as T;
  };
  const keeper = { schoolCode: school.schoolCode, email: `mohan@${school.schoolCode}.example.com`, password: PASSWORD };
  const teacher = { schoolCode: school.schoolCode, email: `rahul@${school.schoolCode}.example.com`, password: PASSWORD };
  const principal = { schoolCode: school.schoolCode, email: `lakshmi@${school.schoolCode}.example.com`, password: PASSWORD };
  const keeperUser = await post<{ id: string }>("/api/users", { name: "Mohan Das", email: keeper.email, password: keeper.password, roles: ["STOREKEEPER"] });
  const principalUser = await post<{ id: string }>("/api/users", { name: "Lakshmi Iyer", email: principal.email, password: principal.password, roles: ["PRINCIPAL"] });
  const primary = await post<{ id: string }>("/api/staff/departments", { name: "Primary" });
  await post("/api/staff", {
    name: "Rahul Verma",
    email: teacher.email,
    password: teacher.password,
    roles: ["TEACHER"],
    employeeCode: "E2E-INV-1",
    designation: "Teacher",
    departmentId: primary.id,
    employmentType: "PERMANENT",
    dateOfJoining: "2020-06-01",
    mobile: "9876501003",
  });
  await post("/api/accounts/vendors", {
    name: "Vijaya Stationers",
    gstin: "36AABCU9603R1ZX",
    pan: "AABCU9603R",
    phone: "9876543210",
    email: null,
    address: "Abids, Hyderabad",
    bankAccountNumber: null,
    bankIfsc: null,
    tdsSection: null,
    tdsRatePercent: null,
    active: true,
  });
  const keeperHeaders = { Authorization: `Bearer ${await loginViaApi(request, keeper)}` };
  const keeperPost = async (path: string, data: unknown, status = 201) => {
    const res = await request.post(path, { headers: keeperHeaders, data });
    expect(res.status(), `${path}: ${await res.text()}`).toBe(status);
  };
  await keeperPost("/api/inventory/categories", { name: "Stationery", depreciationRateBp: 0, active: true });
  await keeperPost("/api/inventory/categories", { name: "Electronics", depreciationRateBp: 4000, active: true });
  await keeperPost("/api/inventory/locations", { name: "Main store", kind: "STORE", active: true });
  const limits = await request.put("/api/inventory/approvers", {
    headers: keeperHeaders,
    data: { approvers: [{ userId: principalUser.id, limitPaise: 50_000_00 }] },
  });
  expect(limits.status(), await limits.text()).toBe(200);
  void keeperUser;
  return { admin, keeper, teacher, principal };
}

test("the storekeeper sets up an item and an asset, a teacher raises a requisition on a phone, the principal approves it, and the storekeeper orders, receives and issues the stock", async ({ page, request }) => {
  test.setTimeout(180_000);
  const school = await setUpInventory(request, "inventory");

  // The storekeeper adds a consumable with a reorder level, and a projector as a tagged asset.
  await loginViaUi(page, school.keeper);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Inventory", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/inventory$/);
  await expect(page.getByRole("heading", { level: 1, name: "Stores" })).toBeVisible();
  await page.getByRole("navigation", { name: "Inventory sections" }).getByRole("link", { name: "Items" }).click();
  await page.getByTestId("item-new").click();
  const itemForm = page.getByRole("dialog", { name: "New item" });
  await itemForm.getByLabel("Item").fill("Chalk box");
  await itemForm.getByLabel("Category").selectOption({ label: "Stationery" });
  await itemForm.getByLabel("Unit").fill("box");
  await itemForm.getByLabel("Reorder level").fill("5");
  await itemForm.getByLabel("Preferred vendor").selectOption({ label: "Vijaya Stationers" });
  await itemForm.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Item saved." })).toBeVisible();
  const itemRow = page.getByTestId("items-row").filter({ hasText: "Chalk box" });
  await expect(itemRow).toContainText("0 box");
  await expect(itemRow).toContainText("Low stock");

  await page.getByRole("navigation", { name: "Inventory sections" }).getByRole("link", { name: "Assets" }).click();
  await page.getByTestId("asset-new").click();
  const assetForm = page.getByRole("dialog", { name: "New asset" });
  await assetForm.getByLabel("Asset", { exact: true }).fill("Epson projector");
  await assetForm.getByLabel("Category").selectOption({ label: "Electronics" });
  await assetForm.getByLabel("Cost").fill("45000");
  await assetForm.getByLabel("Location").selectOption({ label: "Main store" });
  await assetForm.getByLabel("Custodian").selectOption({ label: "Rahul Verma · Primary" });
  await assetForm.getByLabel("Serial number").fill("EPS-2026-01");
  await assetForm.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Asset AST-000001 added." })).toBeVisible();
  const assetRow = page.getByTestId("assets-row").filter({ hasText: "AST-000001" });
  await expect(assetRow).toContainText("Epson projector");
  await expect(assetRow).toContainText("Rahul Verma");
  await expect(assetRow).toContainText("₹45,000");
  await signOut(page);

  // The teacher raises a requisition from his phone: two boxes of chalk for Class 3.
  await page.setViewportSize({ width: 390, height: 844 });
  await loginViaUi(page, school.teacher);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/inventory/requisitions");
  await expect(page.getByRole("heading", { level: 1, name: "Requisitions" })).toBeVisible();
  await expect(page.getByTestId("requisitions-empty")).toBeVisible();
  await expectNoHorizontalScroll(page);
  await page.getByTestId("requisition-new").click();
  const reqForm = page.getByRole("dialog", { name: "New requisition" });
  await expect(reqForm.getByLabel("Department")).toHaveValue("");
  await reqForm.getByLabel("Purpose").fill("Chalk for Class 3");
  await reqForm.getByLabel("Item").selectOption({ label: "Chalk box (0 box)" });
  await expect(reqForm.getByLabel("Description")).toHaveValue("Chalk box");
  await reqForm.getByLabel("Quantity").fill("20");
  await reqForm.getByLabel("Estimated price (₹)").fill("45");
  await expect(reqForm.getByTestId("requisition-total")).toContainText("₹900");
  await reqForm.getByTestId("requisition-submit").click();
  await expect(page.getByRole("status").filter({ hasText: /Requisition REQ\/\d{4}-\d{2}\/000001 sent for approval/ })).toBeVisible();
  await expect(page).toHaveURL(/\/app\/inventory\/requisitions\/[0-9a-f-]+$/);
  await expect(page.getByTestId("requisition-facts")).toContainText("Primary");
  await expect(page.getByTestId("requisition-estimated")).toContainText("₹900");
  await expect(page.getByRole("main")).toContainText("Awaiting approval");
  await expectNoHorizontalScroll(page);
  await signOut(page);
  await page.setViewportSize({ width: 1440, height: 900 });

  // The principal approves it within her ₹50,000 limit.
  await loginViaUi(page, school.principal);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/inventory/approvals");
  await expect(page.getByRole("heading", { level: 1, name: "Purchase approvals" })).toBeVisible();
  await expect(page.getByTestId("queue-limit")).toContainText("₹50,000");
  await expect(page.getByText("1 requisition waiting")).toBeVisible();
  const queueRow = page.getByTestId("queue-req-row");
  await expect(queueRow).toHaveCount(1);
  await expect(queueRow).toContainText("Chalk for Class 3");
  await queueRow.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Requisition approved." })).toBeVisible();
  await expect(page.getByTestId("queue-reqs-empty")).toBeVisible();
  await signOut(page);

  // The storekeeper raises the order from the requisition, the principal approves it, and the goods arrive.
  await loginViaUi(page, school.keeper);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("tile-lowStock")).toContainText("1");
  await page.goto("/app/inventory/purchase-orders");
  await page.getByTestId("order-new").click();
  const orderForm = page.getByRole("dialog", { name: "New purchase order" });
  await orderForm.getByLabel("Vendor").selectOption({ label: "Vijaya Stationers" });
  await orderForm.getByLabel("Requisition").selectOption({ index: 1 });
  await expect(orderForm.getByLabel("Description")).toHaveValue("Chalk box");
  await expect(orderForm.getByLabel("Quantity")).toHaveValue("20");
  await expect(orderForm.getByLabel("Unit price")).toHaveValue("45");
  await orderForm.getByLabel("GST").selectOption("1200");
  await expect(orderForm.getByTestId("order-totals")).toContainText("₹1,008");
  await orderForm.getByTestId("order-submit").click();
  await expect(page.getByRole("status").filter({ hasText: /Purchase order PO\/\d{4}-\d{2}\/000001 sent for approval/ })).toBeVisible();
  await expect(page).toHaveURL(/\/app\/inventory\/purchase-orders\/[0-9a-f-]+$/);
  const orderUrl = page.url();
  await expect(page.getByTestId("order-total")).toContainText("₹1,008");
  await expect(page.getByTestId("order-vendor")).toContainText("Vijaya Stationers");
  await expect(page.getByTestId("order-receive")).toHaveCount(0);
  await signOut(page);

  await loginViaUi(page, school.principal);
  await page.goto(orderUrl);
  await page.getByTestId("order-approve").click();
  await expect(page.getByRole("status").filter({ hasText: "Purchase order approved." })).toBeVisible();
  await expect(page.getByTestId("order-document")).toContainText("Approved");
  await signOut(page);

  // Twelve boxes arrive first (a partial receipt), then the rest; the ledger shows the stock and the issue.
  await loginViaUi(page, school.keeper);
  await page.goto(orderUrl);
  await page.getByTestId("order-receive").click();
  const receive = page.getByRole("dialog", { name: "Receive goods" });
  await receive.getByLabel("Receiving now").fill("12");
  await receive.getByLabel("Invoice number").fill("VS/1042");
  await receive.getByTestId("receive-save").click();
  await expect(page.getByRole("status").filter({ hasText: "Goods received in part" })).toBeVisible();
  await expect(page.getByTestId("order-document")).toContainText("Partly received");
  await expect(page.getByTestId("order-line-received")).toContainText("12 / 20");
  await expect(page.getByTestId("receipts-list")).toContainText("GRN/");
  await page.getByTestId("order-receive").click();
  await expect(page.getByRole("dialog", { name: "Receive goods" }).getByLabel("Receiving now")).toHaveValue("8");
  await page.getByRole("dialog", { name: "Receive goods" }).getByTestId("receive-save").click();
  await expect(page.getByRole("status").filter({ hasText: "Goods received in full." })).toBeVisible();
  await expect(page.getByTestId("order-document")).toContainText("Received");
  await expect(page.getByTestId("order-receive")).toHaveCount(0);

  await page.goto("/app/inventory/items");
  await page.getByRole("link", { name: "Chalk box" }).click();
  await expect(page.getByTestId("ledger-stock")).toContainText("20 box");
  await expect(page.getByTestId("ledger-row")).toHaveCount(2);
  await page.getByTestId("ledger-issue").click();
  const issue = page.getByRole("dialog", { name: "Issue stock" });
  await issue.getByLabel("Quantity").fill("16");
  await issue.getByLabel("Department").selectOption({ label: "Primary" });
  await issue.getByLabel("Person").selectOption({ label: "Rahul Verma · Primary" });
  await issue.getByLabel("Purpose").fill("Class 3 board work");
  await issue.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Movement recorded." })).toBeVisible();
  await expect(page.getByTestId("ledger-stock")).toContainText("4 box · Low stock");
  const latest = page.getByTestId("ledger-row").first();
  await expect(latest).toContainText("Issue");
  await expect(latest).toContainText("Primary · Rahul Verma");
  await expect(latest).toContainText("Class 3 board work");

  // The report hub lists the stores' reports, and the low-stock report names the chalk.
  await page.goto("/app/reports");
  await expect(page.getByTestId("reports-group-inventory")).toBeVisible();
  await page.getByTestId("report-link-inventoryLowStock").click();
  await expect(page.getByRole("heading", { level: 1, name: "Low stock" })).toBeVisible();
  await expect(page.getByTestId("low-count")).toContainText("1 items need reordering.");
  await expect(page.getByTestId("items-table")).toContainText("Chalk box");
});
