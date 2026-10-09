import { expect, test } from "@playwright/test";
import {
  adminCredentials,
  loginViaUi,
  mainNav,
  newSchool,
  signOut,
  signupViaApi,
} from "./support/helpers";

test("an admin adds a teacher, who cannot see or open the admin pages", async ({ page, request }) => {
  const school = newSchool("users");
  await signupViaApi(request, school);
  const teacher = {
    name: "Fatima Shaikh",
    email: `fatima@${school.schoolCode}.example.com`,
    password: "Teacher-temp-2026",
  };

  // Admin adds the teacher.
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Users", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/users$/);

  await page.getByRole("button", { name: "Add user" }).click();
  const dialog = page.getByRole("dialog", { name: "Add a user" });
  await dialog.getByLabel("Full name").fill(teacher.name);
  await dialog.getByLabel("Email", { exact: true }).fill(teacher.email);
  await dialog.getByLabel("Temporary password").fill(teacher.password);
  await dialog.getByRole("checkbox", { name: "Teacher", exact: true }).check();
  await dialog.getByRole("button", { name: "Add user" }).click();

  await expect(dialog).toBeHidden();
  const table = page.getByTestId("users-table");
  await expect(table).toContainText(teacher.email);
  await expect(table.getByRole("row").filter({ hasText: teacher.email })).toContainText("Teacher");

  // The teacher signs in.
  await signOut(page);
  await loginViaUi(page, { schoolCode: school.schoolCode, email: teacher.email, password: teacher.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);

  const nav = mainNav(page);
  await expect(nav.getByRole("link", { name: "Dashboard" })).toBeVisible();
  await expect(nav.getByRole("link", { name: "Users", exact: true })).toHaveCount(0);
  await expect(nav.getByRole("link", { name: "Audit trail" })).toHaveCount(0);

  // Opening the page directly shows the access-denied state.
  await page.goto("/app/users");
  await expect(page.getByRole("heading", { name: "You don't have access to this page" })).toBeVisible();
  await expect(page.getByTestId("users-table")).toHaveCount(0);
});
