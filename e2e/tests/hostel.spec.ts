import { expect, test, type APIRequestContext } from "@playwright/test";
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

/** Through the API: the warden, Class 5 A, Arjun Sharma in it and his mother's sign-in. */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const post = client(request, auth);
  const warden: Credentials = { schoolCode, email: `vikram@${schoolCode}.example.com`, password: PASSWORD };
  await post("/api/users", { name: "Vikram Pillai", email: warden.email, password: PASSWORD, roles: ["WARDEN"] });
  const year = academicYear();
  await post("/api/academics/years", { ...year, current: true });
  const { id: class5 } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: section5A } = await post(`/api/academics/classes/${class5}/sections`, { name: "A", capacity: 40 });
  const arjun = await post<{ id: string; guardians: { id: string }[] }>("/api/students", {
    admissionNo: "E2E/HO/1",
    firstName: "Arjun",
    lastName: "Sharma",
    dateOfBirth: "2016-03-12",
    gender: "MALE",
    admissionDate: year.startsOn,
    sectionId: section5A,
    rollNo: 1,
    guardians: [{ name: "Lata Sharma", relation: "MOTHER", phone: "98765 40001", primary: true }],
  });
  const parent: Credentials = { schoolCode, email: `lata@${schoolCode}.example.com`, password: "Parent-temp-2026" };
  await post(`/api/students/${arjun.id}/guardians/${arjun.guardians[0].id}/sign-in`, { mode: "CREATE", email: parent.email, password: parent.password }, 200);
  return { warden, parent, arjunId: arjun.id };
}

