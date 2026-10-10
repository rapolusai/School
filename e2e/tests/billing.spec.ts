import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  signupViaApi,
  type School,
} from "./support/helpers";

/**
 * The Super Admin's billing console and a school's Billing page. Needs the stack's bootstrap Super Admin
 * (PLATFORM_ADMIN_EMAIL / PLATFORM_ADMIN_PASSWORD on the API) passed as E2E_PLATFORM_EMAIL and E2E_PLATFORM_PASSWORD;
 * skipped without them.
 */
const PLATFORM_EMAIL = process.env.E2E_PLATFORM_EMAIL ?? "";
const PLATFORM_PASSWORD = process.env.E2E_PLATFORM_PASSWORD ?? "";

test.skip(!PLATFORM_EMAIL || !PLATFORM_PASSWORD, "Set E2E_PLATFORM_EMAIL and E2E_PLATFORM_PASSWORD to run");

const INVOICE_NO = /^AKS\/\d{2}-\d{2}\/\d{6}$/;
const PAUSED = "Sign-in to this school is paused at the moment. Please contact the school office.";

async function platformLoginViaUi(page: Page) {
  await page.goto("/login");
  await page.getByRole("button", { name: "Super Admin sign in" }).click();
  await page.getByLabel("Email", { exact: true }).fill(PLATFORM_EMAIL);
  await page.getByLabel("Password", { exact: true }).fill(PLATFORM_PASSWORD);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/platform\/schools$/);
}

async function platformToken(request: APIRequestContext): Promise<string> {
  const res = await request.post("/api/platform/auth/login", {
    data: { email: PLATFORM_EMAIL, password: PLATFORM_PASSWORD },
  });
  expect(res.status(), await res.text()).toBe(200);
  return ((await res.json()) as { accessToken: string }).accessToken;
}

/** A trial school with billing details in Telangana, converted to Growth (yearly, 10 students) through the API. */
async function payingSchool(request: APIRequestContext, tag: string) {
  const school = newSchool(tag);
  const { tenantId } = await signupViaApi(request, school);
  const headers = { Authorization: `Bearer ${await platformToken(request)}` };
  const base = `/api/platform/billing/schools/${tenantId}`;
  const details = await request.put(`${base}/details`, {
    headers,
    data: { legalName: "E2E Education Trust", address: "1 Test Road, Hyderabad", stateCode: "36", gstin: null },
  });
  expect(details.status(), await details.text()).toBe(200);
  const started = await request.post(`${base}/subscription`, {
    headers,
    data: { plan: "GROWTH", billingCycle: "YEARLY", billedStudents: 10 },
  });
  expect(started.status(), await started.text()).toBe(201);
  const account = (await started.json()) as { invoices: { id: string; invoiceNo: string }[] };
  return { school, tenantId, invoice: account.invoices[0] };
}

async function openSchoolFromList(page: Page, school: School) {
  await page.getByPlaceholder("Search by name, code or city").fill(school.schoolCode);
  await page.getByRole("link", { name: school.schoolName }).click();
  await expect(page.getByRole("heading", { name: school.schoolName })).toBeVisible();
}

