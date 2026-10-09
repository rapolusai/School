import { expect, test, type APIRequestContext } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  signupViaApi,
  type School,
} from "./support/helpers";

/** The Indian academic year (April to March) that contains today, e.g. 2026-04-01 to 2027-03-31. */
function academicYearAround(now: Date) {
  const india = new Date(now.getTime() + 330 * 60_000);
  const year = india.getUTCMonth() >= 3 ? india.getUTCFullYear() : india.getUTCFullYear() - 1;
  return {
    startsOn: `${year}-04-01`,
    endsOn: `${year + 1}-03-31`,
    name: `${year}-${String((year + 1) % 100).padStart(2, "0")}`,
  };
}

/** The current year, class LKG and its section A, set up through the API. */
async function setUpSchool(request: APIRequestContext, school: School) {
  const token = await loginViaApi(request, adminCredentials(school));
  const headers = { Authorization: `Bearer ${token}` };
  const year = academicYearAround(new Date());
  const created = await request.post("/api/academics/years", { headers, data: { ...year, current: true } });
  expect(created.ok(), await created.text()).toBe(true);
  const lkg = await request.post("/api/academics/classes", { headers, data: { name: "LKG", displayOrder: 1 } });
  expect(lkg.ok(), await lkg.text()).toBe(true);
  const { id: classId } = (await lkg.json()) as { id: string };
  const section = await request.post(`/api/academics/classes/${classId}/sections`, {
    headers,
    data: { name: "A", capacity: 30 },
  });
  expect(section.ok(), await section.text()).toBe(true);
  const { id: sectionId } = (await section.json()) as { id: string };
  return { headers, year, sectionId };
}

