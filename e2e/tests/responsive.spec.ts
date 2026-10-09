import { expect, test } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaUi,
  newSchool,
  signupViaApi,
} from "./support/helpers";

test("no horizontal scroll, and the right navigation for this screen size", async ({
  page,
  request,
}, testInfo) => {
  const project = testInfo.project.name; // desktop | tablet | mobile
  const school = newSchool(`resp-${project.slice(0, 3)}`);
  await signupViaApi(request, school);

  await page.goto("/login");
  await expectNoHorizontalScroll(page);

  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("trial-banner")).toBeVisible();
  await expectNoHorizontalScroll(page);

  for (const path of ["/app/users", "/app/roles", "/app/audit"]) {
    await page.goto(path);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await expect(page.getByText("Loading…")).toHaveCount(0);
    await expectNoHorizontalScroll(page);
  }

  const sidebar = page.getByTestId("sidebar");
  const bottomBar = page.getByTestId("bottom-bar");

  if (project === "mobile") {
    await expect(bottomBar).toBeVisible();
    await expect(sidebar).toBeHidden();
    // First four sections + "More", which opens a sheet with every section.
    await expect(bottomBar.getByRole("link")).toHaveCount(4);
    await bottomBar.getByRole("button", { name: "More" }).click();
    const sheet = page.getByRole("dialog", { name: "All sections" });
    await expect(sheet).toBeVisible();
    await expect(sheet.getByRole("button", { name: "Sign out" })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(sheet).toBeHidden();
  } else if (project === "tablet") {
    await expect(sidebar).toBeVisible();
    await expect(bottomBar).toBeHidden();
    const box = await sidebar.boundingBox();
    expect(box!.width).toBeLessThanOrEqual(80);
    // Icon rail: links keep their accessible names but the text labels are not shown.
    const users = sidebar.getByRole("link", { name: "Users", exact: true });
    await expect(users).toBeVisible();
    const linkBox = await users.boundingBox();
    expect(linkBox!.width).toBeLessThanOrEqual(64);
  } else {
    await expect(sidebar).toBeVisible();
    await expect(bottomBar).toBeHidden();
    const box = await sidebar.boundingBox();
    expect(box!.width).toBeGreaterThanOrEqual(200);
    await expect(sidebar.getByText("Roles & permissions")).toBeVisible();
  }
});