test("the Super Admin converts a trial to Growth, prints the GST invoice and records the payment", async ({
  page,
  request,
}) => {
  const school = newSchool("bill");
  await signupViaApi(request, school);
  await page.addInitScript(() => {
    (window as unknown as { printed: number }).printed = 0;
    window.print = () => {
      (window as unknown as { printed: number }).printed += 1;
    };
  });

  await platformLoginViaUi(page);
  await expect(mainNav(page).getByRole("link", { name: "Plans & billing" })).toBeVisible();
  await expect(mainNav(page).getByRole("link", { name: "Platform health" })).toBeVisible();
  await openSchoolFromList(page, school);

  // A subscription needs the school's state first.
  await expect(page.getByRole("button", { name: "Start paid subscription" })).toBeDisabled();
  await page.getByRole("button", { name: "Edit" }).click();
  const details = page.getByRole("dialog", { name: "Billing details" });
  await details.getByLabel("Legal name").fill("E2E Education Trust");
  await details.getByLabel("State").selectOption("36");
  await details.getByRole("button", { name: "Save" }).click();
  await expect(details).toBeHidden();
  await expect(page.getByTestId("billing-details")).toContainText("Telangana (36)");

  await page.getByRole("button", { name: "Start paid subscription" }).click();
  const start = page.getByRole("dialog", { name: "Start a paid subscription" });
  await start.getByLabel("Plan").selectOption("GROWTH");
  await start.getByLabel("Billed students").fill("10");
  await expect(start.getByTestId("subscription-price")).toHaveText("Each invoice: ₹2,000 + GST 18%, yearly.");
  await start.getByRole("button", { name: "Start and issue invoice" }).click();
  await expect(start).toBeHidden();
  await expect(page.getByText(/Subscription started\. Invoice AKS\/\d{2}-\d{2}\/\d{6} issued\./)).toBeVisible();
  await expect(page.getByRole("main").getByText("Active", { exact: true })).toBeVisible();

  await page.getByTestId("invoices-table").getByRole("link").first().click();
  const invoice = page.getByTestId("invoice");
  await expect(invoice).toBeVisible();
  expect((await invoice.getByTestId("invoice-no").innerText()).trim()).toMatch(INVOICE_NO);
  await expect(invoice).toContainText("E2E Education Trust");
  await expect(invoice).toContainText("CGST 9%");
  await expect(invoice).toContainText("SGST 9%");
  await expect(invoice).not.toContainText("IGST");
  await expect(invoice).toContainText("998315");
  await expect(invoice.getByTestId("invoice-total")).toHaveText("₹2,360");
  await expect(invoice).toContainText("Rupees Two Thousand Three Hundred");
  // The stack runs with the placeholder seller GSTIN, so the invoice says it is a sample.
  await expect(page.getByTestId("invoice-sample")).toBeVisible();

  await page.getByRole("button", { name: "Print or save as PDF" }).click();
  expect(await page.evaluate(() => (window as unknown as { printed: number }).printed)).toBe(1);
  await page.emulateMedia({ media: "print" });
  await expect(invoice).toBeVisible();
  await expect(mainNav(page)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Record payment" })).toBeHidden();
  await page.emulateMedia({ media: "screen" });

  await page.getByRole("button", { name: "Record payment" }).click();
  const payment = page.getByRole("dialog", { name: /^Record a payment for AKS/ });
  await expect(payment.getByLabel("Amount (₹)")).toHaveValue("2360");
  await payment.getByLabel("Reference").fill("UTR-E2E-0001");
  await payment.getByRole("button", { name: "Record payment" }).click();
  await expect(payment).toBeHidden();
  await expect(page.getByText(/Payment recorded\. Invoice AKS\/.+ is paid\./)).toBeVisible();
  await expect(invoice).toContainText("UTR-E2E-0001");
  await expect(invoice.getByTestId("invoice-balance")).toHaveText("₹0");
  await expect(page.getByRole("button", { name: "Record payment" })).toHaveCount(0);
});

test("a suspended school cannot sign in or take enquiries until the Super Admin reactivates it", async ({
  page,
  browser,
  request,
}) => {
  const { school } = await payingSchool(request, "susp");
  const superAdmin = await (await browser.newContext()).newPage();
  await platformLoginViaUi(superAdmin);
  await openSchoolFromList(superAdmin, school);
  await superAdmin.getByRole("button", { name: "Suspend school" }).click();
  const suspend = superAdmin.getByRole("dialog", { name: `Suspend ${school.schoolName}?` });
  await suspend.getByLabel("Reason").fill("E2E: invoice unpaid for 60 days");
  await suspend.getByRole("button", { name: "Suspend school" }).click();
  await expect(superAdmin.getByTestId("account-suspended")).toContainText("E2E: invoice unpaid for 60 days");

  // The right password learns that sign-in is paused; the public enquiry form refuses politely.
  await loginViaUi(page, adminCredentials(school));
  await expect(page.getByRole("alert").filter({ hasText: PAUSED })).toBeVisible();
  await expect(page).toHaveURL(/\/login/);
  await page.goto(`/enquire/${school.schoolCode}`);
  await expect(page.getByText("Enquiry form not available")).toBeVisible();

  await superAdmin.getByRole("button", { name: "Reactivate" }).click();
  await superAdmin
    .getByRole("dialog", { name: `Reactivate ${school.schoolName}?` })
    .getByRole("button", { name: "Reactivate" })
    .click();
  await expect(superAdmin.getByTestId("account-suspended")).toHaveCount(0);
  await expect(superAdmin.getByRole("main").getByText("Active", { exact: true })).toBeVisible();

  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await superAdmin.context().close();
});

test("a School Admin sees the plan, renewal date and invoices, and no other school can", async ({ page, request }) => {
  const { school, invoice } = await payingSchool(request, "mine");
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Billing", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/billing$/);
  await expect(page.getByTestId("billing-plan-name")).toHaveText("Growth");
  await expect(page.getByTestId("billing-renewal")).toBeVisible();
  const table = page.getByTestId("invoices-table");
  await expect(table.getByRole("link", { name: invoice.invoiceNo })).toBeVisible();
  await expect(table).toContainText("Unpaid");
  // Schools never pay from here.
  await expect(page.getByRole("button", { name: /pay/i })).toHaveCount(0);

  await table.getByRole("link", { name: invoice.invoiceNo }).click();
  await expect(page.getByTestId("invoice")).toContainText("E2E Education Trust");
  await expect(page.getByRole("button", { name: "Print or save as PDF" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Record payment" })).toHaveCount(0);

  for (const width of [820, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await expectNoHorizontalScroll(page);
    await page.goto("/app/billing");
    await expect(page.getByTestId("billing-plan-name")).toBeVisible();
    await expectNoHorizontalScroll(page);
    await page.goBack();
  }

  // Another school's admin gets 404 for the invoice and 403 from the Super Admin's console.
  const other = newSchool("bill-other");
  await signupViaApi(request, other);
  const headers = { Authorization: `Bearer ${await loginViaApi(request, adminCredentials(other))}` };
  expect((await request.get(`/api/billing/invoices/${invoice.id}`, { headers })).status()).toBe(404);
  expect((await request.get("/api/platform/billing/renewals", { headers })).status()).toBe(403);
});

test("platform health shows the schools, the database and the app at every width", async ({ page }) => {
  await platformLoginViaUi(page);
  await mainNav(page).getByRole("link", { name: "Platform health" }).click();
  await expect(page).toHaveURL(/\/app\/platform\/health$/);
  await expect(page.getByTestId("health-database")).toContainText("Reachable");
  await expect(page.getByTestId("health-schools")).toContainText("Trial");
  await expect(page.getByTestId("health-app")).toContainText("Up");
  await mainNav(page).getByRole("link", { name: "Plans & billing" }).click();
  await expect(page.getByRole("heading", { name: "Renewals" })).toBeVisible();
  for (const width of [820, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await expectNoHorizontalScroll(page);
  }
});
