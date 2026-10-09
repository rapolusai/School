import { expect, test, type APIRequestContext } from "@playwright/test";
import {
  adminCredentials,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  PASSWORD,
  signupViaApi,
  type Credentials,
} from "./support/helpers";

const DAY_MS = 86_400_000;

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

const RECEIPT_NO = /^RCPT\/\d{4}-\d{2}\/\d{6}$/;

type FeesSchool = {
  admin: Credentials;
  accountant: Credentials;
  parent: Credentials;
  adminToken: string;
  studentId: string;
};

/**
 * A new school through the API: the current year, Class 5 A, one student whose mother has a sign-in,
 * an accountant, the default fee heads and a published Class 5 structure of ₹40,000 tuition in four
 * quarterly instalments. Quarter 1 and Quarter 2 are already past their due dates; Quarters 3 and 4 are
 * months away.
 */
async function setUpFees(request: APIRequestContext, tag: string): Promise<FeesSchool> {
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

  const year = await post<{ id: string }>("/api/academics/years", { ...academicYear(), current: true });
  const klass = await post<{ id: string }>("/api/academics/classes", { name: "Class 5" });
  const section = await post<{ id: string }>(`/api/academics/classes/${klass.id}/sections`, { name: "A", capacity: 40 });

  const parent = { schoolCode: school.schoolCode, email: `meena@${school.schoolCode}.example.com`, password: "Parent-temp-2026" };
  const student = await post<{ id: string; guardians: { id: string }[] }>("/api/students", {
    admissionNo: "E2E/FEES/001",
    firstName: "Kabir",
    lastName: "Mehta",
    dateOfBirth: "2016-04-12",
    gender: "MALE",
    admissionDate: indiaDate(),
    sectionId: section.id,
    rollNo: 1,
    guardians: [{ name: "Meena Mehta", relation: "MOTHER", phone: "9876543210", email: parent.email, primary: true }],
  });
  await post(`/api/students/${student.id}/guardians/${student.guardians[0].id}/sign-in`, { mode: "CREATE", ...parent }, 200);

  const accountant = { schoolCode: school.schoolCode, email: `ravi@${school.schoolCode}.example.com`, password: PASSWORD };
  await post("/api/users", { name: "Ravi Kumar", email: accountant.email, password: accountant.password, roles: ["ACCOUNTANT"] });

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
  const published = await post<{ dues: { studentsCreated: number } }>(
    `/api/fees/structures/${saved.structure.id}/publish`,
    undefined,
    200,
  );
  expect(published.dues.studentsCreated).toBe(1);

  return {
    admin,
    accountant,
    parent,
    adminToken,
    studentId: student.id,
  };
}

