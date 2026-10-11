import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  PASSWORD,
  signupViaApi,
  type Credentials,
} from "./support/helpers";

const DAY_MS = 86_400_000;

/** Today in India as "YYYY-MM-DD", moved by `days`. */
function indiaDate(days = 0): string {
  return new Date(Date.now() + 330 * 60_000 + days * DAY_MS).toISOString().slice(0, 10);
}

/** A plain date moved by `days`. */
function addDays(date: string, days: number): string {
  return new Date(Date.parse(`${date}T00:00:00Z`) + days * DAY_MS).toISOString().slice(0, 10);
}

const isSunday = (date: string) => new Date(`${date}T00:00:00Z`).getUTCDay() === 0;

/** The Indian academic year (April to March) that contains today. */
function academicYear() {
  const today = indiaDate();
  const year = Number(today.slice(5, 7)) >= 4 ? Number(today.slice(0, 4)) : Number(today.slice(0, 4)) - 1;
  return { startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31`, name: `${year}-${String((year + 1) % 100).padStart(2, "0")}` };
}

/**
 * The library's rules for a student in a new school (no holidays declared): a book is due 14 days after it is lent,
 * or the Monday after when that is a Sunday; each open day (not a Sunday) after that is fined ₹1, up to ₹50.
 */
function lateReturn(issuedOn: string, today: string) {
  let dueOn = addDays(issuedOn, 14);
  while (isSunday(dueOn)) dueOn = addDays(dueOn, 1);
  let openDaysLate = 0;
  for (let d = addDays(dueOn, 1); d <= today; d = addDays(d, 1)) {
    if (!isSunday(d)) openDaysLate++;
  }
  const calendarDaysLate = Math.round((Date.parse(`${today}T00:00:00Z`) - Date.parse(`${dueOn}T00:00:00Z`)) / DAY_MS);
  return { dueOn, openDaysLate, calendarDaysLate, fineRupees: Math.min(openDaysLate, 50) };
}

type Auth = { headers: { Authorization: string } };

function client(request: APIRequestContext, auth: Auth) {
  return async <T = { id: string }>(path: string, data?: unknown, status = 201): Promise<T> => {
    const res = await request.post(path, { ...auth, data });
    expect(res.status(), `POST ${path}: ${await res.text()}`).toBe(status);
    return (await res.json()) as T;
  };
}

/**
 * Through the API: a current year, Class 5 A with Arjun Sharma in it, his mother's sign-in, and the school's
 * librarian.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const post = client(request, auth);
  const librarian: Credentials = { schoolCode, email: `kavitha@${schoolCode}.example.com`, password: PASSWORD };
  await post("/api/users", { name: "Kavitha Rao", email: librarian.email, password: PASSWORD, roles: ["LIBRARIAN"] });

  const year = academicYear();
  await post("/api/academics/years", { ...year, current: true });
  const { id: class5 } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: section5A } = await post(`/api/academics/classes/${class5}/sections`, { name: "A", capacity: 40 });
  const arjun = await post<{ id: string; guardians: { id: string }[] }>("/api/students", {
    admissionNo: "E2E/LIB/1",
    firstName: "Arjun",
    lastName: "Sharma",
    dateOfBirth: "2016-03-12",
    gender: "MALE",
    admissionDate: year.startsOn,
    sectionId: section5A,
    rollNo: 1,
    guardians: [{ name: "Neha Sharma", relation: "MOTHER", phone: "98765 30001", primary: true }],
  });
  const parent: Credentials = { schoolCode, email: `neha@${schoolCode}.example.com`, password: "Parent-temp-2026" };
  await post(`/api/students/${arjun.id}/guardians/${arjun.guardians[0].id}/sign-in`, { mode: "CREATE", email: parent.email, password: parent.password }, 200);
  return { librarian, parent };
}

/** Opens a menu item from the phone's bottom bar, through "More" when it is not one of the first four. */
async function openFromMenu(page: Page, label: string) {
  const bar = page.getByTestId("bottom-bar");
  await expect(bar).toBeVisible();
  const direct = bar.getByRole("link", { name: label, exact: true });
  if ((await direct.count()) > 0) {
    await direct.click();
    return;
  }
  await bar.getByRole("button", { name: "More" }).click();
  await page.getByRole("dialog", { name: "All sections" }).getByRole("link", { name: label, exact: true }).click();
}

test("the librarian adds a title with two copies, lends one by its accession number, takes it back late and collects the fine; the parent sees the loan on a phone", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(150_000);
  const school = newSchool("library");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const { librarian, parent } = await setUpSchool(request, school.schoolCode, { headers: { Authorization: `Bearer ${adminToken}` } });
  const today = indiaDate();
  // A book lent 30 days ago, before the school started using the system, and so already late.
  const issuedOn = indiaDate(-30);
  const late = lateReturn(issuedOn, today);

  // 1. The librarian adds the title with two copies; each gets the next accession number.
  await loginViaUi(page, librarian);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Library", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/library$/);
  const empty = page.getByTestId("catalogue-empty");
  await expect(empty).toBeVisible();
  await empty.getByRole("button", { name: "Add title" }).click();
  const add = page.getByRole("dialog", { name: "Add title" });
  await add.getByLabel("Title", { exact: true }).fill("The Panchatantra");
  await add.getByLabel("Authors", { exact: true }).fill("Vishnu Sharma");
  await add.getByLabel("Number of copies", { exact: true }).fill("2");
  await add.getByLabel("Price per copy (₹)", { exact: true }).fill("180");
  await add.getByRole("button", { name: "Add title" }).click();
  await expect(page.getByText("The Panchatantra added with 2 copies.")).toBeVisible();
  await expect(page).toHaveURL(/\/app\/library\/titles\/[0-9a-f-]+$/);
  await expect(page.getByRole("heading", { name: "The Panchatantra" })).toBeVisible();
  const copies = page.getByTestId("copy-row");
  await expect(copies).toHaveCount(2);
  await expect(copies.nth(0)).toContainText("LIB00001");
  await expect(copies.nth(1)).toContainText("LIB00002");
  await expect(copies.nth(0)).toContainText("₹180");
  await expectNoHorizontalScroll(page);

  // 2. At the desk she finds Arjun and lends the first copy by typing its number and pressing Enter, as a scanner does.
  await page.goto("/app/library/desk");
  await expect(page.getByRole("heading", { name: "Circulation desk" })).toBeVisible();
  await page.getByRole("searchbox", { name: "Find a student or member of staff" }).fill("Arjun");
  await page.getByRole("button", { name: /Arjun Sharma/ }).click();
  await expect(page.getByTestId("desk-member-name")).toHaveText("Arjun Sharma");
  await expect(page.getByTestId("desk-rule")).toContainText("Up to 2 books for 14 days.");
  await page.getByRole("checkbox", { name: "Record an earlier issue date" }).check();
  await page.getByLabel("Lent on", { exact: true }).fill(issuedOn);
  const lend = page.getByTestId("issue-form").getByLabel("Accession number", { exact: true });
  await lend.pressSequentially("LIB00001");
  await lend.press("Enter");
  await expect(page.getByText(/^The Panchatantra lent, due /)).toBeVisible();
  await expect(lend).toHaveValue("");
  await expect(lend).toBeFocused();
  const loans = page.getByTestId("desk-loans");
  await expect(loans).toContainText("The Panchatantra");
  await expect(loans).toContainText(`${late.calendarDaysLate} days late`);
  await expect(loans).toContainText(`Fine so far ₹${late.fineRupees}`);

  // 3. His mother, on her phone, sees the book at home and how late it is.
  const phone = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const parentPage = await phone.newPage();
  await loginViaUi(parentPage, parent);
  await expect(parentPage).toHaveURL(/\/app\/dashboard$/);
  await openFromMenu(parentPage, "Library");
  await expect(parentPage).toHaveURL(/\/app\/family\/library$/);
  const member = parentPage.getByTestId("family-library-member");
  await expect(member).toContainText("Arjun Sharma");
  const book = member.getByTestId("family-library-loan");
  await expect(book).toHaveCount(1);
  await expect(book).toContainText("The Panchatantra");
  await expect(book).toContainText(`${late.calendarDaysLate} days late`);
  await expect(book).toContainText(`Fine so far ₹${late.fineRupees}`);
  await expectNoHorizontalScroll(parentPage);
  // The parent cannot reach the desk.
  const parentAuth = { headers: { Authorization: `Bearer ${await loginViaApi(request, parent)}` } };
  expect((await request.get("/api/library/loans", parentAuth)).status()).toBe(403);

  // 4. The book comes back late: the fine is raised and collected in cash, with a receipt.
  const giveBack = page.getByTestId("return-form").getByLabel("Accession number", { exact: true });
  await giveBack.pressSequentially("LIB00001");
  await giveBack.press("Enter");
  const result = page.getByTestId("return-result");
  await expect(result).toContainText("The Panchatantra (LIB00001) back from Arjun Sharma.");
  await expect(result).toContainText(`Late return · ${late.openDaysLate} days late`);
  await expect(giveBack).toBeFocused();
  await result.getByRole("button", { name: `Collect ₹${late.fineRupees}` }).click();
  const collect = page.getByRole("dialog", { name: "Collect fines from Arjun Sharma" });
  await expect(collect.getByTestId("collect-total")).toContainText(`₹${late.fineRupees}`);
  await expect(collect.getByLabel("Paid by", { exact: true })).toHaveValue("CASH");
  await collect.getByRole("button", { name: `Collect ₹${late.fineRupees}` }).click();
  const receiptLine = page.getByTestId("return-receipt");
  await expect(receiptLine).toContainText(/receipt LIBF\/\d{4}-\d{2}\/000001/);
  await page.getByRole("link", { name: "View receipt" }).click();
  await expect(page).toHaveURL(/\/app\/library\/receipt\?no=LIBF/);
  const receipt = page.getByTestId("library-receipt");
  await expect(receipt.getByTestId("library-receipt-no")).toHaveText(/^LIBF\/\d{4}-\d{2}\/000001$/);
  await expect(receipt).toContainText("Arjun Sharma");
  await expect(receipt.getByTestId("library-receipt-total")).toHaveText(`₹${late.fineRupees}`);

  // The copy is back on the shelf and the parent sees the paid fine with its receipt.
  await parentPage.reload();
  await expect(parentPage.getByTestId("family-library-empty")).toHaveText("No library books at home.");
  await expect(parentPage.getByTestId("family-library-fines")).toContainText("LIBF/");
  await expectNoHorizontalScroll(parentPage);
  await phone.close();
});
