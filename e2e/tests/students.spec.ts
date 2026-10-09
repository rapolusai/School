import { expect, test, type Page } from "@playwright/test";
import {
  adminCredentials,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  signOut,
  signupViaApi,
} from "./support/helpers";

/** The Indian academic year (April to March) that contains today, e.g. 2026-04-01 to 2027-03-31. */
function academicYearAround(now: Date) {
  const india = new Date(now.getTime() + 330 * 60_000);
  const year = india.getUTCMonth() >= 3 ? india.getUTCFullYear() : india.getUTCFullYear() - 1;
  return {
    startsOn: `${year}-04-01`,
    endsOn: `${year + 1}-03-31`,
    name: `${year}-${String((year + 1) % 100).padStart(2, "0")}`,
  };
}

/** School setup through the UI: the current year, Class 5 and its section A. */
async function setUpSchool(page: Page) {
  const year = academicYearAround(new Date());
  await mainNav(page).getByRole("link", { name: "School setup" }).click();
  await expect(page).toHaveURL(/\/app\/setup$/);

  await page.getByRole("button", { name: "Add year" }).click();
  const yearDialog = page.getByRole("dialog", { name: "Add year" });
  await yearDialog.getByLabel("Starts on").fill(year.startsOn);
  await yearDialog.getByLabel("Ends on").fill(year.endsOn);
  // The first year becomes the current one unless the admin unticks it.
  await expect(yearDialog.getByRole("checkbox", { name: "Make this the current year" })).toBeChecked();
  await yearDialog.getByRole("button", { name: "Save" }).click();
  await expect(yearDialog).toBeHidden();
  const years = page.getByTestId("years-list");
  await expect(years).toContainText(year.name);
  await expect(years).toContainText("Current");

  await page.getByRole("tab", { name: "Classes & sections" }).click();
  await page.getByRole("button", { name: "Add class" }).click();
  const classDialog = page.getByRole("dialog", { name: "Add class" });
  await classDialog.getByLabel("Class name").fill("Class 5");
  await classDialog.getByRole("button", { name: "Save" }).click();
  await expect(classDialog).toBeHidden();

  const classCard = page.getByRole("article", { name: "Class 5" });
  await classCard.getByRole("button", { name: "Add section" }).click();
  const sectionDialog = page.getByRole("dialog", { name: "Add a section to Class 5" });
  await sectionDialog.getByLabel("Section", { exact: true }).fill("A");
  await sectionDialog.getByLabel("Seats").fill("40");
  await sectionDialog.getByRole("button", { name: "Save" }).click();
  await expect(sectionDialog).toBeHidden();
  await expect(classCard).toContainText("Section A");
  await expect(classCard).toContainText("0 of 40");
  return year;
}

test("an admin sets up the school, admits a student and gives the parent a sign-in", async ({ page, request }) => {
  const school = newSchool("stud");
  await signupViaApi(request, school);
  const parent = {
    name: "Meena Mehta",
    email: `meena@${school.schoolCode}.example.com`,
    password: "Parent-temp-2026",
  };

  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await setUpSchool(page);

  // Admit a student through the form.
  await mainNav(page).getByRole("link", { name: "Students", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/students$/);
  await expect(page.getByTestId("students-empty")).toBeVisible();
  await page.getByRole("button", { name: "Add student" }).first().click();
  const dialog = page.getByRole("dialog", { name: "Admit a student" });
  await dialog.getByLabel("First name").fill("Kabir");
  await dialog.getByLabel("Last name").fill("Mehta");
  await dialog.getByLabel("Admission number").fill("E2E/2026/001");
  await dialog.getByLabel("Date of birth").fill("2016-04-12");
  await dialog.getByLabel("Gender").selectOption("MALE");
  await dialog.getByLabel("Class and section").selectOption({ label: "Class 5 A · 0 of 40" });
  await dialog.getByLabel("Roll number").fill("1");
  await dialog.getByLabel("Name", { exact: true }).fill(parent.name);
  await dialog.getByLabel("Relation").selectOption("MOTHER");
  await dialog.getByLabel("Mobile number").fill("98765 43210");
  await dialog.getByLabel("Email", { exact: true }).fill(parent.email);
  await dialog.getByRole("button", { name: "Admit student" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("Kabir Mehta has been admitted.")).toBeVisible();

  // Search finds the student by admission number; a search with no match says so.
  const table = page.getByTestId("students-table");
  const search = page.getByRole("searchbox", { name: "Search by name or admission number" });
  await search.fill("nobody-here");
  await expect(page.getByText("No students match these filters.")).toBeVisible();
  await search.fill("E2E/2026");
  await expect(table.getByRole("row").filter({ hasText: "Kabir Mehta" })).toContainText("Class 5 A");
  await expect(table).toContainText("98765 43210");

  // The student's page.
  await table.getByRole("link", { name: /Kabir Mehta/ }).click();
  await expect(page).toHaveURL(/\/app\/students\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Kabir Mehta");
  await expect(page.getByText("Class 5 A · Roll 1 · E2E/2026/001")).toBeVisible();
  await expect(page.getByText("12 Apr 2016")).toBeVisible();
  const studentId = page.url().split("/").pop()!;

  // Give the mother a sign-in.
  await page.getByRole("button", { name: `Give ${parent.name} a sign-in` }).click();
  const signIn = page.getByRole("dialog", { name: `Sign-in for ${parent.name}` });
  await expect(signIn.getByLabel("Email", { exact: true })).toHaveValue(parent.email);
  await signIn.getByLabel("Temporary password").fill(parent.password);
  await signIn.getByRole("button", { name: "Give sign-in" }).click();
  await expect(signIn).toBeHidden();
  await expect(page.getByTestId("guardians-list")).toContainText(`Signs in as ${parent.email}`);

  // The parent sees their child on the dashboard and nothing of the school's records.
  await signOut(page);
  await loginViaUi(page, { schoolCode: school.schoolCode, email: parent.email, password: parent.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByRole("heading", { name: "My children" })).toBeVisible();
  const card = page.getByTestId("child-card");
  await expect(card).toHaveCount(1);
  await expect(card).toContainText("Kabir Mehta");
  await expect(card).toContainText("Class 5 A");
  await expect(mainNav(page).getByRole("link", { name: "Students", exact: true })).toHaveCount(0);

  const parentToken = await loginViaApi(request, {
    schoolCode: school.schoolCode,
    email: parent.email,
    password: parent.password,
  });
  const asParent = { headers: { Authorization: `Bearer ${parentToken}` } };
  expect((await request.get("/api/students", asParent)).status()).toBe(403);
  expect((await request.get(`/api/students/${studentId}`, asParent)).status()).toBe(403);
  const children = (await (await request.get("/api/me/children", asParent)).json()) as { id: string }[];
  expect(children.map((c) => c.id)).toEqual([studentId]);

  // Another school cannot see the student at all.
  const other = newSchool("stud-b");
  await signupViaApi(request, other);
  const otherToken = await loginViaApi(request, adminCredentials(other));
  const asOther = { headers: { Authorization: `Bearer ${otherToken}` } };
  expect((await request.get(`/api/students/${studentId}`, asOther)).status()).toBe(404);
  const otherList = (await (await request.get("/api/students", asOther)).json()) as { total: number };
  expect(otherList.total).toBe(0);
});
