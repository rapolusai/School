import { expect, test, type APIRequestContext } from "@playwright/test";
import { adminCredentials, expectNoHorizontalScroll, loginViaApi, loginViaUi, mainNav, newSchool, signupViaApi } from "./support/helpers";

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
  return {
    post: async <T = { id: string }>(path: string, data?: unknown, status = 201): Promise<T> => {
      const res = await request.post(path, { ...auth, data });
      expect(res.status(), `POST ${path}: ${await res.text()}`).toBe(status);
      return (await res.json()) as T;
    },
    put: async <T = { id: string }>(path: string, data?: unknown): Promise<T> => {
      const res = await request.put(path, { ...auth, data });
      expect(res.status(), `PUT ${path}: ${await res.text()}`).toBe(200);
      return (await res.json()) as T;
    },
  };
}

/** Through the API: a current year, Class 1 A with two students, and three admissions applications for Class 1. */
async function setUpSchool(request: APIRequestContext, auth: Auth) {
  const { post } = client(request, auth);
  const year = academicYear();
  const { id: yearId } = await post("/api/academics/years", { ...year, current: true });
  const { id: class1 } = await post("/api/academics/classes", { name: "Class 1" });
  const { id: section } = await post(`/api/academics/classes/${class1}/sections`, { name: "A", capacity: 40 });
  for (const [i, [first, last, gender]] of [
    ["Asha", "Rao", "FEMALE"],
    ["Bala", "Iyer", "MALE"],
  ].entries()) {
    await post("/api/students", {
      admissionNo: `E2E/GOV/${i + 1}`,
      firstName: first,
      lastName: last,
      dateOfBirth: "2019-05-01",
      gender,
      admissionDate: year.startsOn,
      sectionId: section,
      rollNo: i + 1,
      guardians: [{ name: `Parent ${last}`, relation: "MOTHER", phone: `98765 4000${i}`, primary: true }],
    });
  }
  const children = ["Meher", "Aarav", "Lasya"];
  for (const [i, first] of children.entries()) {
    await post("/api/admissions/applications", {
      stage: "APPLICATION",
      firstName: first,
      lastName: "Kumar",
      dateOfBirth: `${Number(year.startsOn.slice(0, 4)) - 6}-06-0${i + 1}`,
      gender: i === 1 ? "MALE" : "FEMALE",
      classId: class1,
      academicYearId: yearId,
      source: "WALK_IN",
      guardians: [{ name: `Guardian ${first}`, relation: "MOTHER", phone: `98765 5000${i}`, primary: true }],
    });
  }
  return { year, yearId, class1, children };
}

