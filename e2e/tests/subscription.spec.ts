import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { adminCredentials, loginViaApi, loginViaUi, mainNav, newSchool, signupViaApi } from "./support/helpers";

/**
 * A School Admin manages the school's own subscription (docs/api/phase-3-subscriptions.md): pays an open invoice
 * online through the sandbox gateway, and changes the plan (upgrade, downgrade, cancellation). Needs the stack's bootstrap Super Admin (E2E_PLATFORM_EMAIL /
 * E2E_PLATFORM_PASSWORD) to start the paid subscription; skipped without them. The stack must run the billing
 * gateway in sandbox mode (the default).
 */
const PLATFORM_EMAIL = process.env.E2E_PLATFORM_EMAIL ?? "";
const PLATFORM_PASSWORD = process.env.E2E_PLATFORM_PASSWORD ?? "";

test.skip(!PLATFORM_EMAIL || !PLATFORM_PASSWORD, "Set E2E_PLATFORM_EMAIL and E2E_PLATFORM_PASSWORD to run");

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

/** A trial school in Telangana converted to a paid plan (yearly, 10 students) through the Super Admin's API. */
async function payingSchool(request: APIRequestContext, tag: string, plan = "GROWTH") {
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
    data: { plan, billingCycle: "YEARLY", billedStudents: 10 },
  });
  expect(started.status(), await started.text()).toBe(201);
  const account = (await started.json()) as { invoices: { id: string; invoiceNo: string }[] };
  return { school, tenantId, invoice: account.invoices[0], headers, base };
}

