import { expect, test, type APIRequestContext } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  PASSWORD,
  signOut,
  signupViaApi,
} from "./support/helpers";

/** Today in India as "YYYY-MM-DD", moved by `days`. */
function indiaDate(days = 0): string {
  const india = new Date(Date.now() + 330 * 60_000 + days * 86_400_000);
  return india.toISOString().slice(0, 10);
}

/** The Indian academic year (April to March) that contains today, e.g. 2026-04-01 to 2027-03-31. */
function academicYearAround(today: string) {
  const [y, m] = today.split("-").map(Number);
  const year = m >= 4 ? y : y - 1;
  return {
    startsOn: `${year}-04-01`,
    endsOn: `${year + 1}-03-31`,
    name: `${year}-${String((year + 1) % 100).padStart(2, "0")}`,
  };
}

type Auth = { headers: { Authorization: string } };

/** JSON calls as the admin, expecting `status` (201 for POST, 200 for PUT unless given). */
function client(request: APIRequestContext, auth: Auth) {
  const call = async <T>(method: "post" | "put", path: string, data: unknown, status: number): Promise<T> => {
    const res = await request[method](path, { ...auth, data });
    expect(res.status(), `${method.toUpperCase()} ${path}: ${await res.text()}`).toBe(status);
    return (await res.json()) as T;
  };
  return {
    post: <T = { id: string }>(path: string, data: unknown, status = 201) => call<T>("post", path, data, status),
    put: <T = unknown>(path: string, data: unknown, status = 200) => call<T>("put", path, data, status),
  };
}

/** A small file that the API recognises as a PDF from its contents. */
const PDF = Buffer.from("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n");

