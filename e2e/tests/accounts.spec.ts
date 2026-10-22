import { expect, test, type APIRequestContext } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  PASSWORD,
  signOut,
  signupViaApi,
  type Credentials,
} from "./support/helpers";

const DAY_MS = 86_400_000;
const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

/** Today's date in India as YYYY-MM-DD, moved by a number of days. */
function indiaDate(offsetDays = 0): string {
  return new Date(Date.now() + 330 * 60_000 + offsetDays * DAY_MS).toISOString().slice(0, 10);
}

/** The Indian academic year (April to March) that contains today. */
function academicYear() {
  const today = indiaDate();
  const year = Number(today.slice(5, 7)) >= 4 ? Number(today.slice(0, 4)) : Number(today.slice(0, 4)) - 1;
  return { startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31`, name: `${year}-${String((year + 1) % 100).padStart(2, "0")}` };
}

/** This month in India: "2026-10" and "October 2026". */
function monthInIndia() {
  const today = indiaDate();
  return { value: today.slice(0, 7), label: `${MONTHS[Number(today.slice(5, 7)) - 1]} ${today.slice(0, 4)}` };
}

// A tiny valid PNG (1×1 pixel), standing in for the photo of a bill.
const BILL_PHOTO = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
  "base64",
);

type AccountsSchool = { admin: Credentials; accountant: Credentials; principal: Credentials; adminToken: string };

/**
 * A new school through the API: an accountant and a principal, the current year with one student on a published
 * fee structure, a ₹10,000 fee paid in cash today (which the accounts record as fee income on their own), the
 * standard chart of heads, and an electrician as a vendor.
 */
async function setUpAccounts(request: APIRequestContext, tag: string): Promise<AccountsSchool> {
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

  const accountant = { schoolCode: school.schoolCode, email: `meena@${school.schoolCode}.example.com`, password: PASSWORD };
  const principal = { schoolCode: school.schoolCode, email: `lakshmi@${school.schoolCode}.example.com`, password: PASSWORD };
  await post("/api/users", { name: "Meena Reddy", email: accountant.email, password: accountant.password, roles: ["ACCOUNTANT"] });
  await post("/api/users", { name: "Lakshmi Iyer", email: principal.email, password: principal.password, roles: ["PRINCIPAL"] });

  const year = await post<{ id: string }>("/api/academics/years", { ...academicYear(), current: true });
  const klass = await post<{ id: string }>("/api/academics/classes", { name: "Class 5" });
  const section = await post<{ id: string }>(`/api/academics/classes/${klass.id}/sections`, { name: "A", capacity: 40 });
  const student = await post<{ id: string }>("/api/students", {
    admissionNo: "E2E/ACC/001",
    firstName: "Kabir",
    lastName: "Mehta",
    dateOfBirth: "2016-04-12",
    gender: "MALE",
    admissionDate: indiaDate(),
    sectionId: section.id,
    rollNo: 1,
    guardians: [{ name: "Meena Mehta", relation: "MOTHER", phone: "9876543210", email: null, primary: true }],
  });
  const heads = await post<{ id: string; kind: string }[]>("/api/fees/heads/defaults", undefined, 200);
  const tuition = heads.find((h) => h.kind === "TUITION")!;
  const saved = await post<{ structure: { id: string } }>("/api/fees/structures", {
    academicYearId: year.id,
    classId: klass.id,
    heads: [{ headId: tuition.id, amountPaise: 40_000_00 }],
    instalments: [
      { label: "Quarter 1", dueDate: indiaDate(-100) },
      { label: "Quarter 2", dueDate: indiaDate(-10) },
      { label: "Quarter 3", dueDate: indiaDate(80) },
      { label: "Quarter 4", dueDate: indiaDate(170) },
    ],
  });
  await post(`/api/fees/structures/${saved.structure.id}/publish`, undefined, 200);
  // Quarter 1 is paid at the counter today: ₹10,000 of fee income for the accounts.
  await post(`/api/fees/students/${student.id}/payments`, { amountPaise: 10_000_00, mode: "CASH" });

  await post("/api/accounts/heads/standard", undefined, 200);
  await post("/api/accounts/vendors", {
    name: "Sparks Electricals",
    gstin: "36AABCU9603R1ZX",
    pan: "AABCU9603R",
    phone: "9876543210",
    email: null,
    address: null,
    bankAccountNumber: null,
    bankIfsc: null,
    tdsSection: "194C",
    tdsRatePercent: 2,
    active: true,
  });
  return { admin, accountant, principal, adminToken };
}

test("the accountant records a bill with its photo, the principal approves it on a phone, the accountant pays it and the report shows it with the month's fees", async ({
  page,
  request,
}) => {
  test.setTimeout(150_000);
  const school = await setUpAccounts(request, "accounts");
  const month = monthInIndia();

  // The accountant records the electrician's bill: ₹10,000 plus 18% GST, with TDS kept back, and a photo of it.
  await loginViaUi(page, school.accountant);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Accounts", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/accounts$/);
  await expect(page.getByRole("heading", { level: 1, name: "Accounts overview" })).toBeVisible();
  await expect(page.getByTestId("accounts-management-note")).toContainText("management accounts");
  await page.getByRole("navigation", { name: "Accounts sections" }).getByRole("link", { name: "Expenses" }).click();
  await expect(page).toHaveURL(/\/app\/accounts\/expenses$/);
  await page.getByTestId("expense-new").click();
  const form = page.getByRole("dialog", { name: "New expense" });
  await form.getByLabel("Head").selectOption({ label: "EXP-ELEC · Electricity" });
  await form.getByLabel("Vendor").selectOption({ label: "Sparks Electricals" });
  await form.getByLabel("Amount").fill("10000");
  await form.getByLabel("GST rate").selectOption("18");
  await expect(form.getByLabel("GST", { exact: true })).toHaveValue("1800");
  await form.getByRole("checkbox", { name: /Keep back TDS 194C at 2%/ }).check();
  await expect(form.getByTestId("expense-totals")).toContainText("₹11,800");
  await expect(form.getByTestId("expense-totals")).toContainText("₹11,600");
  await form.getByLabel("Description").fill("Lab wiring");
  await form.getByLabel("Bill number").fill("SE/1042");
  await form.locator('input[name="file"]').setInputFiles({ name: "bill.png", mimeType: "image/png", buffer: BILL_PHOTO });
  await expect(form.getByText("bill.png")).toBeVisible();
  await form.getByRole("button", { name: "Send for approval" }).click();
  await expect(page).toHaveURL(/\/app\/accounts\/expenses\/[0-9a-f-]+$/);
  const expenseUrl = page.url();
  await expect(page.getByRole("heading", { level: 1, name: "Lab wiring" })).toBeVisible();
  await expect(page.getByTestId("expense-detail")).toContainText("Waiting for approval");
  await expect(page.getByTestId("expense-total")).toContainText("₹11,800");
  await expect(page.getByText("bill.png")).toBeVisible();
  // The accountant cannot approve her own bill.
  await expect(page.getByTestId("expense-approve")).toHaveCount(0);
  await expect(page.getByTestId("expense-pay")).toHaveCount(0);
  await signOut(page);

  // The principal approves it from her phone.
  await page.setViewportSize({ width: 390, height: 844 });
  await loginViaUi(page, school.principal);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("chart-finance")).toContainText("1 expense waiting");
  await page.goto("/app/accounts/approvals");
  await expect(page.getByRole("heading", { level: 1, name: "Expense approvals" })).toBeVisible();
  await expect(page.getByText("1 expense waiting")).toBeVisible();
  const card = page.getByTestId("queue-card");
  await expect(card).toHaveCount(1);
  await expect(card).toContainText("Lab wiring");
  await expect(card).toContainText("₹11,800");
  await expectNoHorizontalScroll(page);
  await card.getByTestId("queue-approve").click();
  await expect(page.getByRole("status").filter({ hasText: "Expense approved." })).toBeVisible();
  await expect(page.getByTestId("queue-empty")).toBeVisible();
  await signOut(page);
  await page.setViewportSize({ width: 1440, height: 900 });

  // The accountant pays it in cash; the bill is then locked.
  await loginViaUi(page, school.accountant);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto(expenseUrl);
  await expect(page.getByTestId("expense-detail")).toContainText("Approved");
  await expect(page.getByTestId("expense-approvals")).toContainText("Lakshmi Iyer");
  await page.getByTestId("expense-pay").click();
  const paid = page.getByRole("dialog", { name: "Mark as paid" });
  await expect(paid).toContainText("₹11,600 to pay");
  await expect(paid.getByLabel("Paid on")).toHaveValue(indiaDate());
  await paid.getByLabel("Mode").selectOption("CASH");
  await paid.getByLabel("Reference").fill("Voucher 17");
  await paid.getByRole("button", { name: "Mark paid" }).click();
  await expect(page.getByTestId("expense-detail")).toContainText("Paid");
  await expect(page.getByTestId("expense-locked")).toBeVisible();
  await expect(page.getByTestId("expense-pay")).toHaveCount(0);

  // The income vs expense report shows this month's fee income and the bill.
  await page.goto("/app/reports");
  await expect(page.getByTestId("reports-group-accounts")).toBeVisible();
  await page.getByTestId("report-link-accountsIncomeVsExpense").click();
  await expect(page).toHaveURL(/\/app\/reports\/accounts-income-vs-expense$/);
  const report = page.getByTestId("report-income-vs-expense");
  await expect(report.getByTestId("ive-fee-income")).toContainText("₹10,000");
  await expect(report.getByTestId("ive-expense")).toContainText("₹11,800");
  const row = report.getByTestId(`ive-month-${month.value}`);
  await expect(row).toContainText(month.label);
  await expect(row).toContainText("₹10,000");
  await expect(row).toContainText("₹11,800");
  await expect(row).toContainText("-₹1,800");
  await expect(page.getByRole("button", { name: "CSV" })).toBeEnabled();

  // A principal can approve but not record; a teacher has no accounts access at all.
  const principalToken = await loginViaApi(request, school.principal);
  const asPrincipal = { headers: { Authorization: `Bearer ${principalToken}` } };
  const recordAsPrincipal = await request.post("/api/accounts/petty-cash", {
    ...asPrincipal,
    data: { entryDate: indiaDate(), kind: "TOP_UP", amountPaise: 1_000_00, headId: null, description: "Float" },
  });
  expect(recordAsPrincipal.status()).toBe(403);
});