test("the admin sets up a hostel and allots a bed, the warden takes the night roll call on a phone, the parent asks for an outing and the warden approves it", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180_000);
  const school = newSchool("hostel");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin: Auth = { headers: { Authorization: `Bearer ${adminToken}` } };
  const { warden, parent } = await setUpSchool(request, school.schoolCode, asAdmin);

  // 1. The admin, at a desk, creates the boys' hostel with its warden, a block, a room and two beds.
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/hostel");
  await expect(page.getByRole("heading", { name: "Hostels" })).toBeVisible();
  await page.getByTestId("hostel-add").click();
  const hostelForm = page.getByTestId("hostel-form");
  await hostelForm.getByLabel("Name", { exact: true }).fill("Boys' Hostel");
  await hostelForm.getByLabel("Type", { exact: true }).selectOption("BOYS");
  await hostelForm.getByLabel("Warden", { exact: true }).selectOption({ label: "Vikram Pillai" });
  await hostelForm.getByTestId("hostel-save").click();
  await expect(page.getByText("Hostel saved.")).toBeVisible();
  const hostelCard = page.getByTestId("hostel-card");
  await expect(hostelCard).toHaveCount(1);
  await expect(hostelCard).toContainText("Warden: Vikram Pillai");
  await hostelCard.getByTestId("hostel-open").click();
  await expect(page).toHaveURL(/\/app\/hostel\/[0-9a-f-]+$/);

  await page.getByTestId("block-add").click();
  const blockForm = page.getByTestId("block-form");
  await blockForm.getByLabel("Block name", { exact: true }).fill("A");
  await blockForm.getByLabel("Floors", { exact: true }).fill("2");
  await blockForm.getByTestId("block-save").click();
  await expect(page.getByText("Block saved.")).toBeVisible();

  await page.getByTestId("room-add").click();
  const roomForm = page.getByTestId("room-form");
  await roomForm.getByLabel("Room number", { exact: true }).fill("101");
  await roomForm.getByLabel("Floor", { exact: true }).fill("1");
  await roomForm.getByLabel("Room type", { exact: true }).selectOption("DOUBLE");
  await roomForm.getByLabel("Capacity", { exact: true }).fill("2");
  await roomForm.getByLabel("Monthly fee (₹)", { exact: true }).fill("2500");
  await roomForm.getByTestId("room-save").click();
  await expect(page.getByText("Room saved.")).toBeVisible();
  const roomRow = page.getByTestId("room-row");
  await expect(roomRow).toHaveCount(1);
  await expect(roomRow).toContainText("101");

  await roomRow.getByTestId("beds-add").click();
  const bedsForm = page.getByTestId("beds-form");
  await bedsForm.getByLabel("Bed labels", { exact: true }).fill("A, B");
  await bedsForm.getByTestId("beds-save").click();
  await expect(page.getByText("Beds added.")).toBeVisible();
  await expect(roomRow.getByTestId("room-beds")).toContainText("2");

  // 2. On the occupancy board the admin allots bed A to Arjun with his mother's consent.
  await page.goto("/app/hostel/occupancy");
  const roomCard = page.getByTestId("room-card");
  await expect(roomCard).toHaveCount(1);
  await expect(roomCard).toHaveAttribute("data-free", "2");
  await roomCard.getByTestId("bed-allot").first().click();
  const allotForm = page.getByTestId("allot-form");
  await allotForm.getByTestId("student-search").fill("Arj");
  await page.getByTestId("student-matches").getByRole("button", { name: /Arjun Sharma/ }).click();
  await allotForm.getByTestId("allot-consent").check();
  await allotForm.getByTestId("allot-save").click();
  await expect(page.getByText("Bed allotted.")).toBeVisible();
  await expect(roomCard).toHaveAttribute("data-free", "1");
  await expect(roomCard.getByTestId("bed").filter({ hasText: "Arjun Sharma" })).toHaveCount(1);
  await expect(page.getByTestId("occupancy-occupied")).toContainText("1");

  // 3. The warden, on a phone, takes tonight's roll call: Arjun is present.
  const wardenContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const wardenPage = await wardenContext.newPage();
  await loginViaUi(wardenPage, warden);
  await expect(wardenPage).toHaveURL(/\/app\/dashboard$/);
  await wardenPage.goto("/app/hostel/roll-call");
  await expect(wardenPage.getByRole("heading", { name: "Night roll call" })).toBeVisible();
  const rollCallForm = wardenPage.getByTestId("roll-call-form");
  const rollCallRow = rollCallForm.getByTestId("roll-call-row");
  await expect(rollCallRow).toHaveCount(1);
  await expect(rollCallRow).toContainText("Arjun Sharma");
  await expect(wardenPage.getByTestId("roll-call-state")).toContainText("Not taken yet");
  await expectNoHorizontalScroll(wardenPage);
  await rollCallForm.getByTestId("roll-call-save").click();
  await expect(rollCallForm.getByText("1 boarder still unmarked").first()).toBeVisible();
  await rollCallForm.getByTestId("roll-call-all-present").click();
  await expect(rollCallRow).toHaveAttribute("data-status", "PRESENT");
  await rollCallForm.getByTestId("roll-call-save").click();
  await expect(wardenPage.getByText("Roll call saved; everyone accounted for.")).toBeVisible();
  await expect(wardenPage.getByTestId("roll-call-state")).toContainText("Taken by Vikram Pillai");
  await expect(wardenPage.getByTestId("register-night")).toHaveCount(1);
  await expectNoHorizontalScroll(wardenPage);

  // 4. Arjun's mother, on her phone, sees the hostel card on her home and asks for a day out.
  const parentContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const parentPage = await parentContext.newPage();
  await loginViaUi(parentPage, parent);
  await expect(parentPage).toHaveURL(/\/app\/dashboard$/);
  const familyCard = parentPage.getByTestId("family-hostel-card");
  await expect(familyCard).toContainText("Boys' Hostel · Room 101 / A");
  await expect(familyCard).toContainText("Warden: Vikram Pillai");
  await expect(familyCard).toContainText("Present");
  await familyCard.getByTestId("family-hostel-open").click();
  await expect(parentPage).toHaveURL(/\/app\/family\/hostel/);
  await expect(parentPage.getByTestId("hostel-pass-none")).toBeVisible();
  await parentPage.getByTestId("hostel-pass-request").click();
  const passForm = parentPage.getByTestId("hostel-pass-form");
  await passForm.getByLabel("Reason", { exact: true }).fill("Cousin's wedding");
  await passForm.getByTestId("hostel-pass-save").click();
  await expect(parentPage.getByText("Pass requested; the warden will decide.")).toBeVisible();
  const familyPass = parentPage.getByTestId("family-hostel-pass");
  await expect(familyPass).toHaveCount(1);
  await expect(familyPass).toContainText("Requested");
  await expect(familyPass).toContainText("Cousin's wedding");
  await expectNoHorizontalScroll(parentPage);

  // 5. The warden approves it; the parent is told through the outbox and sees it approved.
  await wardenPage.goto("/app/hostel/passes");
  const passRow = wardenPage.getByTestId("hostel-pass");
  await expect(passRow).toHaveCount(1);
  await expect(passRow).toContainText("Arjun Sharma");
  await expect(passRow).toContainText("Asked by Lata Sharma");
  await passRow.getByTestId("pass-approve").click();
  const decisionForm = wardenPage.getByTestId("decision-form");
  await decisionForm.getByLabel("Note", { exact: true }).fill("Back by 6 pm please.");
  await decisionForm.getByTestId("decision-save").click();
  await expect(wardenPage.getByText("Pass approved; the parent has been told.")).toBeVisible();
  await expect(passRow).toHaveAttribute("data-status", "APPROVED");
  await expectNoHorizontalScroll(wardenPage);

  const parentToken = await loginViaApi(request, parent);
  const children = await request.get("/api/me/children", { headers: { Authorization: `Bearer ${parentToken}` } });
  expect(children.status()).toBe(200);
  const [child] = (await children.json()) as { id: string }[];
  const passes = await request.get(`/api/me/children/${child.id}/hostel-passes`, { headers: { Authorization: `Bearer ${parentToken}` } });
  expect(passes.status()).toBe(200);
  const [approved] = (await passes.json()) as { id: string; status: string }[];
  expect(approved.status).toBe("APPROVED");
  const outbox = await request.get(`/api/messages?relatedId=${approved.id}`, asAdmin);
  expect(outbox.status()).toBe(200);
  const { items } = (await outbox.json()) as { items: { templateKey: string; recipient: string }[] };
  const told = items.find((m) => m.templateKey === "hostel.pass_decided");
  expect(told, "a decision message to the parent").toBeTruthy();
  expect(told!.recipient).toBe("98765•••01");

  await parentPage.reload();
  await expect(parentPage.getByTestId("family-hostel-pass")).toContainText("Approved");
  await expect(parentPage.getByTestId("family-hostel-pass")).toContainText("Back by 6 pm please.");

  await parentContext.close();
  await wardenContext.close();
});
