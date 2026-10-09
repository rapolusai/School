import { expect, test } from "@playwright/test";
import { newSchool } from "./support/helpers";

test("a new school signs up through the wizard and lands on its dashboard", async ({ page }) => {
  const school = newSchool("a");

  await page.goto("/signup");
  await expect(page.getByRole("heading", { name: "Tell us about your school" })).toBeVisible();

  // Step 1: school details. The code is suggested from the name, then made unique for this run.
  await page.getByLabel("School name").fill(school.schoolName);
  await expect(page.getByLabel("School code")).not.toHaveValue("");
  await page.getByLabel("School code").fill(school.schoolCode);
  await page.getByLabel("Board").selectOption(school.board);
  await page.getByLabel("City").fill(school.city);
  await page.getByRole("button", { name: "Continue" }).click();

  // Step 2: the first admin.
  await expect(page.getByRole("heading", { name: "Create the admin account" })).toBeVisible();
  await page.getByLabel("Your name").fill(school.adminName);
  await page.getByLabel("Work email").fill(school.adminEmail);
  await page.getByLabel("Password", { exact: true }).fill(school.password);
  await page.getByRole("button", { name: "Continue" }).click();

  // Step 3: review and create.
  await expect(page.getByRole("heading", { name: "Review and create" })).toBeVisible();
  await expect(page.getByText(school.schoolCode, { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Create my school" }).click();

  // Signed in automatically and on the dashboard of the new school, still in its trial.
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Asha");
  await expect(page.getByTestId("tenant-name")).toHaveText(school.schoolName);
  await expect(page.getByTestId("trial-banner")).toContainText("Your free trial ends on");
});
