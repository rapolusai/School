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

const DAY_MS = 86_400_000;

/** Today in India as "YYYY-MM-DD", moved by `days`. */
function indiaDate(days = 0): string {
  return new Date(Date.now() + 330 * 60_000 + days * DAY_MS).toISOString().slice(0, 10);
}

/** The Indian academic year (April to March) that contains today. */
function academicYear() {
  const today = indiaDate();
  const year = Number(today.slice(5, 7)) >= 4 ? Number(today.slice(0, 4)) : Number(today.slice(0, 4)) - 1;
  return { startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31`, name: `${year}-${String((year + 1) % 100).padStart(2, "0")}` };
}

/** The day the leave is for: today, or yesterday (a Saturday) when today is a Sunday and needs no leave. */
function leaveDay(): string {
  const today = indiaDate();
  return new Date(`${today}T00:00:00Z`).getUTCDay() === 0 ? indiaDate(-1) : today;
}

type Auth = { headers: { Authorization: string } };

/** JSON calls through the frontend's /api proxy, expecting `status`. */
function client(request: APIRequestContext, auth: Auth) {
  const call = async <T>(method: "post" | "put", path: string, data: unknown, status: number): Promise<T> => {
    const res = await request[method](path, { ...auth, data });
    expect(res.status(), `${method.toUpperCase()} ${path}: ${await res.text()}`).toBe(status);
    return (await res.json()) as T;
  };
  return {
    post: <T = { id: string }>(path: string, data?: unknown, status = 201) => call<T>("post", path, data, status),
    put: <T = unknown>(path: string, data: unknown, status = 200) => call<T>("put", path, data, status),
  };
}

type StudentDetail = { id: string; guardians: { id: string }[] };

/**
 * Through the API, a school like the demo one: Ravi Kumar is class teacher of Class 5 A, where Diya Mehta and
 * Bala Iyer study; Diya's elder brother Kabir is in Class 8 A. Their mother Meena Mehta has one sign-in for both
 * children. Class 5 A has maths homework due soon, and Class 5 fees whose first term is overdue.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const { post, put } = client(request, auth);
  const teacher: Credentials = { schoolCode, email: `ravi@${schoolCode}.example.com`, password: PASSWORD };
  const { id: raviId } = await post("/api/users", { name: "Ravi Kumar", email: teacher.email, password: PASSWORD, roles: ["TEACHER"] });

  const year = academicYear();
  const { id: yearId } = await post("/api/academics/years", { ...year, current: true });
  const { id: class5 } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: class8 } = await post("/api/academics/classes", { name: "Class 8" });
  const { id: section5A } = await post(`/api/academics/classes/${class5}/sections`, { name: "A", capacity: 40, classTeacherId: raviId });
  const { id: section8A } = await post(`/api/academics/classes/${class8}/sections`, { name: "A", capacity: 40 });

  const admit = (admissionNo: string, firstName: string, lastName: string, dateOfBirth: string, sectionId: string, rollNo: number, guardian: string, phone: string) =>
    post<StudentDetail>("/api/students", {
      admissionNo,
      firstName,
      lastName,
      dateOfBirth,
      gender: firstName === "Kabir" ? "MALE" : "FEMALE",
      admissionDate: year.startsOn,
      sectionId,
      rollNo,
      guardians: [{ name: guardian, relation: "MOTHER", phone, primary: true }],
    });
  const kabir = await admit("E2E/PA/1", "Kabir", "Mehta", "2013-02-08", section8A, 1, "Meena Mehta", "98765 20001");
  const diya = await admit("E2E/PA/2", "Diya", "Mehta", "2016-09-21", section5A, 1, "Meena Mehta", "98765 20001");
  const bala = await admit("E2E/PA/3", "Bala", "Iyer", "2016-05-14", section5A, 2, "Uma Iyer", "98765 20002");

  const parent: Credentials = { schoolCode, email: `meena@${schoolCode}.example.com`, password: "Parent-temp-2026" };
  // The same mother (same phone) is one guardian of both children, so one sign-in reaches both.
  expect(diya.guardians[0].id).toBe(kabir.guardians[0].id);
  await post(`/api/students/${kabir.id}/guardians/${kabir.guardians[0].id}/sign-in`, { mode: "CREATE", email: parent.email, password: parent.password }, 200);

  // Maths homework for Class 5 A, due in two days.
  const { id: maths } = await post("/api/academics/subjects", { name: "Mathematics" });
  await put(`/api/academics/classes/${class5}/subjects`, { subjectIds: [maths] });
  const dueOn = indiaDate(2) <= year.endsOn ? indiaDate(2) : year.endsOn;
  await post("/api/homework", {
    sectionIds: [section5A],
    subjectId: maths,
    title: "Fractions worksheet",
    instructions: "Do questions 1 to 10 on page 42.",
    dueOn,
    onlineSubmission: true,
  });

  // Class 5 fees: ₹20,000 tuition in two terms, the first already overdue.
  const heads = await post<{ id: string; kind: string }[]>("/api/fees/heads/defaults", undefined, 200);
  const tuition = heads.find((h) => h.kind === "TUITION")!;
  const saved = await post<{ structure: { id: string } }>("/api/fees/structures", {
    academicYearId: yearId,
    classId: class5,
    heads: [{ headId: tuition.id, amountPaise: 20_000_00 }],
    instalments: [
      { label: "Term 1", dueDate: indiaDate(-10) },
      { label: "Term 2", dueDate: indiaDate(90) },
    ],
  });
  await post(`/api/fees/structures/${saved.structure.id}/publish`, undefined, 200);

  return { teacher, parent, diyaId: diya.id, balaId: bala.id, section5A };
}

/** Opens a menu item from the phone's bottom bar, through "More" when it is not one of the first four. */
async function openFromMenu(page: Page, label: string) {
  const bar = page.getByTestId("bottom-bar");
  await expect(bar).toBeVisible();
  const direct = bar.getByRole("link", { name: label, exact: true });
  if ((await direct.count()) > 0) {
    await direct.click();
    return;
  }
  await bar.getByRole("button", { name: "More" }).click();
  await page.getByRole("dialog", { name: "All sections" }).getByRole("link", { name: label, exact: true }).click();
}

test.use({ viewport: { width: 390, height: 844 } });

test("a parent switches to their daughter, sees homework and fees and applies for leave; the class teacher approves and the register starts with leave", async ({
  page,
  request,
}) => {
  test.setTimeout(150_000);
  const school = newSchool("parent");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const { teacher, parent, diyaId, balaId, section5A } = await setUpSchool(request, school.schoolCode, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  const day = leaveDay();

  // 1. The parent's home on a phone: the family menu, the install tip and the elder child first.
  await loginViaUi(page, parent);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByRole("heading", { name: "My children" })).toBeVisible();
  const bar = page.getByTestId("bottom-bar");
  await expect(bar.getByRole("link")).toHaveCount(4);
  await expect(bar.getByRole("link", { name: "Fees" })).toBeVisible();
  await expect(bar.getByRole("link", { name: "Students" })).toHaveCount(0);
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute("href", /\/manifest\.webmanifest/);
  const hint = page.getByTestId("install-hint");
  await expect(hint).toContainText("Add Akshara to your home screen");
  await hint.getByRole("button", { name: "Hide this tip" }).click();
  await expect(hint).toBeHidden();

  const switcher = page.getByRole("radiogroup", { name: "Choose a child" });
  await expect(switcher.getByRole("radio", { name: "Kabir Mehta" })).toBeChecked();
  const card = page.getByTestId("child-card");
  await expect(card).toHaveCount(1);
  await expect(card).toContainText("Class 8 A");
  await expect(card.getByTestId("child-fees")).not.toContainText("overdue");
  await expect(page.getByTestId("child-homework-due")).toContainText("Nothing due");
  await expectNoHorizontalScroll(page);

  // 2. She switches to Diya: Class 5 A's homework, the overdue term with a Pay button, and leave.
  await switcher.getByRole("radio", { name: "Diya Mehta" }).check();
  await expect(card).toContainText("Diya Mehta");
  await expect(card).toContainText("Class 5 A");
  await expect(card).toContainText("Ravi Kumar");
  const fees = card.getByTestId("child-fees");
  await expect(fees).toContainText("₹10,000 overdue");
  await expect(fees.getByRole("link", { name: "Pay fees" })).toHaveAttribute("href", `/app/children/${diyaId}/fees`);
  const homework = page.getByTestId("child-homework-due");
  await expect(homework).toContainText("1 to hand in");
  await expect(homework.getByRole("link", { name: /Fractions worksheet/ })).toBeVisible();
  await expect(page.getByTestId("family-leave-card")).toContainText("No leave requested yet");
  await expectNoHorizontalScroll(page);

  // The app remembers the child she chose.
  await page.reload();
  await expect(page.getByRole("radiogroup", { name: "Choose a child" }).getByRole("radio", { name: "Diya Mehta" })).toBeChecked();
  await expect(page.getByTestId("child-card")).toContainText("Class 5 A");

  // 3. She applies for a day's leave from the menu.
  await openFromMenu(page, "Leave");
  await expect(page).toHaveURL(/\/app\/family\/leave$/);
  await expect(page.getByText("Your child's class teacher, Ravi Kumar, sees your request and approves it.")).toBeVisible();
  await page.getByRole("button", { name: "Apply for leave" }).click();
  const form = page.getByRole("dialog", { name: "Leave for Diya Mehta" });
  await form.getByLabel("From", { exact: true }).fill(day);
  await form.getByLabel("To", { exact: true }).fill(day);
  await form.getByLabel("Reason", { exact: true }).fill("Fever since last night; the doctor advised rest.");
  await form.getByRole("button", { name: "Send request" }).click();
  await expect(page.getByText("Leave request sent to the class teacher.")).toBeVisible();
  const row = page.getByTestId("family-leave-row");
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("Waiting");
  await expect(row).toContainText("1 school day");
  await expectNoHorizontalScroll(page);

  // The parent cannot reach the teacher's inbox, nor leave for a child who is not hers.
  const parentAuth = { headers: { Authorization: `Bearer ${await loginViaApi(request, parent)}` } };
  expect((await request.get("/api/attendance/leave-requests", parentAuth)).status()).toBe(403);
  expect((await request.get(`/api/me/children/${balaId}/leave-requests`, parentAuth)).status()).toBe(404);
  const notHers = await request.post(`/api/me/children/${balaId}/leave-requests`, {
    ...parentAuth,
    data: { fromDate: day, toDate: day, halfDay: false, reason: "Not my child" },
  });
  expect(notHers.status()).toBe(404);
  await signOut(page);

  // 4. Ravi Kumar approves it from the register's "Leave requests" link.
  await loginViaUi(page, teacher);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const register = `/app/attendance?section=${section5A}&date=${day}`;
  await page.goto(register);
  await expect(page.getByRole("heading", { name: "Class 5 A" })).toBeVisible();
  const link = page.getByTestId("leave-requests-link");
  await expect(link).toContainText("1");
  await link.click();
  await expect(page).toHaveURL(/\/app\/attendance\/leave-requests$/);
  const request1 = page.getByTestId("inbox-pending").getByRole("listitem", { name: "Diya Mehta" });
  await expect(request1).toContainText("Fever since last night");
  await expect(request1).toContainText("Asked by Meena Mehta");
  await expectNoHorizontalScroll(page);
  await request1.getByRole("button", { name: "Approve" }).click();
  const decision = page.getByRole("dialog", { name: "Approve this leave?" });
  await expect(decision).toContainText("no absence alert goes to the parent");
  await decision.getByLabel("Comment (optional)").fill("Get well soon.");
  await decision.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByText("Leave approved for Diya Mehta.")).toBeVisible();
  await expect(page.getByTestId("inbox-empty")).toBeVisible();
  await expect(page.getByTestId("inbox-recent")).toContainText("Approved by Ravi Kumar");

  // 5. The register for that day starts Diya on leave; "Mark all present" keeps it, and no alert is queued.
  await page.goto(register);
  const diya = page.getByRole("group", { name: "Diya Mehta" });
  await expect(diya.getByRole("button", { name: "Leave (excused)" })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("Roll 1 · On approved leave")).toBeVisible();
  await page.getByRole("button", { name: "Mark all present" }).click();
  await expect(diya.getByRole("button", { name: "Leave (excused)" })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("group", { name: "Bala Iyer" }).getByRole("button", { name: "Present" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await page.getByTestId("attendance-save").click();
  const confirm = page.getByRole("dialog", { name: "Save attendance for Class 5 A?" });
  await expect(confirm).toContainText("1 present, 0 absent, 0 late, 0 half day, 1 on leave");
  await confirm.getByRole("button", { name: "Save attendance" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Attendance saved" })).toHaveText("Attendance saved for Class 5 A.");
  await expectNoHorizontalScroll(page);
  await signOut(page);

  // 6. The parent sees the decision.
  await loginViaUi(page, parent);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await openFromMenu(page, "Leave");
  const decided = page.getByTestId("family-leave-row");
  await expect(decided).toContainText("Approved");
  await expect(decided).toContainText("Approved by Ravi Kumar: “Get well soon.”");
  const leave = (await (await request.get(`/api/me/children/${diyaId}/leave-requests`, parentAuth)).json()) as {
    requests: { status: string; fromDate: string }[];
  };
  expect(leave.requests).toMatchObject([{ status: "APPROVED", fromDate: day }]);

  // The installable app: manifest, worker (never cached by the browser) and offline page.
  const manifest = await request.get("/manifest.webmanifest");
  expect(manifest.status()).toBe(200);
  expect(await manifest.json()).toMatchObject({ name: "Akshara School Cloud", display: "standalone", start_url: "/app/dashboard" });
  const worker = await request.get("/sw.js");
  expect(worker.status()).toBe(200);
  expect(worker.headers()["cache-control"]).toContain("no-store");
  expect((await request.get("/offline.html")).status()).toBe(200);
});
