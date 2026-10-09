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
} from "./support/helpers";

/** "YYYY-MM-DD" of a UTC date. */
const iso = (date: Date) => date.toISOString().slice(0, 10);

/** Today in India as a UTC-midnight date. */
function todayInIndia(): Date {
  const india = new Date(Date.now() + 330 * 60_000);
  return new Date(Date.UTC(india.getUTCFullYear(), india.getUTCMonth(), india.getUTCDate()));
}

/** The Indian academic year (April to March) that contains today. */
function academicYearAround(today: Date) {
  const year = today.getUTCMonth() >= 3 ? today.getUTCFullYear() : today.getUTCFullYear() - 1;
  return {
    startsOn: `${year}-04-01`,
    endsOn: `${year + 1}-03-31`,
    name: `${year}-${String((year + 1) % 100).padStart(2, "0")}`,
  };
}

const addDays = (date: Date, days: number) => new Date(date.getTime() + days * 86_400_000);

/**
 * A Monday and Tuesday (two working days) inside the academic year that do not include today: the
 * next such pair at least three days ahead, or the one before last when the year ends too soon.
 */
function twoWorkingDays(today: Date, endsOn: string): { from: string; to: string } {
  let monday = addDays(today, 3);
  while (monday.getUTCDay() !== 1) monday = addDays(monday, 1);
  if (iso(addDays(monday, 1)) > endsOn) {
    monday = addDays(today, -8);
    while (monday.getUTCDay() !== 1) monday = addDays(monday, -1);
  }
  return { from: iso(monday), to: iso(addDays(monday, 1)) };
}

type Auth = { headers: { Authorization: string } };

async function send<T>(request: APIRequestContext, method: "post" | "put", path: string, auth: Auth, data?: unknown) {
  const res = await request[method](path, { ...auth, data });
  expect(res.status(), `${path}: ${await res.text()}`).toBeLessThan(300);
  return (await res.json()) as T;
}