test("a parent enquires online and the school takes the child from the board to admission", async ({
  page,
  request,
}) => {
  const school = newSchool("adm");
  await signupViaApi(request, school);
  const { headers, year, sectionId } = await setUpSchool(request, school);

  // 1. A parent sends an enquiry from the school's public page, without signing in.
  await page.goto(`/enquire/${school.schoolCode}`);
  await expect(page.getByTestId("enquiry-school")).toHaveText(school.schoolName);
  await page.getByLabel("Your name").fill("Meera Iyer");
  await page.getByLabel("Mobile number").fill("98765 12345");
  await page.getByLabel("You are the child's").selectOption("MOTHER");
  await page.getByLabel("First name").fill("Anika");
  await page.getByLabel("Last name").fill("Iyer");
  await page.getByLabel("Date of birth").fill("2022-02-10");
  await page.getByLabel("Class", { exact: true }).selectOption("LKG");
  // The school has one year, so it is chosen already.
  await expect(page.getByRole("radio", { name: year.name })).toBeChecked();
  await page.getByRole("checkbox").check();
  await page.setViewportSize({ width: 390, height: 844 });
  await expectNoHorizontalScroll(page);
  await page.getByRole("button", { name: "Send enquiry" }).click();
  const sent = page.getByTestId("enquiry-sent");
  await expect(sent).toContainText("Thank you, Meera Iyer");
  await expect(sent).toContainText("98765 12345");
  await expectNoHorizontalScroll(page);
  await page.setViewportSize({ width: 1440, height: 900 });

  // 2. The admin finds it on the dashboard and on the admissions board.
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("admissions-card")).toContainText("New enquiries");
  await mainNav(page).getByRole("link", { name: "Admissions", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/admissions$/);
  const card = page.getByRole("article", { name: "Anika Iyer" });
  await expect(page.getByTestId("lane-ENQUIRY").getByRole("article", { name: "Anika Iyer" })).toBeVisible();
  await expect(card).toContainText(`LKG · ${year.name}`);

  // 3. Enquiry → application → offered, with the Move menu.
  await card.getByRole("button", { name: "Move Anika Iyer to another stage" }).click();
  await page.getByRole("menuitem", { name: "Move to application" }).click();
  let dialog = page.getByRole("dialog", { name: "Move to application" });
  await dialog.getByLabel("Note").fill("Form collected at the office");
  await dialog.getByRole("button", { name: "Move to application" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("Anika Iyer moved to Application.")).toBeVisible();
  await expect(page.getByTestId("lane-APPLICATION").getByRole("article", { name: "Anika Iyer" })).toBeVisible();

  await card.getByRole("button", { name: "Move Anika Iyer to another stage" }).click();
  await page.getByRole("menuitem", { name: "Offer a place" }).click();
  dialog = page.getByRole("dialog", { name: "Offer a place" });
  await expect(dialog.getByLabel("Offered on")).not.toHaveValue("");
  await dialog.getByRole("button", { name: "Offer a place" }).click();
  await expect(dialog).toBeHidden();
  const offered = page.getByTestId("lane-OFFERED").getByRole("article", { name: "Anika Iyer" });
  await expect(offered).toBeVisible();

  // The board scrolls inside its card on a tablet and stacks on a phone: the page never scrolls sideways.
  for (const viewport of [
    { width: 820, height: 1180 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport);
    await expect(offered).toBeAttached();
    await expectNoHorizontalScroll(page);
  }
  await page.setViewportSize({ width: 1440, height: 900 });

  // 4. Admit as a student.
  await offered.getByRole("button", { name: "Move Anika Iyer to another stage" }).click();
  await page.getByRole("menuitem", { name: "Admit as student" }).click();
  dialog = page.getByRole("dialog", { name: "Admit Anika Iyer" });
  await expect(dialog.getByLabel("Class and section")).toHaveValue(sectionId);
  await dialog.getByLabel("Admission number").fill("E2E/ADM/001");
  await dialog.getByLabel("Gender").selectOption("FEMALE");
  await dialog.getByRole("button", { name: "Admit", exact: true }).click();
  const done = dialog.getByTestId("admit-done");
  await expect(done).toContainText("Anika Iyer is admitted.");
  await done.getByRole("button", { name: "Close", exact: true }).click();
  await expect(dialog).toBeHidden();
  const admitted = page.getByTestId("lane-ADMITTED").getByRole("article", { name: "Anika Iyer" });
  await expect(admitted).toBeVisible();

  // 5. The application keeps its history and has a printable offer letter.
  await admitted.getByRole("link", { name: "Anika Iyer" }).click();
  await expect(page).toHaveURL(/\/app\/admissions\/[0-9a-f-]{36}$/);
  const applicationId = page.url().split("/").pop()!;
  const timeline = page.getByTestId("timeline");
  await expect(timeline).toContainText("Enquiry sent from the website");
  await expect(timeline).toContainText("Moved from Enquiry to Application");
  await expect(timeline).toContainText("Form collected at the office");
  await expect(timeline).toContainText("Moved from Application to Offered");
  await expect(timeline).toContainText("Admitted as E2E/ADM/001 in LKG A");
  await page.getByRole("link", { name: "Offer letter" }).click();
  await expect(page.getByTestId("offer-letter")).toContainText(
    `We are pleased to offer Anika Iyer a place in LKG for the academic year ${year.name}.`,
  );
  await page.goBack();
  await page.getByTestId("admitted-banner").getByRole("link", { name: "Open student record" }).click();
  await expect(page).toHaveURL(/\/app\/students\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Anika Iyer");
  await expect(page.getByTestId("guardians-list")).toContainText("Meera Iyer");

  // 6. Admitting again changes nothing, and another school cannot see the application.
  const again = await request.post(`/api/admissions/applications/${applicationId}/admit`, {
    headers,
    data: { sectionId, admissionNo: "E2E/ADM/002", gender: "FEMALE" },
  });
  expect(again.status(), await again.text()).toBe(200);
  const students = (await (await request.get("/api/students?q=Anika", { headers })).json()) as { total: number };
  expect(students.total).toBe(1);

  const other = newSchool("adm-b");
  await signupViaApi(request, other);
  const otherToken = await loginViaApi(request, adminCredentials(other));
  const asOther = { headers: { Authorization: `Bearer ${otherToken}` } };
  expect((await request.get(`/api/admissions/applications/${applicationId}`, asOther)).status()).toBe(404);
});