test("a School Admin pays an open invoice online and the Super Admin sees it paid", async ({ page, request }) => {
  const { school, invoice, headers, base } = await payingSchool(request, "payon");
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Billing", exact: true }).click();
  const table = page.getByTestId("invoices-table");
  await expect(table).toContainText("Unpaid");

  await table.getByRole("button", { name: `Pay invoice ${invoice.invoiceNo} online` }).click();
  await expect(page).toHaveURL(/\/app\/billing\/pay\/sandbox\/order_SBX/);
  const checkout = page.getByTestId("billing-sandbox-checkout");
  await expect(checkout).toContainText(invoice.invoiceNo);
  await expect(page.getByTestId("billing-sandbox-amount")).toHaveText("₹2,360");
  await page.getByRole("button", { name: "Payment succeeds" }).click();

  await expect(page).toHaveURL(new RegExp(`/app/billing/invoices/${invoice.id}\\?paid=1$`));
  await expect(page.getByTestId("invoice-paid-banner")).toBeVisible();
  await expect(page.getByRole("button", { name: /online/ })).toHaveCount(0);

  // Paying again is refused; the Super Admin sees the online payment.
  const token = await loginViaApi(request, adminCredentials(school));
  const again = await request.post(`/api/billing/invoices/${invoice.id}/orders`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(again.status()).toBe(409);
  const seen = await request.get(`${base}/invoices/${invoice.id}`, { headers });
  const body = (await seen.json()) as { status: string; payments: { mode: string }[] };
  expect(body.status).toBe("PAID");
  expect(body.payments[0].mode).toBe("ONLINE");
});

test("a failed sandbox payment charges nothing and can be tried again", async ({ page, request }) => {
  const { school, invoice } = await payingSchool(request, "payfail");
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/billing");
  await page.getByTestId("invoices-table").getByRole("button", { name: `Pay invoice ${invoice.invoiceNo} online` }).click();
  await page.getByRole("button", { name: "Payment fails" }).click();
  await expect(page.getByText("The payment failed. Nothing was charged.")).toBeVisible();
  await page.getByRole("link", { name: "Back to billing" }).click();
  await expect(page.getByTestId("invoices-table")).toContainText("Unpaid");
});

test("a Starter school upgrades from Billing: locked menu items open, with an invoice for the rest of the period", async ({
  page,
  request,
}) => {
  const { school, headers, base } = await payingSchool(request, "upgrade", "STARTER");
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  // Starter leaves out transport, library and inventory: the menu offers an upgrade instead.
  const locked = page.getByTestId("nav-locked-library");
  await expect(locked).toContainText("Upgrade");
  await locked.click();
  await expect(page).toHaveURL(/\/app\/billing\?upgrade=library$/);
  await expect(page.getByTestId("billing-upgrade-notice")).toContainText("Library is not in your current plan");

  await page.getByRole("button", { name: "Upgrade: Growth plan" }).click();
  const dialog = page.getByRole("dialog", { name: "Change to Growth" });
  await expect(dialog.getByTestId("plan-change-quote")).toContainText("An invoice for the rest of this period");
  await dialog.getByRole("button", { name: "Change plan" }).click();
  await expect(page.getByText("The school is now on Growth.")).toBeVisible();
  await expect(page.getByTestId("billing-plan-name")).toHaveText("Growth");
  await expect(page.getByTestId("invoices-table").getByRole("row")).toHaveCount(3);
  await expect(page.getByTestId("nav-locked-library")).toHaveCount(0);
  await expect(mainNav(page).getByRole("link", { name: "Library", exact: true })).toBeVisible();

  const account = (await (await request.get(base, { headers })).json()) as {
    school: { plan: string };
    invoices: { kind: string; previousPlan: string | null }[];
  };
  expect(account.school.plan).toBe("GROWTH");
  expect(account.invoices[0]).toMatchObject({ kind: "UPGRADE", previousPlan: "STARTER" });
});

test("a School Admin schedules a downgrade and a cancellation, and takes both back", async ({ page, request }) => {
  const { school, headers, base } = await payingSchool(request, "downgrade");
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/billing");

  await page.getByRole("button", { name: "Downgrade: Starter plan" }).click();
  const dialog = page.getByRole("dialog", { name: "Change to Starter" });
  await expect(dialog.getByTestId("plan-change-quote")).toContainText("when the next period starts");
  await dialog.getByRole("button", { name: "Change plan" }).click();
  await expect(page.getByTestId("billing-pending-plan")).toContainText("Moving to Starter");
  await expect(page.getByTestId("billing-plan-name")).toHaveText("Growth");
  type Account = { subscription: { pendingPlan: string | null; cancelAtPeriodEnd: boolean } };
  let account = (await (await request.get(base, { headers })).json()) as Account;
  expect(account.subscription.pendingPlan).toBe("STARTER");
  await page.getByRole("button", { name: "Keep Growth" }).click();
  await expect(page.getByTestId("billing-pending-plan")).toHaveCount(0);

  await page.getByTestId("billing-cancel").click();
  const cancel = page.getByRole("dialog", { name: "Cancel the subscription" });
  await cancel.getByLabel("Why are you cancelling? (optional)").fill("E2E check");
  await cancel.getByRole("button", { name: "Cancel at period end" }).click();
  await expect(page.getByTestId("billing-cancelled")).toContainText("will not renew");
  account = (await (await request.get(base, { headers })).json()) as Account;
  expect(account.subscription.cancelAtPeriodEnd).toBe(true);
  await page.getByRole("button", { name: "Keep subscription" }).click();
  await expect(page.getByTestId("billing-cancelled")).toHaveCount(0);
  account = (await (await request.get(base, { headers })).json()) as Account;
  // The API leaves out empty members.
  expect(account.subscription.pendingPlan ?? null).toBeNull();
  expect(account.subscription.cancelAtPeriodEnd).toBe(false);
});

test("the Super Admin cancels for a school, sees it on Renewals and Activity, and takes it back", async ({
  page,
  request,
}) => {
  const { school, tenantId, headers, base } = await payingSchool(request, "sacancel");
  await platformLoginViaUi(page);
  await page.goto(`/app/platform/schools/${tenantId}`);
  await page.getByTestId("account-cancel").click();
  const dialog = page.getByRole("dialog", { name: `Cancel ${school.schoolName}'s subscription` });
  await dialog.getByLabel("Reason").fill("Asked by phone (E2E)");
  await dialog.getByRole("button", { name: "Cancel at period end" }).click();
  await expect(page.getByTestId("account-cancellation")).toContainText("Cancelled: ends on");
  await expect(page.getByTestId("account-cancellation")).toContainText("Asked by phone (E2E)");

  await page.goto("/app/platform/billing");
  await page.getByLabel(/^Cancelling \(\d+\)$/).check();
  const row = page.getByTestId("renewals-table").getByRole("row").filter({ hasText: school.schoolName });
  await expect(row).toContainText("Asked by phone (E2E)");

  await page.goto("/app/platform/activity");
  await page.getByLabel("School").selectOption(tenantId);
  await expect(page.getByTestId("activity-table")).toContainText("Asked by phone (E2E) · for the school");

  await page.goto(`/app/platform/schools/${tenantId}`);
  await page.getByTestId("account-resume").click();
  await page.getByRole("dialog").getByRole("button", { name: "Take back cancellation" }).click();
  await expect(page.getByTestId("account-cancellation")).toHaveCount(0);
  const account = (await (await request.get(base, { headers })).json()) as { subscription: { cancelAtPeriodEnd: boolean } };
  expect(account.subscription.cancelAtPeriodEnd).toBe(false);
});

test("a School Admin cancels the free trial and keeps it again", async ({ page, request }) => {
  const school = newSchool("trialcancel");
  await signupViaApi(request, school);
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/billing");
  await page.getByRole("button", { name: "Cancel trial" }).click();
  const dialog = page.getByRole("dialog", { name: "Cancel the free trial" });
  await dialog.getByRole("button", { name: "Cancel trial" }).click();
  await expect(page.getByTestId("billing-cancelled")).toContainText("Trial cancelled");
  await page.getByRole("button", { name: "Keep trial" }).click();
  await expect(page.getByTestId("billing-cancelled")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Cancel trial" })).toBeVisible();
});
