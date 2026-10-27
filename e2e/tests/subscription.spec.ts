import { expect, test, type APIRequestContext } from "@playwright/test";
import { adminCredentials, loginViaApi, loginViaUi, mainNav, newSchool, signupViaApi } from "./support/helpers";

/**
 * A School Admin manages the school's own subscription (docs/api/phase-3-subscriptions.md): pays an open invoice
 * online through the sandbox gateway. Needs the stack's bootstrap Super Admin (E2E_PLATFORM_EMAIL /
 * E2E_PLATFORM_PASSWORD) to start the paid subscription; skipped without them. The stack must run the billing
 * gateway in sandbox mode (the default).
 */
const PLATFORM_EMAIL = process.env.E2E_PLATFORM_EMAIL ?? "";
const PLATFORM_PASSWORD = process.env.E2E_PLATFORM_PASSWORD ?? "";

test.skip(!PLATFORM_EMAIL || !PLATFORM_PASSWORD, "Set E2E_PLATFORM_EMAIL and E2E_PLATFORM_PASSWORD to run");

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
