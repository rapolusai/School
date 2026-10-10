import { readFileSync } from "node:fs";
import { expect, test, type APIRequestContext } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  PASSWORD,
  signupViaApi,
} from "./support/helpers";

/** "YYYY-MM-DD" of a UTC date. */
const iso = (date: Date) => date.toISOString().slice(0, 10);

/** Today in India as a UTC-midnight date. */
function todayInIndia(): Date {
  const india = new Date(Date.now() + 330 * 60_000);
  return new Date(Date.UTC(india.getUTCFullYear(), india.getUTCMonth(), india.getUTCDate()));
}

/** The Indian academic year (April to March) that contains today. */
function academicYearAround(today: Date) {
  const year = today.getUTCMonth() >= 3 ? today.getUTCFullYear() : today.getUTCFullYear() - 1;
  return {
    startsOn: `${year}-04-01`,
    endsOn: `${year + 1}-03-31`,
    name: `${year}-${String((year + 1) % 100).padStart(2, "0")}`,
  };
}

/** The latest school day (Monday to Saturday) up to today, but not before the year starts. */
function lastSchoolDay(today: Date, startsOn: string): string {
  const day = today.getUTCDay() === 0 ? new Date(today.getTime() - 86_400_000) : today;
  return iso(day) < startsOn ? iso(today) : iso(day);
}

type Auth = { headers: { Authorization: string } };

async function send<T>(request: APIRequestContext, method: "post" | "put", path: string, auth: Auth, data?: unknown) {
  const res = await request[method](path, { ...auth, data });
  expect(res.status(), `${path}: ${await res.text()}`).toBeLessThan(300);
  return (await res.json()) as T;
}

