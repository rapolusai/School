import { expect, test } from "@playwright/test";
import { adminCredentials, loginViaUi, newSchool, signOut, signupViaApi } from "./support/helpers";

test.describe("signing in and out", () => {
  test("logs out, then logs back in with school code, email and password", async ({ page, request }) => {
    const school = newSchool("auth");
    await signupViaApi(request, school);

    await loginViaUi(page, adminCredentials(school));
    await expect(page).toHaveURL(/\/app\/dashboard$/);

    await signOut(page);
    // The refresh cookie is gone too: a reload does not restore the session.
    await page.goto("/app/dashboard");
    await expect(page).toHaveURL(/\/login(\?|$)/);

    await loginViaUi(page, adminCredentials(school));
    await expect(page).toHaveURL(/\/app\/dashboard$/);
    await expect(page.getByTestId("tenant-name")).toHaveText(school.schoolName);
  });

  test("a wrong password shows the generic error and stays on the login page", async ({ page, request }) => {
    const school = newSchool("auth-bad");
    await signupViaApi(request, school);

    await loginViaUi(page, { ...adminCredentials(school), password: "not-the-right-password" });

    await expect(
      page.getByText("Those details don't match. Check the school code, email and password."),
    ).toBeVisible();
    await expect(page).toHaveURL(/\/login(\?|$)/);
  });

  test("a signed-out visit to /app/users redirects to /login", async ({ page }) => {
    await page.goto("/app/users");
    await expect(page).toHaveURL(/\/login(\?|$)/);
    await expect(page.getByLabel("School code")).toBeVisible();
  });
});