test("an accountant collects a cash fee at the counter and prints the receipt", async ({ page, request }) => {
  const school = await setUpFees(request, "fee-cash");
  // Printing opens the browser's dialog; count the calls instead.
  await page.addInitScript(() => {
    (window as unknown as { printed: number }).printed = 0;
    window.print = () => {
      (window as unknown as { printed: number }).printed += 1;
    };
  });

  await loginViaUi(page, school.accountant);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Fees", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/fees$/);
  await expect(page.getByTestId("fees-kpis")).toContainText("₹20,000");

  const sections = page.getByRole("navigation", { name: "Fees sections" });
  await sections.getByRole("link", { name: "Collect" }).click();
  await expect(page).toHaveURL(/\/app\/fees\/collect$/);
  await page.getByPlaceholder("Search by student, admission number or parent").fill("E2E/FEES");
  await page.getByRole("button", { name: /Kabir Mehta/ }).click();
  await expect(page.getByRole("heading", { name: "Kabir Mehta" })).toBeVisible();

  // Both overdue quarters are suggested; the parent pays Quarter 1 only, in cash.
  const amount = page.getByLabel("Amount received (₹)");
  await expect(amount).toHaveValue("20000");
  const dues = page.getByTestId("dues-table");
  await dues.getByRole("checkbox", { name: "Pay Quarter 1" }).check();
  await expect(amount).toHaveValue("10000");
  await page.getByRole("radio", { name: "Cash" }).check();
  await page.getByRole("button", { name: "Collect ₹10,000" }).click();

  const receipt = page.getByTestId("receipt");
  await expect(receipt).toBeVisible();
  const receiptNo = (await receipt.getByTestId("receipt-no").innerText()).trim();
  expect(receiptNo).toMatch(RECEIPT_NO);
  expect(receiptNo.endsWith("/000001")).toBe(true);
  await expect(page.getByRole("status").filter({ hasText: "is saved" })).toHaveText(
    `Receipt ${receiptNo} for ₹10,000 is saved.`,
  );
  await expect(receipt).toContainText("Kabir Mehta");
  await expect(receipt).toContainText("Quarter 1 · Tuition fee");
  await expect(receipt).toContainText("Rupees Ten Thousand Only");
  await expect(receipt).toContainText("Cash");

  await page.getByRole("button", { name: "Print", exact: true }).click();
  expect(await page.evaluate(() => (window as unknown as { printed: number }).printed)).toBe(1);
  // On paper only the receipt is printed: no navigation, no buttons.
  await page.emulateMedia({ media: "print" });
  await expect(receipt).toBeVisible();
  await expect(mainNav(page)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Print", exact: true })).toBeHidden();
  await page.emulateMedia({ media: "screen" });

  // The receipt is in the register and can be reopened.
  await sections.getByRole("link", { name: "Receipts" }).click();
  await expect(page).toHaveURL(/\/app\/fees\/receipts$/);
  const register = page.getByTestId("receipts-table");
  await expect(register.getByRole("row").filter({ hasText: receiptNo })).toContainText("₹10,000");
  await register.getByRole("link", { name: receiptNo }).click();
  await expect(page).toHaveURL(/\/app\/fees\/receipts\/[0-9a-f-]{36}$/);
  await expect(page.getByTestId("receipt-no")).toHaveText(receiptNo);

  // Quarter 1 is paid; Quarter 2 is still owed.
  const asAccountant = { headers: { Authorization: `Bearer ${await loginViaApi(request, school.accountant)}` } };
  const fees = (await (await request.get(`/api/fees/students/${school.studentId}`, asAccountant)).json()) as {
    instalments: { label: string; status: string }[];
  };
  expect(fees.instalments.map((i) => [i.label, i.status])).toEqual([
    ["Quarter 1", "PAID"],
    ["Quarter 2", "OVERDUE"],
    ["Quarter 3", "UPCOMING"],
    ["Quarter 4", "UPCOMING"],
  ]);
});

test("a parent pays an overdue quarter online through the sandbox and sees the receipt", async ({ page, request }) => {
  const school = await setUpFees(request, "fee-online");
  // Quarter 1 was paid at the counter.
  const counter = await request.post(`/api/fees/students/${school.studentId}/payments`, {
    headers: { Authorization: `Bearer ${school.adminToken}` },
    data: { amountPaise: 10_000_00, mode: "CASH" },
  });
  expect(counter.status(), await counter.text()).toBe(201);

  await loginViaUi(page, school.parent);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const card = page.getByTestId("child-card");
  await expect(card).toContainText("Kabir Mehta");
  await expect(card.getByTestId("child-fees")).toContainText("₹10,000 overdue");
  await card.getByRole("link", { name: "Pay fees" }).click();
  await expect(page).toHaveURL(new RegExp(`/app/children/${school.studentId}/fees$`));
  await expect(page.getByRole("heading", { name: "Kabir Mehta" })).toBeVisible();

  // Quarter 2 is ticked; Quarter 1 is paid and Quarters 3 and 4 can wait.
  const dues = page.getByTestId("dues-table");
  await expect(dues.getByRole("checkbox", { name: "Pay Quarter 2" })).toBeChecked();
  await expect(dues.getByRole("checkbox", { name: "Pay Quarter 1" })).toHaveCount(0);
  await expect(dues.getByRole("checkbox", { name: "Pay Quarter 3" })).not.toBeChecked();
  await page.getByRole("button", { name: "Pay ₹10,000 online" }).click();

  // The sandbox checkout stands in for the payment gateway.
  await expect(page).toHaveURL(/\/app\/pay\/sandbox\/[A-Za-z0-9_]+$/);
  await expect(page.getByTestId("sandbox-amount")).toHaveText("₹10,000");
  await expect(page.getByTestId("sandbox-checkout")).toContainText("Kabir Mehta");
  await page.getByRole("button", { name: "Payment succeeds" }).click();

  await expect(page).toHaveURL(
    new RegExp(`/app/children/${school.studentId}/fees/receipts/[0-9a-f-]{36}\\?paid=1$`),
  );
  const receipt = page.getByTestId("receipt");
  const receiptNo = (await receipt.getByTestId("receipt-no").innerText()).trim();
  expect(receiptNo).toMatch(RECEIPT_NO);
  expect(receiptNo.endsWith("/000002")).toBe(true);
  await expect(page.getByTestId("payment-success")).toHaveText(
    `Payment of ₹10,000 received. Receipt ${receiptNo} is below.`,
  );
  await expect(receipt).toContainText("Quarter 2 · Tuition fee");
  await expect(receipt).toContainText("Rupees Ten Thousand Only");
  await expect(receipt).toContainText("Online");
  await expect(page.getByRole("button", { name: "Print or save as PDF" })).toBeVisible();

  // The parent's fees now show Quarter 2 paid and both receipts; another student stays out of reach.
  const parentToken = await loginViaApi(request, school.parent);
  const asParent = { headers: { Authorization: `Bearer ${parentToken}` } };
  const fees = (await (await request.get(`/api/me/children/${school.studentId}/fees`, asParent)).json()) as {
    instalments: { label: string; status: string }[];
    receipts: { receiptNo: string }[];
  };
  expect(fees.instalments.map((i) => i.status)).toEqual(["PAID", "PAID", "UPCOMING", "UPCOMING"]);
  expect(fees.receipts.map((r) => r.receiptNo)).toContain(receiptNo);
  const stranger = "00000000-0000-4000-8000-000000000000";
  expect((await request.get(`/api/me/children/${stranger}/fees`, asParent)).status()).toBe(404);
  expect((await request.get("/api/fees/receipts", asParent)).status()).toBe(403);
});
