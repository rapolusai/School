import { expect, type APIRequestContext, type Page } from "@playwright/test";

export const PASSWORD = "E2e-correct-horse-9";

export type School = {
  schoolName: string;
  schoolCode: string;
  board: "CBSE" | "ICSE" | "STATE" | "IB" | "CAMBRIDGE";
  city: string;
  adminName: string;
  adminEmail: string;
  password: string;
};

export type Credentials = { schoolCode: string; email: string; password: string };

/**
 * A school code that is unique per run: "e2e-<tag>-<base36 time>-<random>".
 * Fits the contract (3–40 chars, a-z 0-9 "-", starts with a letter).
 */
export function uniqueCode(tag: string): string {
  const time = Date.now().toString(36);
  const random = Math.random().toString(36).slice(2, 6);
  const code = `e2e-${tag}-${time}-${random}`.toLowerCase().replace(/[^a-z0-9-]/g, "");
  return code.slice(0, 40);
}

export function newSchool(tag: string): School {
  const schoolCode = uniqueCode(tag);
  return {
    schoolName: `E2E School ${schoolCode.slice(4)}`,
    schoolCode,
    board: "CBSE",
    city: "Pune",
    adminName: "Asha Rao",
    adminEmail: `admin@${schoolCode}.example.com`,
    password: PASSWORD,
  };
}

export function adminCredentials(school: School): Credentials {
  return { schoolCode: school.schoolCode, email: school.adminEmail, password: school.password };
}

/** POST /api/public/signup through the frontend's /api proxy. */
export async function signupViaApi(request: APIRequestContext, school: School) {
  const res = await request.post("/api/public/signup", { data: school });
  expect(res.status(), await res.text()).toBe(201);
  return (await res.json()) as { tenantId: string; schoolCode: string; trialEndsAt: string };
}

/** POST /api/auth/login and return the access token. */
export async function loginViaApi(request: APIRequestContext, creds: Credentials): Promise<string> {
  const res = await request.post("/api/auth/login", { data: creds });
  expect(res.status(), await res.text()).toBe(200);
  const body = (await res.json()) as { accessToken: string };
  return body.accessToken;
}

export async function loginViaUi(page: Page, creds: Credentials) {
  await page.goto("/login");
  await page.getByLabel("School code", { exact: true }).fill(creds.schoolCode);
  await page.getByLabel("Email", { exact: true }).fill(creds.email);
  await page.getByLabel("Password", { exact: true }).fill(creds.password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
}

/** Sign out through the account menu in the top bar (present at every screen size). */
export async function signOut(page: Page) {
  await page.getByRole("button", { name: /^Account menu/ }).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login(\?|$)/);
}

export function mainNav(page: Page) {
  return page.getByRole("navigation", { name: "Main navigation" });
}

export async function expectNoHorizontalScroll(page: Page) {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
  expect(scrollWidth, `page is ${scrollWidth}px wide in a ${clientWidth}px viewport`).toBeLessThanOrEqual(
    clientWidth,
  );
}
