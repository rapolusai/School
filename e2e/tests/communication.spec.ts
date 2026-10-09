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
  type Credentials,
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

/** Today's date in India, "YYYY-MM-DD". */
function todayInIndia(now: Date): string {
  return new Date(now.getTime() + 330 * 60_000).toISOString().slice(0, 10);
}

type Auth = { headers: { Authorization: string } };

async function send<T>(request: APIRequestContext, method: "post" | "put", path: string, auth: Auth, data: unknown) {
  const res = await request[method](path, { ...auth, data });
  expect(res.ok(), `${path}: ${await res.text()}`).toBe(true);
  return (await res.json()) as T;
}

type StudentDetail = { id: string; guardians: { id: string; name: string }[] };

/**
 * Through the API: the current year, Class 5 with sections A (Ravi Kumar is class teacher) and B, one student in
 * each with a parent who has a sign-in, and a principal.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const at = (name: string) => `${name}@${schoolCode}.example.com`;
  const teacher: Credentials = { schoolCode, email: at("ravi"), password: PASSWORD };
  const principal: Credentials = { schoolCode, email: at("principal"), password: PASSWORD };
  const parentA: Credentials = { schoolCode, email: at("meena"), password: PASSWORD };
  const parentB: Credentials = { schoolCode, email: at("suresh"), password: PASSWORD };

  const { id: teacherId } = await send<{ id: string }>(request, "post", "/api/users", auth, {
    name: "Ravi Kumar",
    email: teacher.email,
    password: teacher.password,
    roles: ["TEACHER"],
  });
  await send(request, "post", "/api/users", auth, {
    name: "Lakshmi Menon",
    email: principal.email,
    password: principal.password,
    roles: ["PRINCIPAL"],
  });

  const year = academicYearAround(new Date());
  await send(request, "post", "/api/academics/years", auth, { ...year, current: true });
  const { id: classId } = await send<{ id: string }>(request, "post", "/api/academics/classes", auth, {
    name: "Class 5",
  });
  const sections: Record<"A" | "B", string> = { A: "", B: "" };
  for (const name of ["A", "B"] as const) {
    const section = await send<{ id: string }>(request, "post", `/api/academics/classes/${classId}/sections`, auth, {
      name,
      capacity: 40,
      ...(name === "A" ? { classTeacherId: teacherId } : {}),
    });
    sections[name] = section.id;
  }

  const families = [
    { section: "A", first: "Kabir", last: "Mehta", guardian: "Meena Mehta", relation: "MOTHER", login: parentA },
    { section: "B", first: "Chitra", last: "Das", guardian: "Suresh Das", relation: "FATHER", login: parentB },
  ] as const;
  const students: Record<"A" | "B", string> = { A: "", B: "" };
  for (const [i, f] of families.entries()) {
    const student = await send<StudentDetail>(request, "post", "/api/students", auth, {
      admissionNo: `E2E/COM/${i + 1}`,
      firstName: f.first,
      lastName: f.last,
      dateOfBirth: "2016-04-12",
      gender: f.first === "Kabir" ? "MALE" : "FEMALE",
      admissionDate: year.startsOn,
      sectionId: sections[f.section],
      rollNo: 1,
      guardians: [{ name: f.guardian, relation: f.relation, phone: `98765 1000${i + 1}`, primary: true }],
    });
    students[f.section] = student.id;
    await send(request, "post", `/api/students/${student.id}/guardians/${student.guardians[0].id}/sign-in`, auth, {
      mode: "CREATE",
      email: f.login.email,
      password: f.login.password,
    });
  }
  return { teacher, principal, parentA, parentB, sections, students };
}

test("a teacher's circular for Class 5 A is approved by the principal and reaches the parent's board", async ({
  page,
  request,
}) => {
  test.setTimeout(120_000);
  const school = newSchool("com");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const { teacher, principal, parentA, parentB } = await setUpSchool(request, school.schoolCode, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  const title = "Class 5 A science project day";
  const body = "Dear parents,\nThe science project is due on Friday. Please send the model to school by 9 am.";

  // 1. The class teacher drafts a circular; it is addressed to the parents of Class 5 A from the start.
  await loginViaUi(page, teacher);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Circulars" }).click();
  await expect(page).toHaveURL(/\/app\/notices$/);
  await expect(page.getByTestId("notices-empty")).toBeVisible();
  await page.getByRole("link", { name: "New circular" }).first().click();
  await expect(page).toHaveURL(/\/app\/notices\/new$/);

  const picker = page.getByTestId("audience-picker");
  await expect(picker.getByRole("checkbox", { name: "Class 5 A" })).toBeChecked();
  await expect(picker.getByRole("checkbox", { name: "Parents" })).toBeChecked();
  await expect(picker.getByRole("checkbox", { name: /Whole school/ })).toHaveCount(0);
  await page.getByLabel("Title").fill(title);
  await page.getByLabel("Category").selectOption("ACADEMIC");
  await page.getByLabel("Message").fill(body);
  await expect(page.getByTestId("estimate-people")).toHaveText("1 parents, 0 students and 0 staff");
  await page.getByRole("button", { name: "Save draft" }).click();

  await expect(page).toHaveURL(/\/app\/notices\/[0-9a-f-]{36}$/);
  const circularId = page.url().split("/").pop()!;
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(title);
  await expect(page.getByTestId("notice-status")).toContainText("Draft");
  await expect(page.getByTestId("notice-audience")).toContainText("Class 5 A");

  // 2. ... and submits it: teachers' circulars wait for the principal.
  await page.getByTestId("notice-actions").getByRole("button", { name: "Submit for approval" }).click();
  const submit = page.getByRole("dialog", { name: "Submit for approval?" });
  await submit.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByText("Sent for approval.")).toBeVisible();
  await expect(page.getByTestId("notice-status")).toContainText("Waiting for approval");
  await expect(page.getByTestId("notice-actions").getByRole("button", { name: "Approve and send" })).toHaveCount(0);

  // A teacher cannot approve, and a parent cannot write circulars.
  const teacherToken = await loginViaApi(request, teacher);
  const asTeacher = { headers: { Authorization: `Bearer ${teacherToken}` } };
  expect((await request.post(`/api/notices/${circularId}/approve`, { ...asTeacher, data: {} })).status()).toBe(403);
  const parentBToken = await loginViaApi(request, parentB);
  const asParentB = { headers: { Authorization: `Bearer ${parentBToken}` } };
  expect((await request.get("/api/notices", asParentB)).status()).toBe(403);
  await signOut(page);

  // 3. The principal approves it, which sends it.
  await loginViaUi(page, principal);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Circulars" }).click();
  await expect(page.getByTestId("pending-alert")).toContainText("1 circular is waiting for your approval.");
  await page.getByTestId("notices-table").getByRole("link", { name: title }).click();
  await expect(page).toHaveURL(new RegExp(`/app/notices/${circularId}$`));
  await expect(page.getByTestId("notice-body")).toContainText("The science project is due on Friday.");
  await page.getByTestId("notice-actions").getByRole("button", { name: "Approve and send" }).click();
  const approve = page.getByRole("dialog", { name: "Approve this circular?" });
  await approve.getByLabel("Note for the author (optional)").fill("Looks good.");
  await approve.getByRole("button", { name: "Approve and send" }).click();
  await expect(page.getByText("Circular sent.")).toBeVisible();
  await expect(page.getByTestId("notice-status")).toContainText("Sent");
  await expect(page.getByTestId("delivery-read")).toHaveText("0 of 1 read");
  await signOut(page);

  // 4. The parent of a Class 5 A student finds it on the dashboard and on the notice board, and reads it.
  await loginViaUi(page, parentA);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("notices-card")).toContainText(title);
  await expect(page.getByTestId("upcoming-card")).toBeVisible();
  await expect(mainNav(page).getByRole("link", { name: "Circulars" })).toHaveCount(0);
  await mainNav(page).getByRole("link", { name: "Notice board" }).click();
  await expect(page).toHaveURL(/\/app\/board$/);
  const row = page.getByTestId("notice-row").filter({ hasText: title });
  await expect(row).toHaveAttribute("data-unread", "true");
  await row.getByRole("button", { name: title }).click();
  const reader = page.getByRole("dialog", { name: title });
  await expect(reader.getByTestId("notice-reader")).toContainText("Please send the model to school by 9 am.");
  await expect(reader).toContainText("Lakshmi Menon");
  await reader.getByRole("button", { name: "Close" }).last().click();
  await expect(reader).toBeHidden();
  await expect(row).not.toHaveAttribute("data-unread", "true");
  await expect(page.getByText("0 unread")).toBeVisible();

  // The principal sees the read receipt.
  const principalToken = await loginViaApi(request, principal);
  const detail = await request.get(`/api/notices/${circularId}`, {
    headers: { Authorization: `Bearer ${principalToken}` },
  });
  expect(((await detail.json()) as { delivery: { read: number } }).delivery.read).toBe(1);

  // 5. The parent of a Class 5 B student does not get it, and another school cannot see it at all.
  const boardB = (await (await request.get("/api/notices/board", asParentB)).json()) as { total: number };
  expect(boardB.total).toBe(0);
  expect((await request.get(`/api/notices/board/${circularId}`, asParentB)).status()).toBe(404);

  const other = newSchool("com-b");
  await signupViaApi(request, other);
  const otherToken = await loginViaApi(request, adminCredentials(other));
  const asOther = { headers: { Authorization: `Bearer ${otherToken}` } };
  expect((await request.get(`/api/notices/${circularId}`, asOther)).status()).toBe(404);
  expect((await request.get(`/api/notices/board/${circularId}`, asOther)).status()).toBe(404);
});

test("an admin declares a holiday and the attendance screen shows the school closed that day", async ({
  page,
  request,
}) => {
  test.setTimeout(120_000);
  const school = newSchool("hol");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin = { headers: { Authorization: `Bearer ${adminToken}` } };
  const { teacher, sections, students } = await setUpSchool(request, school.schoolCode, asAdmin);
  const today = todayInIndia(new Date());
  const holiday = "Rain holiday";

  // 1. The admin adds a whole-school holiday on today's date from the calendar.
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Calendar" }).click();
  await expect(page).toHaveURL(/\/app\/calendar$/);
  await page.getByRole("button", { name: "Add entry" }).click();
  const dialog = page.getByRole("dialog", { name: "Add a calendar entry" });
  await dialog.getByLabel("Kind").selectOption("HOLIDAY");
  await dialog.getByLabel("Title").fill(holiday);
  await dialog.getByLabel("Date", { exact: true }).fill(today);
  await expect(dialog.getByRole("radio", { name: "Whole school" })).toBeChecked();
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(`${holiday} added to the calendar.`)).toBeVisible();

  const day = page.locator(`.cal-day[data-date="${today}"]`);
  await expect(day).toHaveClass(/is-holiday/);
  await expect(day.getByRole("button", { name: holiday })).toBeVisible();

  // 2. The attendance screen shows the day as a holiday, with nothing to mark.
  await mainNav(page).getByRole("link", { name: "Attendance" }).click();
  await expect(page).toHaveURL(/\/app\/attendance/);
  await expect(page.getByTestId("attendance-holiday")).toHaveText(
    `School holiday: ${holiday}. Attendance is not marked on holidays.`,
  );
  await expectNoHorizontalScroll(page);

  // 3. The API refuses a register for that day, even from the class teacher.
  const teacherToken = await loginViaApi(request, teacher);
  const marked = await request.put(`/api/attendance/registers/${sections.A}/${today}`, {
    headers: { Authorization: `Bearer ${teacherToken}` },
    data: { entries: [{ studentId: students.A, status: "PRESENT" }] },
  });
  expect(marked.status()).toBe(409);
  const problem = (await marked.json()) as { errors: { date: string } };
  expect(problem.errors.date).toBe(`${today} is a school holiday (${holiday}). Attendance is not marked on holidays.`);

  // 4. The class teacher sees the holiday on their dashboard and in the calendar, without editing rights.
  await signOut(page);
  await loginViaUi(page, teacher);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("upcoming-card")).toContainText(holiday);
  await mainNav(page).getByRole("link", { name: "Calendar" }).click();
  await expect(page.locator(`.cal-day[data-date="${today}"]`).getByRole("button", { name: holiday })).toBeVisible();
  await expect(page.getByRole("button", { name: "Add entry" })).toHaveCount(0);
});
