import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  newSchool,
  PASSWORD,
  signOut,
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
 * Through the API: the front office clerk, the security guard, Class 5 A with Tara as class teacher, Asha Rao in it
 * and her mother's sign-in (the phone the OTP goes to is 98765 40001).
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const post = client(request, auth);
  const office: Credentials = { schoolCode, email: `olivia@${schoolCode}.example.com`, password: PASSWORD };
  const guard: Credentials = { schoolCode, email: `sam@${schoolCode}.example.com`, password: PASSWORD };
  const teacher: Credentials = { schoolCode, email: `tara@${schoolCode}.example.com`, password: PASSWORD };
  await post("/api/users", { name: "Olivia Office", email: office.email, password: PASSWORD, roles: ["FRONT_OFFICE"] });
  await post("/api/users", { name: "Sam Security", email: guard.email, password: PASSWORD, roles: ["SECURITY"] });
  const { id: taraId } = await post("/api/users", { name: "Tara Teacher", email: teacher.email, password: PASSWORD, roles: ["TEACHER"] });

  const year = academicYear();
  await post("/api/academics/years", { ...year, current: true });
  const { id: class5 } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: section5A } = await post(`/api/academics/classes/${class5}/sections`, { name: "A", capacity: 40, classTeacherId: taraId });
  const asha = await post<{ id: string; guardians: { id: string }[] }>("/api/students", {
    admissionNo: "E2E/FO/1",
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
  return { office, guard, teacher, parent };
}

/** The six-digit OTP of a pass, read from the notifications outbox the way a tester would: never shown on a staff screen. */
async function otpFromMessageLog(request: APIRequestContext, auth: Auth, passId: string): Promise<string> {
  const list = await request.get(`/api/messages?relatedId=${passId}`, auth);
  expect(list.status()).toBe(200);
  const { items } = (await list.json()) as { items: { id: string; templateKey: string; recipient: string }[] };
  const otpMessage = items.find((m) => m.templateKey === "frontoffice.gate_pass_otp");
  expect(otpMessage, "an OTP message to the parent").toBeTruthy();
  expect(otpMessage!.recipient).toBe("98765•••01");
  const detail = await request.get(`/api/messages/${otpMessage!.id}`, auth);
  expect(detail.status()).toBe(200);
  const { body } = (await detail.json()) as { body: string };
  const match = /OTP ([0-9]{6})/.exec(body);
  expect(match, body).toBeTruthy();
  return match![1];
}

async function phone(page: Page) {
  await page.setViewportSize({ width: 390, height: 844 });
}

test("the office checks a visitor in and out on a phone, a parent asks for an early pickup, the office approves, the guard releases the child with the parent's OTP", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180_000);
  const school = newSchool("frontoffice");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin: Auth = { headers: { Authorization: `Bearer ${adminToken}` } };
  const { office, guard, teacher, parent } = await setUpSchool(request, school.schoolCode, asAdmin);

  // 1. The front office, on a phone, checks a visitor in: the list shows the masked phone, then checks her out.
  await phone(page);
  await loginViaUi(page, office);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/frontoffice");
  await expect(page.getByRole("heading", { name: "Visitors today" })).toBeVisible();
  await page.getByTestId("visitor-add").click();
  const visitorForm = page.getByTestId("visitor-form");
  await visitorForm.getByLabel("Name", { exact: true }).fill("Sunita Verma");
  await visitorForm.getByLabel("Mobile number", { exact: true }).fill("9876543210");
  await visitorForm.getByLabel("Purpose", { exact: true }).selectOption("ADMISSION");
  await visitorForm.getByLabel("Whom to meet", { exact: true }).fill("Principal");
  await visitorForm.getByLabel("Number of people", { exact: true }).fill("2");
  await visitorForm.getByTestId("visitor-save").click();
  await expect(visitorForm).toBeHidden();
  const inside = page.getByTestId("visitors-inside");
  const row = inside.getByTestId("visitor-row");
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("Sunita Verma");
  await expect(row).toContainText("98765•••10");
  await expect(row).not.toContainText("9876543210");
  await expect(row).toContainText("Admission · To meet Principal · 2 people");
  await expect(row.getByTestId("visitor-photo")).toBeVisible();
  await expectNoHorizontalScroll(page);
  await row.getByTestId("visitor-check-out").click();
  await expect(page.getByText("Sunita Verma checked out.")).toBeVisible();
  await expect(inside.getByTestId("visitor-row")).toHaveCount(0);
  const gone = page.getByTestId("visitors-out").getByTestId("visitor-row");
  await expect(gone).toHaveCount(1);
  await expect(gone).toContainText("Left");
  await expectNoHorizontalScroll(page);

  // 2. Asha's mother, on her phone, asks for an early pickup by her grandmother.
  const parentContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const parentPage = await parentContext.newPage();
  await loginViaUi(parentPage, parent);
  await expect(parentPage).toHaveURL(/\/app\/dashboard$/);
  await expect(parentPage.getByTestId("family-pickup-card")).toContainText("No early pickup today.");
  await parentPage.goto("/app/family/pickup");
  await expect(parentPage.getByTestId("pickup-none")).toBeVisible();
  await parentPage.getByTestId("pickup-request").click();
  const pickupForm = parentPage.getByTestId("pickup-form");
  await pickupForm.getByLabel("Reason", { exact: true }).fill("Dentist appointment");
  await pickupForm.getByLabel("Who will collect", { exact: true }).selectOption("OTHER");
  await pickupForm.getByLabel("Collector's name", { exact: true }).fill("Kamla Devi");
  await pickupForm.getByLabel("Collector's mobile", { exact: true }).fill("9876540009");
  await pickupForm.getByTestId("pickup-save").click();
  await expect(parentPage.getByText("Request sent to the office.")).toBeVisible();
  const familyPass = parentPage.getByTestId("family-pass");
  await expect(familyPass).toHaveCount(1);
  await expect(familyPass).toContainText("Waiting for approval");
  await expect(familyPass).toContainText("Kamla Devi · 98765•••09");
  await expect(parentPage.getByTestId("pickup-request")).toHaveCount(0);
  await expectNoHorizontalScroll(parentPage);

  // 3. The office approves it at the desk; the OTP goes to the parent's phone and is never shown to staff.
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/app/frontoffice/passes");
  const passRow = page.getByTestId("pass-row");
  await expect(passRow).toHaveCount(1);
  await expect(passRow).toContainText("Asha Rao");
  await expect(passRow).toContainText("Waiting for approval");
  await expect(passRow).toContainText("Asked from the app by Lata Rao");
  await expect(passRow).toContainText("OTP goes to Lata Rao (98765•••01)");
  await passRow.getByTestId("pass-approve").click();
  await expect(page.getByText("Pass for Asha Rao approved; the OTP has been sent to the parent.")).toBeVisible();
  await expect(passRow).toContainText("Approved");
  await expect(passRow).toContainText("OTP valid till");
  await expect(passRow).not.toContainText(/OTP [0-9]{6}/);

  const myPasses = await request.get("/api/me/gate-passes", { headers: { Authorization: `Bearer ${await loginViaApi(request, parent)}` } });
  expect(myPasses.status()).toBe(200);
  const [approved] = (await myPasses.json()) as { id: string; status: string }[];
  expect(approved.status).toBe("APPROVED");
  const otp = await otpFromMessageLog(request, asAdmin, approved.id);

  // The parent sees the pass approved with the OTP notice (the OTP itself arrives by SMS, not in the app).
  await parentPage.reload();
  await expect(parentPage.getByTestId("family-pass")).toContainText("Approved");
  await expect(parentPage.getByTestId("pass-otp-hint")).toContainText("OTP sent to your phone");
  await expect(parentPage.getByTestId("family-pass")).not.toContainText(otp);

  // 4. The guard, on the gate phone, tries a wrong OTP once, then the right one, and releases Asha.
  const guardContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const guardPage = await guardContext.newPage();
  await loginViaUi(guardPage, guard);
  await expect(guardPage).toHaveURL(/\/app\/dashboard$/);
  await guardPage.goto("/app/frontoffice/gate");
  const gatePass = guardPage.getByTestId("gate-pass");
  await expect(gatePass).toHaveCount(1);
  await expect(gatePass).toContainText("Asha Rao");
  await expect(gatePass).toContainText("Collected by: Kamla Devi · 98765•••09");
  await expect(gatePass).toContainText("5 tries left");
  await gatePass.getByRole("button", { name: "Who may collect this child" }).click();
  await expect(guardPage.getByText("The family has not listed anyone yet.")).toBeVisible();
  const releaseForm = gatePass.getByTestId("release-form");
  await expect(releaseForm.getByLabel("Taken by (name)", { exact: true })).toHaveValue("Kamla Devi");
  const wrong = otp === "000000" ? "111111" : "000000";
  await releaseForm.getByLabel("OTP from the parent", { exact: true }).fill(wrong);
  await releaseForm.getByTestId("release-button").click();
  await expect(releaseForm).toContainText("Wrong OTP. 4 tries left.");
  await releaseForm.getByLabel("OTP from the parent", { exact: true }).fill(otp);
  await releaseForm.getByTestId("release-button").click();
  await expect(guardPage.getByText("Asha Rao released.")).toBeVisible();
  await expect(guardPage.getByText("No approved passes right now.")).toBeVisible();
  await expectNoHorizontalScroll(guardPage);
  // The guard can reach the gate only, not the enquiry desk.
  const guardAuth: Auth = { headers: { Authorization: `Bearer ${await loginViaApi(request, guard)}` } };
  expect((await request.get("/api/frontoffice/enquiries", guardAuth)).status()).toBe(403);

  // 5. The parent sees the pass completed; the class teacher's dashboard lists the early pickup.
  await parentPage.reload();
  const done = parentPage.getByTestId("family-pass");
  await expect(done).toHaveAttribute("data-status", "RELEASED");
  await expect(done).toContainText("Child released");
  await expect(done).toContainText("Handed to Kamla Devi at");
  await parentPage.goto("/app/dashboard");
  // The home card shows only an open pass; a released one is history on the pickup page.
  await expect(parentPage.getByTestId("family-pickup-card")).toContainText("No early pickup today.");
  await expectNoHorizontalScroll(parentPage);
  await parentContext.close();
  await guardContext.close();

  await signOut(page);
  await loginViaUi(page, teacher);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const classPickups = page.getByTestId("class-pickups");
  await expect(classPickups).toContainText("Asha Rao");
  await expect(classPickups).toContainText("collected by Kamla Devi");
  await expect(classPickups).toContainText("Child released");

  // 6. The audit trail has the whole story, and the guard's wrong try is in it.
  const audit = await request.get("/api/audit-events?limit=200", asAdmin);
  expect(audit.status()).toBe(200);
  const auditText = await audit.text();
  for (const action of ["visitor.checked_in", "visitor.checked_out", "gate_pass.requested", "gate_pass.approved", "gate_pass.wrong_otp", "gate_pass.released"]) {
    expect(auditText, action).toContain(action);
  }
});
