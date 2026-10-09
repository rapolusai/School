import { expect, test } from "@playwright/test";
import {
  adminCredentials,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  signupViaApi,
} from "./support/helpers";

type UserSummary = { id: string; email: string };

test("school A sees only its own users and cannot fetch school B's", async ({ page, request }) => {
  const schoolA = newSchool("iso-a");
  const schoolB = newSchool("iso-b");
  await signupViaApi(request, schoolA);
  await signupViaApi(request, schoolB);

  // In the UI, A's admin sees A's users only.
  await loginViaUi(page, adminCredentials(schoolA));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Users", exact: true }).click();
  const table = page.getByTestId("users-table");
  await expect(table).toContainText(schoolA.adminEmail);
  await expect(table).not.toContainText(schoolB.adminEmail);

  // Through the API, A's token cannot read a user that belongs to B.
  const tokenA = await loginViaApi(request, adminCredentials(schoolA));
  const tokenB = await loginViaApi(request, adminCredentials(schoolB));

  const usersB = (await (
    await request.get("/api/users", { headers: { Authorization: `Bearer ${tokenB}` } })
  ).json()) as UserSummary[];
  const adminB = usersB.find((u) => u.email === schoolB.adminEmail);
  expect(adminB, "school B's admin is listed for school B").toBeTruthy();

  const crossTenant = await request.get(`/api/users/${adminB!.id}`, {
    headers: { Authorization: `Bearer ${tokenA}` },
  });
  expect(crossTenant.status()).toBe(404);

  // Sanity check: the same call works within the right school.
  const sameTenant = await request.get(`/api/users/${adminB!.id}`, {
    headers: { Authorization: `Bearer ${tokenB}` },
  });
  expect(sameTenant.status()).toBe(200);

  const usersA = (await (
    await request.get("/api/users", { headers: { Authorization: `Bearer ${tokenA}` } })
  ).json()) as UserSummary[];
  expect(usersA.map((u) => u.id)).not.toContain(adminB!.id);
});