test("a teacher checks in and out, applies for leave, the principal approves it and the balance goes down", async ({
  page,
  request,
}) => {
  test.setTimeout(150_000);
  const school = newSchool("staff");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin = { headers: { Authorization: `Bearer ${adminToken}` } };

  // Through the API: the current year, the usual leave types, a teacher and a principal (sign-in and profile).
  const today = todayInIndia();
  const year = academicYearAround(today);
  await send(request, "post", "/api/academics/years", asAdmin, { ...year, current: true });
  await send(request, "post", "/api/leave/types/standard", asAdmin);
  const teacher = { name: "Neha Gupta", email: `neha@${school.schoolCode}.example.com`, password: PASSWORD };
  const principal = { name: "Meena Iyer", email: `meena@${school.schoolCode}.example.com`, password: PASSWORD };
  await send(request, "post", "/api/staff", asAdmin, {
    ...teacher,
    roles: ["TEACHER"],
    employeeCode: "E2E-101",
    designation: "TGT English",
    departmentId: null,
    employmentType: "PERMANENT",
    dateOfJoining: "2020-06-01",
    mobile: "98480 12345",
  });
  await send(request, "post", "/api/staff", asAdmin, {
    ...principal,
    roles: ["PRINCIPAL"],
    employeeCode: "E2E-002",
    designation: "Principal",
    departmentId: null,
    employmentType: "PERMANENT",
    dateOfJoining: "2018-04-01",
    mobile: "98480 12346",
  });
  const { from, to } = twoWorkingDays(today, year.endsOn);

  // The teacher checks in and out from the dashboard.
  await loginViaUi(page, { schoolCode: school.schoolCode, email: teacher.email, password: teacher.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const card = page.getByTestId("check-in-card");
  await expect(card.getByTestId("check-in-state")).toHaveText("Not checked in yet");
  await card.getByLabel("Note (optional)").fill("Reached early for assembly");
  await card.getByRole("button", { name: "Check in" }).click();
  await expect(card.getByTestId("check-in-state")).toContainText("Checked in at");
  await expect(card.getByTestId("check-in-times")).toContainText("Reached early for assembly");
  await card.getByRole("button", { name: "Check out" }).click();
  await expect(card.getByTestId("check-in-state")).toHaveText("Checked in and out");
  await expect(card.getByRole("button", { name: /^Check (in|out)$/ })).toHaveCount(0);

  // The teacher applies for two days of casual leave and sees the working days and balance after.
  await mainNav(page).getByRole("link", { name: "Leave", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/leave$/);
  await expect(page.getByTestId("balance-CL").getByTestId("balance-available")).toHaveText("12");
  await page.getByTestId("apply-leave").click();
  const dialog = page.getByRole("dialog", { name: "Apply for leave" });
  await dialog.getByLabel("Leave type").selectOption({ label: "Casual leave · 12 left" });
  await dialog.getByLabel("From").fill(from);
  await dialog.getByLabel("To").fill(to);
  const preview = dialog.getByTestId("leave-preview");
  await expect(preview).toContainText("2 days of leave");
  await expect(preview.getByTestId("leave-preview-balance")).toHaveText(
    "Balance now 12, waiting for approval 0, after this 10.",
  );
  await dialog.getByLabel("Reason").fill("Sister's wedding");
  await dialog.getByRole("button", { name: "Apply" }).click();
  await expect(page.getByText("Leave applied for. It goes to the School Admin or Principal.")).toBeVisible();
  const mine = page.getByTestId("my-requests").getByTestId("leave-request");
  await expect(mine).toHaveCount(1);
  await expect(mine).toContainText("Waiting");
  await signOut(page);

  // The principal sees it waiting on the dashboard and approves it.
  await loginViaUi(page, { schoolCode: school.schoolCode, email: principal.email, password: principal.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const waiting = page.getByTestId("leave-inbox-card");
  await expect(waiting.getByTestId("leave-inbox-count")).toHaveText("1");
  await expect(waiting).toContainText("Neha Gupta");
  await waiting.getByRole("link", { name: "Review requests" }).click();
  await expect(page).toHaveURL(/\/app\/leave\?tab=inbox$/);
  const inbox = page.getByTestId("leave-inbox");
  await expect(inbox.getByTestId("leave-request")).toContainText("12 days left before this");
  await inbox.getByRole("button", { name: "Approve leave of Neha Gupta" }).click();
  const approve = page.getByRole("dialog", { name: "Approve leave" });
  await approve.getByLabel("Comment (optional)").fill("Enjoy the wedding");
  await approve.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByText("Leave of Neha Gupta approved.")).toBeVisible();
  await expect(page.getByText("Nothing is waiting for you.")).toBeVisible();
  await signOut(page);

  // The teacher sees the approval and the balance after it, on a phone-sized screen.
  await page.setViewportSize({ width: 390, height: 844 });
  await loginViaUi(page, { schoolCode: school.schoolCode, email: teacher.email, password: teacher.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/leave");
  await expect(page.getByTestId("balance-CL").getByTestId("balance-available")).toHaveText("10");
  const approved = page.getByTestId("my-requests").getByTestId("leave-request");
  await expect(approved).toContainText("Approved");
  await expect(approved).toContainText("Approved by Meena Iyer");
  await expect(approved).toContainText("Enjoy the wedding");
  await expectNoHorizontalScroll(page);
  await signOut(page);

  // The admin's daily sheet shows the teacher's own check-in for today.
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Staff attendance" }).click();
  await expect(page).toHaveURL(/\/app\/staff-attendance$/);
  const row = page.getByTestId("staff-sheet-row").filter({ hasText: "Neha Gupta" });
  await expect(row).toContainText("self check-in");
  await expect(row.getByRole("button", { name: "Present" })).toHaveAttribute("aria-pressed", "true");
});