test("the School Admin fills the government profile, enters a section's fields, downloads the UDISE+ workbook and runs the RTE lottery", async ({
  page,
  request,
}) => {
  test.setTimeout(180_000);
  const school = newSchool("gov");
  await signupViaApi(request, school);
  const auth = { headers: { Authorization: `Bearer ${await loginViaApi(request, adminCredentials(school))}` } };
  const { children } = await setUpSchool(request, auth);

  // 1. The school profile: fill the UDISE code and the category, save, and the completeness goes up.
  await page.addInitScript(() => {
    window.print = () => {};
  });
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Government reporting", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/govreport$/);
  await expect(page.getByTestId("govreport-verify")).toContainText("Verify against the current portal template");
  const profile = page.getByTestId("govreport-profile-form");
  await profile.getByLabel("UDISE code", { exact: true }).fill("123");
  await profile.getByRole("button", { name: "Save profile" }).click();
  await expect(profile.getByText("A UDISE code has 11 digits.")).toBeVisible();
  await profile.getByLabel("UDISE code", { exact: true }).fill("36011200912");
  await expect(profile.getByLabel("School category", { exact: true }).locator("option[value='CAT3']")).toBeAttached();
  await profile.getByLabel("School category", { exact: true }).selectOption("CAT3");
  await profile.getByLabel("Board", { exact: true }).fill("CBSE");
  await profile.getByLabel("Classrooms", { exact: true }).fill("24");
  await profile.getByLabel("Drinking water", { exact: true }).selectOption("Y");
  await profile.getByRole("button", { name: "Save profile" }).click();
  await expect(page.getByText("Profile saved.")).toBeVisible();
  await expect(page.getByTestId("govreport-profile-completeness")).toContainText("5 of 25 fields filled");

  // 2. Students' fields for Class 1 A: Asha's APAAR ID, PEN, category, religion, mother tongue, BPL and RTE.
  await page.getByRole("link", { name: "Student fields", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/govreport\/students$/);
  const grid = page.getByTestId("govreport-grid");
  await expect(grid.getByTestId("govreport-grid-summary")).toHaveText("0 of 2 complete");
  const asha = grid.getByTestId("govreport-grid-row").filter({ hasText: "Asha Rao" });
  await asha.getByLabel("APAAR ID", { exact: true }).fill("123456789012");
  await asha.getByLabel("PEN", { exact: true }).fill("TS260001");
  await asha.getByLabel("Social category", { exact: true }).selectOption("OBC");
  await asha.getByLabel("Religion", { exact: true }).selectOption("HINDU");
  await asha.getByLabel("Mother tongue", { exact: true }).selectOption("TELUGU");
  await asha.getByLabel("BPL", { exact: true }).selectOption("N");
  await asha.getByLabel("Admitted under RTE", { exact: true }).selectOption("N");
  await grid.getByRole("button", { name: "Save changes (1)" }).click();
  await expect(page.getByText("Saved 1 students.")).toBeVisible();
  await expect(grid.getByTestId("govreport-grid-summary")).toHaveText("1 of 2 complete");
  await expect(asha.getByText("Complete", { exact: true })).toBeVisible();

  // 3. The UDISE+ preview and the workbook download (audited by the API).
  await page.getByRole("link", { name: "Exports", exact: true }).click();
  const preview = page.getByTestId("govreport-export-preview");
  await expect(preview.getByTestId("govreport-export-table-enrolment")).toContainText("Class 1");
  await expect(preview.getByTestId("govreport-export-table-school")).toContainText("36011200912");
  const download = page.waitForEvent("download");
  await preview.getByTestId("govreport-download-workbook").click();
  expect((await download).suggestedFilename()).toMatch(/^udise-plus-\d{4}-\d{2}-\d{2}\.xlsx$/);
  const audit = await (await request.get("/api/audit-events?limit=20", auth)).text();
  expect(audit).toContain("govreport_export.downloaded");

  // 4. The RTE intake for Class 1: two RTE seats, three applications from admissions, screened, and the lottery.
  await page.getByRole("link", { name: "RTE 25%", exact: true }).click();
  const create = page.getByTestId("rte-new-intake");
  await create.getByLabel("Entry class", { exact: true }).selectOption({ label: "Class 1" });
  await create.getByLabel("Seats in the class", { exact: true }).fill("8");
  await create.getByLabel("State code", { exact: true }).fill("TS");
  await create.getByRole("button", { name: "Create intake" }).click();
  await expect(page.getByText("Intake created.")).toBeVisible();
  await page.getByTestId("rte-intake-link").click();
  await expect(page).toHaveURL(/\/app\/govreport\/rte\/[0-9a-f-]+$/);
  const adder = page.getByTestId("rte-add-application");
  for (const child of children) {
    await adder.getByLabel("Add an admissions application", { exact: true }).selectOption({ label: `${child} Kumar` });
    await adder.getByRole("button", { name: "Add", exact: true }).click();
    await expect(page.getByTestId("rte-application").filter({ hasText: `${child} Kumar` })).toBeVisible();
  }
  for (const [i, child] of children.entries()) {
    await page.getByTestId("rte-application").filter({ hasText: `${child} Kumar` }).getByTestId("rte-screen").click();
    const dialog = page.getByRole("dialog", { name: `Screen ${child} Kumar` });
    await dialog.getByLabel("Distance from school (km)", { exact: true }).fill(String(0.5 + i));
    await dialog.getByLabel("The family has declared its income").check();
    await dialog.getByRole("button", { name: "Save screening" }).click();
    await expect(dialog).toBeHidden();
  }
  await expect(page.getByTestId("rte-application").filter({ hasText: "Eligible" })).toHaveCount(3);
  const lottery = page.getByTestId("rte-lottery");
  await lottery.getByLabel("Witness", { exact: true }).fill("Mrs Rao, SMC member");
  await lottery.getByRole("button", { name: "Run lottery" }).click();
  await expect(page.getByText("Lottery run recorded.")).toBeVisible();
  await expect(page.getByTestId("rte-screen")).toHaveCount(0);
  await page.getByTestId("rte-run-link").click();
  await expect(page).toHaveURL(/\/app\/govreport\/rte\/runs\/[0-9a-f-]+$/);
  const list = page.getByTestId("rte-run-list");
  await expect(list).toContainText("witness: Mrs Rao, SMC member");
  await expect(list.getByTestId("rte-allotment")).toHaveCount(3);
  await expect(list.getByText("Allotted")).toHaveCount(2);
  await expect(list.getByText("Waitlist 1")).toBeVisible();
  await page.getByTestId("rte-toggle-letters").click();
  await expect(page.getByTestId("rte-letters").getByRole("article")).toHaveCount(2);

  // 5. On a phone the grid is cards and nothing scrolls sideways.
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/app/govreport/students");
  await expect(page.getByTestId("govreport-grid")).toBeVisible();
  await expectNoHorizontalScroll(page);
});
