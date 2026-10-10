import { expect, test, type APIRequestContext } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  signOut,
  signupViaApi,
  type Credentials,
} from "./support/helpers";

const DAY_MS = 86_400_000;

/** Today's date in India as YYYY-MM-DD. */
function indiaDate(offsetDays = 0): string {
  return new Date(Date.now() + 330 * 60_000 + offsetDays * DAY_MS).toISOString().slice(0, 10);
}

/** The Indian academic year (April to March) that contains today. */
function academicYear() {
  const today = indiaDate();
  const year = Number(today.slice(5, 7)) >= 4 ? Number(today.slice(0, 4)) : Number(today.slice(0, 4)) - 1;
  return { startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31`, name: `${year}-${String((year + 1) % 100).padStart(2, "0")}` };
}

type PrivacySchool = {
  schoolCode: string;
  admin: Credentials;
  parent: Credentials;
  adminToken: string;
  studentId: string;
  admissionNo: string;
};

/** A new school through the API: the current year, Class 5 A and one student whose mother has a sign-in. */
async function setUpSchool(request: APIRequestContext, tag: string): Promise<PrivacySchool> {
  const school = newSchool(tag);
  await signupViaApi(request, school);
  const admin = adminCredentials(school);
  const adminToken = await loginViaApi(request, admin);
  const headers = { Authorization: `Bearer ${adminToken}` };
  const post = async <T>(path: string, data?: unknown, status = 201): Promise<T> => {
    const res = await request.post(path, { headers, data });
    expect(res.status(), `${path}: ${await res.text()}`).toBe(status);
    return (await res.json()) as T;
  };

  await post("/api/academics/years", { ...academicYear(), current: true });
  const klass = await post<{ id: string }>("/api/academics/classes", { name: "Class 5" });
  const section = await post<{ id: string }>(`/api/academics/classes/${klass.id}/sections`, { name: "A", capacity: 40 });
  const parent = { schoolCode: school.schoolCode, email: `meena@${school.schoolCode}.example.com`, password: "Parent-temp-2026" };
  const admissionNo = "E2E/PRIV/001";
  const student = await post<{ id: string; guardians: { id: string }[] }>("/api/students", {
    admissionNo,
    firstName: "Kabir",
    lastName: "Mehta",
    dateOfBirth: "2016-04-12",
    gender: "MALE",
    admissionDate: indiaDate(),
    sectionId: section.id,
    rollNo: 1,
    guardians: [{ name: "Meena Mehta", relation: "MOTHER", phone: "9876543210", email: parent.email, primary: true }],
  });
  await post(`/api/students/${student.id}/guardians/${student.guardians[0].id}/sign-in`, { mode: "CREATE", ...parent }, 200);
  return { schoolCode: school.schoolCode, admin, parent, adminToken, studentId: student.id, admissionNo };
}

/** The grievance officer and version 1 of the standard notice, through the API. */
async function publishNotice(request: APIRequestContext, s: PrivacySchool) {
  const headers = { Authorization: `Bearer ${s.adminToken}` };
  const officer = await request.put("/api/privacy/grievance-officer", {
    headers,
    data: { name: "Lakshmi Iyer", email: `grievance@${s.schoolCode}.example.com`, phone: "040 2345 6789" },
  });
  expect(officer.status(), await officer.text()).toBe(200);
  const draft = (await (await request.get("/api/privacy/notice", { headers })).json()) as { draftEn: string; draftHi: string };
  const published = await request.post("/api/privacy/notice", {
    headers,
    data: { bodyEn: draft.draftEn, bodyHi: draft.draftHi, changeSummary: null },
  });
  expect(published.status(), await published.text()).toBe(201);
}

test("the admin publishes a notice; the parent accepts it, changes a choice and asks for their data", async ({
  page,
  request,
  browser,
}) => {
  const s = await setUpSchool(request, "priv-consent");

  // The admin names the grievance officer and publishes version 1 from the standard notice.
  await loginViaUi(page, s.admin);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Data protection" }).click();
  await expect(page).toHaveURL(/\/app\/privacy$/);
  await expect(page.getByTestId("requests-empty")).toBeVisible();
  await page.getByRole("navigation", { name: "Data protection sections" }).getByRole("link", { name: "Privacy notice" }).click();
  await expect(page).toHaveURL(/\/app\/privacy\/notice$/);
  await expect(page.getByTestId("template-hint")).toBeVisible();

  await page.getByRole("button", { name: "Publish version 1" }).click();
  await expect(page.getByText("Save the grievance officer before publishing the notice.")).toBeVisible();
  await page.getByLabel("Name", { exact: true }).fill("Lakshmi Iyer");
  await page.getByLabel("Email", { exact: true }).fill(`grievance@${s.schoolCode}.example.com`);
  await page.getByLabel("Phone", { exact: true }).fill("040 2345 6789");
  await page.getByRole("button", { name: "Save officer" }).click();
  await expect(page.getByText("Grievance officer saved.")).toBeVisible();
  await page.getByRole("button", { name: "Publish version 1" }).click();
  await expect(page.getByText("Version 1 published. Parents will be asked to accept it.")).toBeVisible();
  await expect(page.getByTestId("current-notice")).toContainText("Version 1");
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoHorizontalScroll(page);
  await page.getByRole("navigation", { name: "Data protection sections" }).getByRole("link", { name: "Consent" }).click();
  await expect(page.getByTestId("consent-coverage")).toContainText("0 of 1 students at school have essential consent for version 1.");
  await expect(page.getByTestId("consents-cards")).toContainText("Kabir Mehta");
  await expectNoHorizontalScroll(page);
  await page.setViewportSize({ width: 1440, height: 900 });

  // Anyone can read it, with the grievance officer, without signing in.
  const visitor = await browser.newPage({ baseURL: test.info().project.use.baseURL });
  await visitor.goto(`/privacy/${s.schoolCode}`);
  await expect(visitor.getByTestId("notice-version")).toContainText("Version 1");
  await expect(visitor.getByTestId("grievance-officer")).toContainText("Lakshmi Iyer");
  await visitor.getByRole("radio", { name: "हिन्दी" }).check();
  await expect(visitor.getByTestId("notice-text")).toHaveAttribute("lang", "hi");
  await visitor.setViewportSize({ width: 390, height: 844 });
  await expectNoHorizontalScroll(visitor);
  await visitor.close();

  // The parent must accept before using the app; optional choices start unticked.
  await signOut(page);
  await loginViaUi(page, s.parent);
  const gate = page.getByTestId("privacy-gate");
  await expect(gate).toBeVisible();
  await expect(page.getByRole("heading", { name: "My children" })).toHaveCount(0);
  await gate.getByRole("button", { name: "Agree and continue" }).click();
  await expect(gate.getByText("Tick this box to continue to the app.")).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoHorizontalScroll(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await gate.getByRole("checkbox", { name: /I have read the notice/ }).check();
  const choices = gate.getByTestId(`gate-child-${s.studentId}`);
  await expect(choices.getByRole("checkbox", { name: /Photos/ })).not.toBeChecked();
  await choices.getByRole("checkbox", { name: /WhatsApp/ }).check();
  await gate.getByRole("button", { name: "Agree and continue" }).click();
  await expect(page.getByRole("heading", { name: "My children" })).toBeVisible();

  // Their privacy page: withdraw WhatsApp, then ask for a copy of the child's data.
  await mainNav(page).getByRole("link", { name: "Privacy and my data" }).click();
  await expect(page).toHaveURL(/\/app\/my-privacy$/);
  const card = page.getByTestId(`child-consent-${s.studentId}`);
  const whatsapp = card.getByRole("checkbox", { name: /WhatsApp/ });
  await expect(whatsapp).toBeChecked();
  // The box follows the saved state, so click and wait rather than uncheck().
  await whatsapp.click();
  await expect(page.getByText("Consent withdrawn.")).toBeVisible();
  await expect(whatsapp).not.toBeChecked();
  for (const viewport of [
    { width: 820, height: 1180 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport);
    await expectNoHorizontalScroll(page);
  }
  await page.setViewportSize({ width: 1440, height: 900 });

  await page.getByRole("button", { name: "New request" }).click();
  const dialog = page.getByRole("dialog", { name: "New request" });
  await dialog.getByLabel("What do you want?").selectOption("ACCESS");
  await dialog.getByLabel("About whom?").selectOption(s.studentId);
  await dialog.getByRole("button", { name: "Raise request" }).click();
  await expect(page).toHaveURL(/\/app\/my-privacy\/requests\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { name: "Copy of data" })).toBeVisible();
  await expect(page.getByTestId("request-timeline")).toContainText("Request raised");
  const requestId = page.url().split("/").pop()!;

  // The parent cannot reach the staff queue; another school cannot see the request.
  const parentToken = await loginViaApi(request, s.parent);
  expect((await request.get("/api/privacy/requests", { headers: { Authorization: `Bearer ${parentToken}` } })).status()).toBe(403);
  const other = newSchool("priv-other");
  await signupViaApi(request, other);
  const otherToken = await loginViaApi(request, adminCredentials(other));
  const asOther = { headers: { Authorization: `Bearer ${otherToken}` } };
  expect((await request.get(`/api/privacy/requests/${requestId}`, asOther)).status()).toBe(404);
  expect((await request.get(`/api/privacy/students/${s.studentId}/consents`, asOther)).status()).toBe(404);
});

test("staff answer an access request with a data file and erase a left student's data", async ({ page, request }) => {
  const s = await setUpSchool(request, "priv-erase");
  await publishNotice(request, s);
  const parentToken = await loginViaApi(request, s.parent);
  const asParent = { headers: { Authorization: `Bearer ${parentToken}` } };
  const accepted = await request.post("/api/me/privacy/consent", {
    ...asParent,
    data: { noticeVersion: 1, acceptEssential: true, choices: [{ studentId: s.studentId, photos: false, whatsapp: false }] },
  });
  expect(accepted.status(), await accepted.text()).toBe(200);
  const raise = async (type: string) => {
    const res = await request.post("/api/me/privacy/requests", {
      ...asParent,
      data: { type, subject: "CHILD", studentId: s.studentId, details: type === "ERASURE" ? "We have moved away." : null },
    });
    expect(res.status(), await res.text()).toBe(201);
    return ((await res.json()) as { id: string }).id;
  };
  const access = await raise("ACCESS");
  const erasure = await raise("ERASURE");

  // The queue lists both; the access request gets a data file.
  await loginViaUi(page, s.admin);
  await mainNav(page).getByRole("link", { name: "Data protection" }).click();
  const queue = page.getByTestId("requests-table");
  await expect(queue.getByRole("row")).toHaveCount(3);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByTestId("requests-cards").getByRole("listitem")).toHaveCount(2);
  await expectNoHorizontalScroll(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await queue.getByRole("row").filter({ hasText: "Copy of data" }).getByRole("link", { name: "Meena Mehta" }).click();
  await expect(page).toHaveURL(new RegExp(`/app/privacy/requests/${access}$`));
  const exportCard = page.getByTestId("export-card");
  await exportCard.getByRole("button", { name: "Make the data file" }).click();
  await expect(exportCard).toContainText(/data-export-.*\.zip/);
  await page.setViewportSize({ width: 820, height: 1180 });
  await expectNoHorizontalScroll(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    exportCard.getByRole("button", { name: "Download" }).click(),
  ]);
  expect(download.suggestedFilename()).toMatch(/^data-export-.*\.zip$/);

  // The parent downloads the same ZIP with their own sign-in.
  const zip = await request.get(`/api/me/privacy/requests/${access}/export`, asParent);
  expect(zip.status()).toBe(200);
  expect(zip.headers()["content-type"]).toContain("application/zip");
  expect((await zip.body()).subarray(0, 2).toString("latin1")).toBe("PK");

  await page.getByRole("button", { name: "Close request…" }).click();
  const closeDialog = page.getByRole("dialog", { name: "Close the request" });
  await closeDialog.getByRole("radio", { name: "Completed" }).check();
  await closeDialog.getByRole("button", { name: "Close request" }).click();
  await expect(closeDialog).toBeHidden();
  await expect(page.getByTestId("request-timeline")).toContainText("Closed");

  // Erasure waits until the student has left.
  await page.goto(`/app/privacy/requests/${erasure}`);
  await expect(page.getByTestId("erase-not-left")).toBeVisible();
  const left = await request.post(`/api/students/${s.studentId}/leave`, {
    headers: { Authorization: `Bearer ${s.adminToken}` },
    data: { status: "TRANSFERRED", leftOn: indiaDate(), reason: "Family moved" },
  });
  expect(left.status(), await left.text()).toBe(200);
  await page.reload();

  const eraseCard = page.getByTestId("erase-card");
  await eraseCard.getByRole("button", { name: "Erase data…" }).click();
  const eraseDialog = page.getByRole("dialog", { name: "Erase the student's data" });
  const submit = eraseDialog.getByRole("button", { name: "Erase for good" });
  await expect(submit).toBeDisabled();
  await eraseDialog.getByLabel(/Type the admission number/).fill(s.admissionNo);
  await submit.click();
  await expect(eraseDialog).toBeHidden();
  await expect(eraseCard).toContainText("Erased on");

  const record = await request.get(`/api/students/${s.studentId}`, { headers: { Authorization: `Bearer ${s.adminToken}` } });
  expect(((await record.json()) as { fullName: string }).fullName).toBe("Erased student");
});
