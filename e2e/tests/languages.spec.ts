import { expect, test } from "@playwright/test";
import { adminCredentials, expectNoHorizontalScroll, loginViaUi, newSchool, signupViaApi } from "./support/helpers";

test("Telugu on sign-in, a Telugu shell, and the account remembers it on a fresh browser", async ({
  page,
  browser,
  request,
}, testInfo) => {
  const project = testInfo.project.name;
  test.skip(project === "tablet", "Checked at 390 px (mobile) and 1440 px (desktop).");
  const school = newSchool(`lang-${project.slice(0, 3)}`);
  await signupViaApi(request, school);
  const creds = adminCredentials(school);

  // Switch to Telugu on the sign-in page, then sign in with the Telugu form.
  await page.goto("/login");
  await page.getByLabel("Language", { exact: true }).selectOption("te");
  await expect(page.locator("html")).toHaveAttribute("lang", "te");
  await page.getByLabel("పాఠశాల కోడ్", { exact: true }).fill(creds.schoolCode);
  await page.getByLabel("ఈమెయిల్", { exact: true }).fill(creds.email);
  await page.getByLabel("పాస్‌వర్డ్", { exact: true }).fill(creds.password);
  await page.getByRole("button", { name: "సైన్ ఇన్", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/dashboard$/);

  // The shell is in Telugu, with nothing wider than the screen.
  await expect(page.getByRole("link", { name: "విద్యార్థులు" }).first()).toBeAttached();
  await expectNoHorizontalScroll(page);
  await page.goto("/app/students");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await expectNoHorizontalScroll(page);

  // Save Telugu as the account's language from the shell's switcher.
  const saved = page.waitForResponse(
    (res) => res.url().endsWith("/api/me/preferences") && res.request().method() === "PUT",
  );
  const select = page.getByLabel("భాష", { exact: true }).first();
  await select.selectOption("en");
  await saved;
  const savedTe = page.waitForResponse(
    (res) => res.url().endsWith("/api/me/preferences") && res.request().method() === "PUT",
  );
  await page.getByLabel("Language", { exact: true }).first().selectOption("te");
  expect((await savedTe).status()).toBe(200);

  // A fresh browser (no device choice) signs in in English and is switched to Telugu by the account.
  const fresh = await browser.newContext({
    baseURL: testInfo.project.use.baseURL,
    viewport: page.viewportSize() ?? undefined,
  });
  const second = await fresh.newPage();
  await loginViaUi(second, creds);
  await expect(second).toHaveURL(/\/app\/dashboard$/);
  await expect(second.locator("html")).toHaveAttribute("lang", "te");
  await expect(second.getByRole("link", { name: "విద్యార్థులు" }).first()).toBeAttached();
  await expectNoHorizontalScroll(second);
  await fresh.close();
});