test("the principal reads the dashboard, opens attendance by class and section, downloads it and prints it", async ({
  page,
  request,
}) => {
  test.setTimeout(150_000);
  // Printing is stubbed: the test checks the print button calls it and what the print stylesheet shows.
  await page.addInitScript(() => {
    (window as unknown as { printed: number }).printed = 0;
    window.print = () => {
      (window as unknown as { printed: number }).printed += 1;
    };
  });

  const school = newSchool("reports");
  await signupViaApi(request, school);
  const asAdmin = { headers: { Authorization: `Bearer ${await loginViaApi(request, adminCredentials(school))}` } };

  // Through the API: the current year, Class 5 A (three students) and 5 B (one), a principal, and one day marked.
  const today = todayInIndia();
  const year = academicYearAround(today);
  await send(request, "post", "/api/academics/years", asAdmin, { ...year, current: true });
  const { id: classId } = await send<{ id: string }>(request, "post", "/api/academics/classes", asAdmin, {
    name: "Class 5",
  });
  const sectionA = await send<{ id: string }>(request, "post", `/api/academics/classes/${classId}/sections`, asAdmin, {
    name: "A",
    capacity: 40,
  });
  const sectionB = await send<{ id: string }>(request, "post", `/api/academics/classes/${classId}/sections`, asAdmin, {
    name: "B",
    capacity: 40,
  });
  const students: { id: string; sectionId: string }[] = [];
  for (const [i, [first, sectionId]] of (
    [
      ["Kabir", sectionA.id],
      ["Bala", sectionA.id],
      ["Chitra", sectionA.id],
      ["Dev", sectionB.id],
    ] as const
  ).entries()) {
    const student = await send<{ id: string }>(request, "post", "/api/students", asAdmin, {
      admissionNo: `E2E/REP/${i + 1}`,
      firstName: first,
      lastName: "Rao",
      dateOfBirth: "2016-04-12",
      gender: first === "Chitra" ? "FEMALE" : "MALE",
      admissionDate: year.startsOn,
      sectionId,
      rollNo: i + 1,
      guardians: [{ name: `Parent of ${first}`, relation: "MOTHER", phone: `98765 1000${i}`, primary: true }],
    });
    students.push({ id: student.id, sectionId });
  }
  const principal = { name: "Lakshmi Iyer", email: `lakshmi@${school.schoolCode}.example.com`, password: PASSWORD };
  await send(request, "post", "/api/users", asAdmin, { ...principal, roles: ["PRINCIPAL"] });
  const day = lastSchoolDay(today, year.startsOn);
  // Class 5 A: present, absent, late (2.0 of 3 days = 66.7%); Class 5 B: present (100%).
  await send(request, "put", `/api/attendance/registers/${sectionA.id}/${day}`, asAdmin, {
    entries: [
      { studentId: students[0].id, status: "PRESENT" },
      { studentId: students[1].id, status: "ABSENT" },
      { studentId: students[2].id, status: "LATE" },
    ],
  });
  await send(request, "put", `/api/attendance/registers/${sectionB.id}/${day}`, asAdmin, {
    entries: [{ studentId: students[3].id, status: "PRESENT" }],
  });

  // 1. The principal's dashboard: tiles and the attendance trend.
  await loginViaUi(page, { schoolCode: school.schoolCode, email: principal.email, password: principal.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const dashboard = page.getByTestId("staff-dashboard");
  await expect(dashboard).toHaveAttribute("data-layout", "leadership");
  await expect(dashboard.getByTestId("tile-students")).toContainText("4");
  await expect(dashboard.getByTestId("tile-attendanceToday")).toBeVisible();
  const trend = dashboard.getByTestId("chart-trend");
  await expect(trend).toContainText("Attendance, last 30 school days");
  await expect(trend.locator(".chart-dot")).toHaveCount(1);
  await expect(dashboard.getByTestId("chart-funnel")).toContainText("No enquiries yet for this year.");

  // 2. The reports hub, then attendance by class and section from the day marked.
  await mainNav(page).getByRole("link", { name: "Reports", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/reports$/);
  await expect(page.getByTestId("reports-group-attendance")).toBeVisible();
  await page.getByTestId("report-link-sections").click();
  await expect(page).toHaveURL(/\/app\/reports\/attendance$/);
  const report = page.getByTestId("report-sections");
  await report.getByLabel("From", { exact: true }).fill(day);
  const rows = report.getByTestId("sections-table").getByTestId("section-row");
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toContainText("Class 5 A");
  await expect(rows.nth(0)).toContainText("66.7%");
  await expect(rows.nth(1)).toContainText("Class 5 B");
  await expect(rows.nth(1)).toContainText("100%");
  await expect(report.getByTestId("overall-percent")).toHaveText("Attendance75%");

  // 3. Excel: a real .xlsx (a zip) named after the dates.
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download Excel" }).click();
  const file = await download;
  const name = `attendance-by-section-${day}-to-${iso(today)}.xlsx`;
  expect(file.suggestedFilename()).toBe(name);
  const bytes = readFileSync(await file.path());
  expect(bytes.subarray(0, 2).toString("latin1")).toBe("PK");
  await expect(page.getByRole("status").filter({ hasText: "Downloaded" })).toHaveText(`Downloaded ${name}`);

  // 4. Print: the button prints, and on paper only the report shows, with the school, title, filters and date.
  await page.getByRole("button", { name: "Print or save as PDF" }).click();
  expect(await page.evaluate(() => (window as unknown as { printed: number }).printed)).toBe(1);
  await page.emulateMedia({ media: "print" });
  const head = report.getByTestId("report-print-head");
  await expect(head).toBeVisible();
  await expect(head).toContainText(school.schoolName);
  await expect(head).toContainText("Attendance by class and section");
  await expect(head).toContainText("All classes");
  await expect(head).toContainText("Printed");
  await expect(head).toContainText("by Lakshmi Iyer");
  await expect(report.getByTestId("sections-table")).toBeVisible();
  await expect(mainNav(page)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Download Excel" })).toBeHidden();
  await expect(report.getByLabel("From", { exact: true })).toBeHidden();
  await page.emulateMedia({ media: "screen" });
  await expect(head).toBeHidden();

  // 5. On a phone the dashboard and the report fit the screen.
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/app/dashboard");
  await expect(page.getByTestId("dashboard-tiles")).toBeVisible();
  await expect(page.getByTestId("chart-trend")).toBeVisible();
  await expectNoHorizontalScroll(page);
  await page.goto("/app/reports/attendance");
  await expect(page.getByTestId("sections-cards")).toBeVisible();
  await expectNoHorizontalScroll(page);
});

test("a teacher gets their own dashboard and no school-wide reports", async ({ page, request }) => {
  const school = newSchool("reports-t");
  await signupViaApi(request, school);
  const asAdmin = { headers: { Authorization: `Bearer ${await loginViaApi(request, adminCredentials(school))}` } };
  const year = academicYearAround(todayInIndia());
  await send(request, "post", "/api/academics/years", asAdmin, { ...year, current: true });
  const teacher = { name: "Ravi Kumar", email: `ravi@${school.schoolCode}.example.com`, password: PASSWORD };
  await send(request, "post", "/api/users", asAdmin, { ...teacher, roles: ["TEACHER"] });

  await loginViaUi(page, { schoolCode: school.schoolCode, email: teacher.email, password: teacher.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("staff-dashboard")).toHaveAttribute("data-layout", "teacher");
  await expect(page.getByTestId("tile-homework")).toBeVisible();
  await expect(page.getByTestId("chart-collections")).toHaveCount(0);

  await mainNav(page).getByRole("link", { name: "Reports", exact: true }).click();
  await expect(page.getByTestId("reports-group-attendance")).toBeVisible();
  await expect(page.getByTestId("reports-group-fees")).toHaveCount(0);
  await expect(page.getByTestId("report-link-leave")).toHaveCount(0);
  // The staff and fee reports stay closed to a teacher, on screen and in the API.
  await page.goto("/app/reports/leave");
  await expect(page.getByTestId("access-denied")).toBeVisible();
  const asTeacher = {
    headers: {
      Authorization: `Bearer ${await loginViaApi(request, { schoolCode: school.schoolCode, email: teacher.email, password: teacher.password })}`,
    },
  };
  expect((await request.get("/api/reports/staff/leave.xlsx", asTeacher)).status()).toBe(403);
  expect((await request.get("/api/reports/admissions/funnel", asTeacher)).status()).toBe(403);
});
