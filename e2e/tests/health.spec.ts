import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  newSchool,
  PASSWORD,
  signupViaApi,
  type Credentials,
} from "./support/helpers";

/** Today in India as "YYYY-MM-DD". */
function indiaDate(): string {
  return new Date(Date.now() + 330 * 60_000).toISOString().slice(0, 10);
}

/** The Indian academic year (April to March) that contains today. */
function academicYear() {
  const today = indiaDate();
  const year = Number(today.slice(5, 7)) >= 4 ? Number(today.slice(0, 4)) : Number(today.slice(0, 4)) - 1;
  return { startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31`, name: `${year}-${String((year + 1) % 100).padStart(2, "0")}` };
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
 * Through the API: the nurse, Class 5 A with Tara as class teacher, Asha Rao in it with her mother's sign-in, and a
 * strip of paracetamol in the infirmary's stock.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const post = client(request, auth);
  const nurse: Credentials = { schoolCode, email: `mary@${schoolCode}.example.com`, password: PASSWORD };
  const teacher: Credentials = { schoolCode, email: `tara@${schoolCode}.example.com`, password: PASSWORD };
  await post("/api/users", { name: "Mary Thomas", email: nurse.email, password: PASSWORD, roles: ["NURSE"] });
  const { id: taraId } = await post("/api/users", { name: "Tara Teacher", email: teacher.email, password: PASSWORD, roles: ["TEACHER"] });

  const year = academicYear();
  await post("/api/academics/years", { ...year, current: true });
  const { id: class5 } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: section5A } = await post(`/api/academics/classes/${class5}/sections`, { name: "A", capacity: 40, classTeacherId: taraId });
  const asha = await post<{ id: string; guardians: { id: string }[] }>("/api/students", {
    admissionNo: "E2E/HL/1",
    firstName: "Asha",
    lastName: "Rao",
    dateOfBirth: "2016-03-12",
    gender: "FEMALE",
    admissionDate: year.startsOn,
    sectionId: section5A,
    rollNo: 1,
    guardians: [{ name: "Lata Rao", relation: "MOTHER", phone: "98765 40001", primary: true }],
  });
  const parent: Credentials = { schoolCode, email: `lata@${schoolCode}.example.com`, password: "Parent-temp-2026" };
  await post(`/api/students/${asha.id}/guardians/${asha.guardians[0].id}/sign-in`, { mode: "CREATE", email: parent.email, password: parent.password }, 200);

  const nurseToken = await loginViaApi(request, nurse);
  const asNurse: Auth = { headers: { Authorization: `Bearer ${nurseToken}` } };
  await client(request, asNurse)("/api/health/infirmary/medicines", { name: "Paracetamol 250 mg", unit: "tablet", quantity: 10, reorderLevel: 5 });
  return { nurse, teacher, parent, ashaId: asha.id };
}

async function phone(page: Page) {
  await page.setViewportSize({ width: 390, height: 844 });
}

test("the nurse logs a visit and sends the child home on a phone, the mother sees it and updates an allergy, the admin records a drill", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180_000);
  const school = newSchool("health");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin: Auth = { headers: { Authorization: `Bearer ${adminToken}` } };
  const { nurse, parent, ashaId } = await setUpSchool(request, school.schoolCode, asAdmin);

  // 1. The nurse, on a phone, records Asha's visit with a tablet from the stock, then closes it as sent home.
  await phone(page);
  await loginViaUi(page, nurse);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/health");
  await expect(page.getByRole("heading", { name: "Infirmary" })).toBeVisible();
  await page.getByTestId("visit-add").click();
  const visitForm = page.getByTestId("visit-form");
  await visitForm.getByRole("textbox", { name: "Student" }).fill("Asha");
  await page.getByTestId("visit-person-results").getByRole("button", { name: /Asha Rao/ }).click();
  await visitForm.getByLabel("Complaint", { exact: true }).fill("Fever and headache");
  await visitForm.getByLabel("Temperature (°C)", { exact: true }).fill("38.6");
  await page.getByTestId("visit-add-medicine").click();
  await visitForm.getByLabel("Medicine", { exact: true }).selectOption({ label: "Paracetamol 250 mg (10 tablet)" });
  await page.getByTestId("visit-save").click();
  await expect(visitForm).toBeHidden();
  const row = page.getByTestId("visit-row");
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("Asha Rao");
  await expect(row).toContainText("In the infirmary");
  await expect(row).toContainText("Paracetamol 250 mg ×1");
  await expect(page.getByTestId("infirmary-counts")).toContainText("1 in the infirmary now");
  await expectNoHorizontalScroll(page);
  await page.getByTestId("visit-close").click();
  const closeForm = page.getByTestId("close-form");
  await closeForm.getByLabel("Sent home", { exact: true }).check();
  await expect(closeForm.getByLabel(/The parent has been informed/)).toBeChecked();
  await page.getByTestId("close-save").click();
  await expect(page.getByText("Visit closed. The parent has been sent a message.")).toBeVisible();
  await expect(row).toContainText("Sent home");
  await expect(row).toContainText("Parent informed");
  await expectNoHorizontalScroll(page);

  // The simulated message to the mother is in the outbox, with her number masked.
  const visits = await request.get("/api/health/infirmary/visits", asAdmin);
  expect(visits.status()).toBe(200);
  const { visits: logged } = (await visits.json()) as { visits: { id: string }[] };
  const list = await request.get(`/api/messages?relatedId=${logged[0].id}`, asAdmin);
  expect(list.status()).toBe(200);
  const { items } = (await list.json()) as { items: { templateKey: string; recipient: string }[] };
  const sent = items.find((m) => m.templateKey === "health.sent_home");
  expect(sent, "a sent-home message to the parent").toBeTruthy();
  expect(sent!.recipient).toBe("98765•••01");

  // 2. Asha's mother, on her phone: the home card says Asha was in the infirmary; she raises the peanut allergy.
  const parentContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const parentPage = await parentContext.newPage();
  await loginViaUi(parentPage, parent);
  await expect(parentPage).toHaveURL(/\/app\/dashboard$/);
  const card = parentPage.getByTestId("family-health-card");
  await expect(card).toContainText("Fever and headache");
  await expect(card).toContainText("Sent home");
  await card.getByRole("link", { name: "Open health" }).click();
  await expect(parentPage).toHaveURL(/\/app\/family\/health/);
  await expect(parentPage.getByTestId("family-visits")).toContainText("Sent home");
  await parentPage.getByTestId("add-ALLERGY").click();
  const itemForm = parentPage.getByTestId("item-form");
  await itemForm.getByLabel("Name", { exact: true }).fill("Peanuts");
  await itemForm.getByLabel("Severity", { exact: true }).selectOption("MODERATE");
  await parentPage.getByTestId("item-save").click();
  await expect(itemForm).toBeHidden();
  const allergies = parentPage.getByTestId("items-ALLERGY");
  await expect(allergies).toContainText("Peanuts");
  await expect(allergies).toContainText("Moderate");
  await allergies.getByRole("button", { name: "Edit" }).click();
  await parentPage.getByTestId("item-form").getByLabel("Severity", { exact: true }).selectOption("SEVERE");
  await parentPage.getByTestId("item-save").click();
  await expect(allergies).toContainText("Severe");
  await expectNoHorizontalScroll(parentPage);
  await parentContext.close();

  // The nurse now sees the badge on Asha's record, and the read is in the audit trail.
  await page.goto(`/app/health/students/${ashaId}`);
  await expect(page.getByTestId("record-allergy-badges")).toContainText("Peanuts");
  await expect(page.getByTestId("items-ALLERGY")).toContainText("By the parent");
  const audit = await request.get("/api/audit-events?limit=50", asAdmin);
  expect(audit.status()).toBe(200);
  const actions = ((await audit.json()) as { action: string }[]).map((e) => e.action);
  expect(actions).toContain("medical_record.viewed");
  expect(actions).toContain("infirmary_visit.closed");

  // 3. The admin, on a laptop, records this morning's fire drill; the drills report shows when the next is due.
  await page.setViewportSize({ width: 1440, height: 900 });
  const adminContext = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const adminPage = await adminContext.newPage();
  await loginViaUi(adminPage, adminCredentials(school));
  await expect(adminPage).toHaveURL(/\/app\/dashboard$/);
  await adminPage.goto("/app/health/safety");
  await expect(adminPage.getByRole("heading", { name: "Safety registers" })).toBeVisible();
  await adminPage.getByTestId("drill-add").click();
  const drillForm = adminPage.getByTestId("drill-form");
  await drillForm.getByLabel("Block", { exact: true }).fill("Main block");
  await drillForm.getByLabel("Time to clear (seconds)", { exact: true }).fill("185");
  await drillForm.getByLabel("People present", { exact: true }).fill("412");
  await adminPage.getByTestId("drill-save").click();
  await expect(drillForm).toBeHidden();
  const drill = adminPage.getByTestId("drill-row");
  await expect(drill).toHaveCount(1);
  await expect(drill).toContainText("Fire");
  await expect(drill).toContainText("Cleared in 185 s · 412 present");
  await expect(drill).toContainText("Fine");
  await adminPage.goto("/app/reports/health-drills");
  await expect(adminPage.getByTestId("drills-next-due")).not.toContainText("—");
  await expect(adminPage.getByTestId("drills-rows")).toContainText("Main block");
  await adminContext.close();
});