/**
 * Through the API: the current year, two teachers, Class 5 A and B with three subjects, a bell schedule,
 * subject teachers, a student with a sign-in, and a timetable in which moving Class 5 A's English to
 * Ravi Kumar puts him in Class 5 A and Class 5 B on Monday, period 1.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const { post, put } = client(request, auth);
  const ravi = { name: "Ravi Kumar", email: `ravi@${schoolCode}.example.com`, password: PASSWORD };
  const sunita = { name: "Sunita Verma", email: `sunita@${schoolCode}.example.com`, password: PASSWORD };
  const { id: raviId } = await post("/api/users", { ...ravi, roles: ["TEACHER"] });
  const { id: sunitaId } = await post("/api/users", { ...sunita, roles: ["TEACHER"] });

  const year = academicYearAround(indiaDate());
  await post("/api/academics/years", { ...year, current: true });
  const { id: classId } = await post("/api/academics/classes", { name: "Class 5" });
  const sections = `/api/academics/classes/${classId}/sections`;
  const { id: sectionA } = await post(sections, { name: "A", capacity: 40, classTeacherId: raviId });
  const { id: sectionB } = await post(sections, { name: "B", capacity: 40 });

  const { id: maths } = await post("/api/academics/subjects", { name: "Mathematics" });
  const { id: english } = await post("/api/academics/subjects", { name: "English" });
  const { id: hindi } = await post("/api/academics/subjects", { name: "Hindi" });
  await put(`/api/academics/classes/${classId}/subjects`, { subjectIds: [maths, english, hindi] });

  const period = (n: number, startsAt: string, endsAt: string) => ({
    label: `Period ${n}`,
    startsAt,
    endsAt,
    breakTime: false,
  });
  await put("/api/timetable/bell-schedule", {
    workingDays: ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
    saturdaySchedule: false,
    weekday: [
      period(1, "08:30", "09:10"),
      period(2, "09:10", "09:50"),
      { label: "Short break", startsAt: "09:50", endsAt: "10:05", breakTime: true },
      period(3, "10:05", "10:45"),
    ],
  });

  const assign = (sectionId: string, subjectId: string, teacherId: string) =>
    post("/api/timetable/assignments", { sectionId, subjectId, teacherId, periodsPerWeek: 5 });
  await assign(sectionA, maths, raviId);
  const { id: englishA } = await assign(sectionA, english, sunitaId);
  await assign(sectionA, hindi, sunitaId);
  await assign(sectionB, maths, raviId);

  await put(`/api/timetable/sections/${sectionA}`, {
    slots: [
      { day: "MONDAY", period: 1, subjectId: english, teacherId: null, room: "Room 12" },
      { day: "MONDAY", period: 2, subjectId: maths, teacherId: null },
    ],
  });
  await put(`/api/timetable/sections/${sectionB}`, {
    slots: [{ day: "MONDAY", period: 1, subjectId: maths, teacherId: null }],
  });

  // Ravi Kumar takes over Class 5 A's English: its Monday period 1 now clashes with his Class 5 B maths.
  const moved = await put<{ periodsMoved: number; clashes: unknown[] }>(`/api/timetable/assignments/${englishA}`, {
    teacherId: raviId,
    periodsPerWeek: 5,
  });
  expect(moved.periodsMoved).toBe(1);
  expect(moved.clashes).toHaveLength(1);

  const student = { email: `kabir@${schoolCode}.example.com`, password: PASSWORD };
  const { id: studentId } = await post("/api/students", {
    admissionNo: "E2E/HW/1",
    firstName: "Kabir",
    lastName: "Mehta",
    dateOfBirth: "2016-04-12",
    gender: "MALE",
    admissionDate: year.startsOn,
    sectionId: sectionA,
    rollNo: 1,
    guardians: [{ name: "Meena Mehta", relation: "MOTHER", phone: "98765 00001", primary: true }],
  });
  await post(`/api/students/${studentId}/sign-in`, { mode: "CREATE", ...student }, 200);

  return { ravi, student, year, sectionA };
}

test("admin fixes a clash in the Class 5 A timetable; teacher sets homework with a PDF; student hands it in; teacher reviews", async ({
  page,
  request,
}) => {
  test.setTimeout(150_000);
  const school = newSchool("tthw");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin = { headers: { Authorization: `Bearer ${adminToken}` } };
  const { ravi, student, year, sectionA } = await setUpSchool(request, school.schoolCode, asAdmin);

  // 1. The admin finds the clash and fixes it in the Class 5 A editor.
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Timetable" }).click();
  await expect(page).toHaveURL(/\/app\/timetable$/);
  await page.getByRole("tab", { name: "Clash check" }).click();
  const report = page.getByTestId("clash-report");
  await expect(report.getByRole("heading", { name: "1 clash" })).toBeVisible();
  const clash = report.getByTestId("clash-list");
  await expect(clash).toContainText(/^Ravi Kumar is in .* on Monday, period 1\./);
  await expect(clash).toContainText("Class 5 A (English)");
  await expect(clash).toContainText("Class 5 B (Mathematics)");
  await report.getByRole("button", { name: "Open Class 5 A" }).click();

  await expect(page.getByRole("tab", { name: "Sections" })).toHaveAttribute("aria-selected", "true");
  const editor = page.getByRole("form", { name: "Edit the timetable of Class 5 A" });
  const monday1 = editor.getByTestId("cell-MONDAY-1");
  await expect(monday1).toContainText("Ravi Kumar is teaching Class 5 B then");
  await expect(editor.getByTestId("editor-status")).toHaveText("1 period clashes with another section.");

  await editor.getByLabel("Monday, period 1", { exact: true }).selectOption({ label: "Hindi" });
  await expect(monday1).not.toContainText("is teaching");
  await expect(monday1).toContainText("Sunita Verma");
  await expect(editor.getByTestId("editor-status")).toHaveText("No clashes.");
  await editor.getByTestId("timetable-save").click();
  await expect(page.getByText("Timetable saved for Class 5 A.")).toBeVisible();

  await page.getByRole("tab", { name: "Clash check" }).click();
  await expect(page.getByText("No clashes: no teacher is in two places at once.")).toBeVisible();
  const clashes = await request.get("/api/timetable/clashes", asAdmin);
  expect(((await clashes.json()) as { clashes: unknown[] }).clashes).toHaveLength(0);
  await signOut(page);

  // 2. Ravi Kumar sets homework for Class 5 A with a PDF.
  const dueOn = indiaDate(2) <= year.endsOn ? indiaDate(2) : year.endsOn;
  await loginViaUi(page, { schoolCode: school.schoolCode, email: ravi.email, password: ravi.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("today-classes")).toBeVisible();
  await mainNav(page).getByRole("link", { name: "Homework" }).click();
  await expect(page).toHaveURL(/\/app\/homework$/);
  await page.getByRole("button", { name: "Set homework" }).click();
  const form = page.getByRole("dialog", { name: "Set homework" });
  await form.getByRole("checkbox", { name: "Class 5 A" }).check();
  await form.getByLabel("Subject").selectOption({ label: "Mathematics" });
  await form.getByLabel("Title").fill("Tables 12 to 15");
  await form.getByLabel("Instructions").fill("Write out the tables from 12 to 15 and learn them.");
  await form.getByLabel("Due on").fill(dueOn);
  await form.locator('input[name="attachments"]').setInputFiles({
    name: "tables-12-15.pdf",
    mimeType: "application/pdf",
    buffer: PDF,
  });
  await expect(form.getByRole("button", { name: "Remove tables-12-15.pdf" })).toBeVisible();
  await form.getByTestId("homework-save").click();
  await expect(page.getByText("Homework saved: Tables 12 to 15.")).toBeVisible();
  await expect(page).toHaveURL(/\/app\/homework\/[0-9a-f-]+$/);
  const homeworkId = page.url().split("/").pop()!;
  await expect(page.getByRole("heading", { name: "Tables 12 to 15" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Download tables-12-15.pdf" })).toBeVisible();
  await expect(page.getByTestId("tracker-counts")).toContainText("0 of 1 handed in");
  await signOut(page);

  // 3. Kabir opens it from his dashboard on a phone, downloads the sheet and hands in his answer.
  await page.setViewportSize({ width: 390, height: 844 });
  await loginViaUi(page, { schoolCode: school.schoolCode, ...student });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const due = page.getByTestId("student-homework-due");
  await expect(due).toContainText("1 to hand in");
  await due.getByRole("link", { name: /Tables 12 to 15/ }).click();
  await expect(page).toHaveURL(new RegExp(`/app/homework/${homeworkId}$`));
  await expect(page.getByRole("heading", { name: "Hand in your work" })).toBeVisible();
  await expectNoHorizontalScroll(page);

  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download tables-12-15.pdf" }).click();
  expect((await download).suggestedFilename()).toBe("tables-12-15.pdf");

  await page.getByLabel("Your answer").fill("12 x 1 = 12, 12 x 2 = 24 ... 15 x 10 = 150");
  await page.getByTestId("submission-send").click();
  await expect(page.getByText("Handed in. Your teacher will review it.")).toBeVisible();
  await expect(page.getByTestId("submission")).toContainText("12 x 1 = 12");
  await expect(page.getByRole("heading", { name: "Hand in again" })).toBeVisible();

  const studentToken = await loginViaApi(request, { schoolCode: school.schoolCode, ...student });
  const asStudent = { headers: { Authorization: `Bearer ${studentToken}` } };
  expect((await request.get("/api/homework", asStudent)).status()).toBe(403);
  expect((await request.get(`/api/timetable/sections/${sectionA}`, asStudent)).status()).toBe(403);
  await signOut(page);

  // 4. Ravi Kumar reviews it.
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginViaUi(page, { schoolCode: school.schoolCode, email: ravi.email, password: ravi.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto(`/app/homework/${homeworkId}`);
  await expect(page.getByTestId("tracker-counts")).toContainText("1 of 1 handed in");
  const row = page.getByTestId("tracker-row").filter({ hasText: "Kabir Mehta" });
  await expect(row).toContainText("Handed in");
  await row.getByRole("button", { name: "Review" }).click();
  const review = page.getByRole("dialog", { name: "Review Kabir Mehta's homework" });
  await expect(review).toContainText("12 x 1 = 12");
  await review.getByLabel("Grade (optional)").fill("A");
  await review.getByLabel("Remark (optional)").fill("Neat work.");
  await review.getByTestId("review-save").click();
  await expect(page.getByText("Review saved for Kabir Mehta.")).toBeVisible();
  await expect(row).toContainText("Reviewed");
  await expect(row).toContainText("Neat work.");

  // Kabir sees the review.
  const mine = await request.get(`/api/me/homework/${homeworkId}`, asStudent);
  expect(mine.status()).toBe(200);
  const detail = (await mine.json()) as { submission: { status: string; grade: string; remark: string } };
  expect(detail.submission).toMatchObject({ status: "REVIEWED", grade: "A", remark: "Neat work." });
});
