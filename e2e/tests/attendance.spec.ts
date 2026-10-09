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

type Auth = { headers: { Authorization: string } };

async function post<T>(request: APIRequestContext, path: string, auth: Auth, data: unknown): Promise<T> {
  const res = await request.post(path, { ...auth, data });
  expect(res.status(), `${path}: ${await res.text()}`).toBe(201);
  return (await res.json()) as T;
}

const STUDENTS = [
  { first: "Kabir", last: "Mehta", guardian: "Meena Mehta", relation: "MOTHER", phone: "98765 00001" },
  { first: "Bala", last: "Iyer", guardian: "Uma Iyer", relation: "MOTHER", phone: "98765 00002" },
  { first: "Chitra", last: "Das", guardian: "Suresh Das", relation: "FATHER", phone: "98765 00003" },
] as const;

/**
 * Through the API: the current year, a class teacher, Class 5 A with three students, and alerts that
 * are not held for quiet hours (so the run does not depend on the time of day).
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const teacher = { name: "Ravi Kumar", email: `ravi@${schoolCode}.example.com`, password: PASSWORD };
  const { id: teacherId } = await post<{ id: string }>(request, "/api/users", auth, { ...teacher, roles: ["TEACHER"] });

  const year = academicYearAround(new Date());
  await post(request, "/api/academics/years", auth, { ...year, current: true });
  const { id: classId } = await post<{ id: string }>(request, "/api/academics/classes", auth, { name: "Class 5" });
  const { id: sectionId } = await post<{ id: string }>(request, `/api/academics/classes/${classId}/sections`, auth, {
    name: "A",
    capacity: 40,
    classTeacherId: teacherId,
  });

  for (const [i, s] of STUDENTS.entries()) {
    await post(request, "/api/students", auth, {
      admissionNo: `E2E/ATT/${i + 1}`,
      firstName: s.first,
      lastName: s.last,
      dateOfBirth: "2016-04-12",
      gender: s.first === "Kabir" ? "MALE" : "FEMALE",
      admissionDate: year.startsOn,
      sectionId,
      rollNo: i + 1,
      guardians: [{ name: s.guardian, relation: s.relation, phone: s.phone, primary: true }],
    });
  }

  const settings = await request.put("/api/notifications/settings", {
    ...auth,
    data: {
      absenceAlertsEnabled: true,
      absenceAlertChannel: "WHATSAPP_SMS",
      alertLanguage: "en",
      quietHoursEnabled: false,
      quietHoursStart: "21:00",
      quietHoursEnd: "07:00",
    },
  });
  expect(settings.status(), await settings.text()).toBe(200);
  return { teacher, sectionId };
}

test("a class teacher marks Class 5 A with one absence and the admin sees the simulated alert", async ({
  page,
  request,
}) => {
  test.setTimeout(120_000);
  const school = newSchool("att");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin = { headers: { Authorization: `Bearer ${adminToken}` } };
  const { teacher, sectionId } = await setUpSchool(request, school.schoolCode, asAdmin);

  // The teacher marks the register on a phone-sized screen.
  await page.setViewportSize({ width: 390, height: 844 });
  await loginViaUi(page, { schoolCode: school.schoolCode, email: teacher.email, password: teacher.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page
    .getByTestId("mark-attendance-cards")
    .getByRole("link", { name: "Mark attendance for Class 5 A" })
    .click();
  await expect(page).toHaveURL(new RegExp(`/app/attendance\\?section=${sectionId}$`));
  await expect(page.getByRole("heading", { name: "Class 5 A" })).toBeVisible();
  await expectNoHorizontalScroll(page);

  await page.getByRole("button", { name: "Mark all present" }).click();
  const bala = page.getByRole("group", { name: "Bala Iyer" });
  await bala.getByRole("button", { name: "Absent" }).click();
  await expect(bala.getByRole("button", { name: "Absent" })).toHaveAttribute("aria-pressed", "true");
  await page.getByTestId("attendance-save").click();
  const confirm = page.getByRole("dialog", { name: "Save attendance for Class 5 A?" });
  await expect(confirm).toContainText("2 present, 1 absent");
  await confirm.getByRole("button", { name: "Save attendance" }).click();
  await expect(page.getByText("Attendance saved for Class 5 A. 1 absence alert queued.")).toBeVisible();
  await expect(page.getByTestId("register-meta")).toContainText("Marked by Ravi Kumar");
  await expectNoHorizontalScroll(page);

  // The teacher has no message log.
  const teacherToken = await loginViaApi(request, {
    schoolCode: school.schoolCode,
    email: teacher.email,
    password: teacher.password,
  });
  expect((await request.get("/api/messages", { headers: { Authorization: `Bearer ${teacherToken}` } })).status()).toBe(
    403,
  );

  // The admin finds the alert in the message log once the dispatcher has run (every 15 seconds).
  await signOut(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Messages" }).click();
  await expect(page).toHaveURL(/\/app\/messages$/);

  const row = page.getByTestId("messages-table").getByRole("row").filter({ hasText: "Bala Iyer (Class 5 A)" });
  await expect(async () => {
    await page.reload();
    await expect(row).toContainText("Simulated", { timeout: 3_000 });
  }).toPass({ timeout: 60_000, intervals: [2_000] });
  await expect(row).toContainText("Uma Iyer");
  await expect(row).toContainText("98765•••02");
  await expect(row).toContainText("WhatsApp");
  await expect(page.getByTestId("messages-table").getByRole("row")).toHaveCount(2);
  await expect(page.locator("body")).not.toContainText("9876500002");

  await row.getByRole("button", { name: "View Bala Iyer (Class 5 A)" }).click();
  const detail = page.getByRole("dialog", { name: "Message" });
  await expect(detail.getByTestId("message-detail")).toContainText(
    `Dear Uma Iyer, Bala Iyer (Class 5 A) was marked absent at ${school.schoolName} on`,
  );
});
