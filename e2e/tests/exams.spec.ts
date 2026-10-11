import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
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

/** JSON calls with a token, expecting `status` (201 for POST, 200 for PUT unless given). */
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

const STUDENTS = [
  { first: "Kabir", last: "Mehta", guardian: "Meena Mehta", relation: "MOTHER", phone: "98765 00001" },
  { first: "Bala", last: "Iyer", guardian: "Uma Iyer", relation: "MOTHER", phone: "98765 00002" },
  { first: "Chitra", last: "Das", guardian: "Suresh Das", relation: "FATHER", phone: "98765 00003" },
] as const;

/**
 * Through the API: the current year, a class teacher and a principal, Class 5 A with English and Mathematics and
 * three students (Kabir's mother has a sign-in), and the Half-yearly exam of Term 1 with both papers written in the
 * last few days (Class 5 gets the board's CBSE 1–8 grading by default).
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const { post, put } = client(request, auth);
  const ravi = { name: "Ravi Kumar", email: `ravi@${schoolCode}.example.com`, password: PASSWORD };
  const lakshmi = { name: "Lakshmi Iyer", email: `lakshmi@${schoolCode}.example.com`, password: PASSWORD };
  const parent = { email: `meena@${schoolCode}.example.com`, password: PASSWORD };
  const { id: raviId } = await post("/api/users", { ...ravi, roles: ["TEACHER"] });
  await post("/api/users", { ...lakshmi, roles: ["PRINCIPAL"] });

  const year = academicYearAround(indiaDate());
  const { id: yearId } = await post("/api/academics/years", { ...year, current: true });
  const { id: classId } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: sectionId } = await post(`/api/academics/classes/${classId}/sections`, {
    name: "A",
    capacity: 40,
    classTeacherId: raviId,
  });
  const { id: english } = await post("/api/academics/subjects", { name: "English" });
  const { id: maths } = await post("/api/academics/subjects", { name: "Mathematics" });
  await put(`/api/academics/classes/${classId}/subjects`, { subjectIds: [english, maths] });

  const students: { id: string; guardians: { id: string }[] }[] = [];
  for (const [i, s] of STUDENTS.entries()) {
    students.push(
      await post("/api/students", {
        admissionNo: `E2E/EX/${i + 1}`,
        firstName: s.first,
        lastName: s.last,
        dateOfBirth: "2016-04-12",
        gender: s.first === "Kabir" ? "MALE" : "FEMALE",
        admissionDate: year.startsOn,
        sectionId,
        rollNo: i + 1,
        guardians: [{ name: s.guardian, relation: s.relation, phone: s.phone, primary: true }],
      }),
    );
  }
  const [kabir, bala] = students;
  await post(`/api/students/${kabir.id}/guardians/${kabir.guardians[0].id}/sign-in`, { mode: "CREATE", ...parent }, 200);

  // Both answer with the year's terms and exams.
  type Structure = { terms: { id: string; name: string; exams: { id: string; name: string }[] }[] };
  const withTerm = await post<Structure>("/api/exams/terms", {
    academicYearId: yearId,
    name: "Term 1",
    startsOn: year.startsOn,
    endsOn: year.endsOn,
  });
  const termId = withTerm.terms[0].id;
  const withExam = await post<Structure>("/api/exams", {
    termId,
    name: "Half-yearly",
    instructions: "Bring your hall ticket.",
    classIds: [classId],
  });
  const examId = withExam.terms[0].exams[0].id;
  // Written in the last few days (never before the year starts), so the marks are due.
  const written = (days: number) => (indiaDate(-days) < year.startsOn ? year.startsOn : indiaDate(-days));
  const paper = (subjectId: string, date: string, startTime: string, maxTheory: number, maxInternal: number | null) => ({
    subjectId,
    maxTheory,
    maxPractical: null,
    maxInternal,
    date,
    startTime,
    durationMinutes: 120,
    room: "Room 12",
  });
  await put(`/api/exams/${examId}/classes/${classId}/papers`, {
    papers: [paper(english, written(2), "09:30", 80, 20), paper(maths, written(1), "09:30", 100, null)],
  });

  return { ravi, lakshmi, parent, examId, classId, kabirId: kabir.id, balaId: bala.id };
}

/** Opens a mark sheet from the marks list (cards on a phone), fills it and submits it. */
async function enterAndLock(page: Page, subject: string, fill: (page: Page) => Promise<void>) {
  await page.getByTestId("marks-sheet-link").filter({ hasText: `Class 5 A · ${subject}` }).click();
  await expect(page.getByRole("heading", { name: `Class 5 A · ${subject}` })).toBeVisible();
  await expect(page.getByTestId("marks-entered")).toHaveText("0 of 3 entered");
  await fill(page);
  await expect(page.getByTestId("marks-entered")).toHaveText("3 of 3 entered");
  await expectNoHorizontalScroll(page);
  await page.getByRole("button", { name: "Submit and lock" }).click();
  const confirm = page.getByRole("dialog", { name: "Submit and lock these marks?" });
  await confirm.getByRole("button", { name: "Submit and lock" }).click();
  await expect(page.getByTestId("marks-locked")).toContainText("Submitted by Ravi Kumar");
  await expect(page.getByRole("textbox", { name: /Theory marks for Kabir Mehta/ })).toBeDisabled();
  await page.getByRole("link", { name: "Marks entry" }).click();
  await expect(page).toHaveURL(/\/app\/exams\/marks$/);
}

test("a teacher locks Class 5 A's marks on a phone, the principal publishes and a parent sees the report card", async ({
  page,
  request,
}) => {
  test.setTimeout(150_000);
  const school = newSchool("exam");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin = { headers: { Authorization: `Bearer ${adminToken}` } };
  const { ravi, lakshmi, parent, examId, classId, kabirId, balaId } = await setUpSchool(
    request,
    school.schoolCode,
    asAdmin,
  );
  const parentToken = await loginViaApi(request, { schoolCode: school.schoolCode, ...parent });
  const asParent = { headers: { Authorization: `Bearer ${parentToken}` } };

  // 1. The class teacher enters both papers' marks on a phone and locks them.
  await page.setViewportSize({ width: 390, height: 844 });
  await loginViaUi(page, { schoolCode: school.schoolCode, email: ravi.email, password: ravi.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const due = page.getByTestId("tile-marksToEnter");
  await expect(due).toContainText("2");
  await due.getByRole("link", { name: "Marks to enter" }).click();
  await expect(page).toHaveURL(/\/app\/exams\/marks$/);
  await expect(page.getByTestId("marks-progress")).toHaveText("0 of 2 locked");
  await expectNoHorizontalScroll(page);

  await enterAndLock(page, "English", async (p) => {
    const marks = { "Kabir Mehta": ["68", "18"], "Bala Iyer": ["55", "15"], "Chitra Das": ["20", "6"] };
    for (const [name, [theory, internal]] of Object.entries(marks)) {
      await p.getByRole("textbox", { name: `Theory marks for ${name}, out of 80` }).fill(theory);
      await p.getByRole("textbox", { name: `Internal marks for ${name}, out of 20` }).fill(internal);
    }
    // A mark above the maximum is caught before saving.
    const kabir = p.getByRole("textbox", { name: "Theory marks for Kabir Mehta, out of 80" });
    await kabir.fill("86");
    await expect(kabir).toHaveAttribute("aria-invalid", "true");
    await kabir.fill("68");
    await p.getByRole("button", { name: "Save draft" }).click();
    await expect(p.getByText("Marks saved: 3 of 3 entered")).toBeVisible();
  });
  await enterAndLock(page, "Mathematics", async (p) => {
    await p.getByRole("textbox", { name: "Theory marks for Kabir Mehta, out of 100" }).fill("92");
    await p.getByRole("textbox", { name: "Theory marks for Bala Iyer, out of 100" }).fill("47.5");
    const chitra = p.getByRole("group", { name: "Absent, medical or exempt for Chitra Das" });
    await chitra.getByRole("button", { name: "AB" }).click();
    await expect(chitra.getByRole("button", { name: "AB" })).toHaveAttribute("aria-pressed", "true");
  });
  await expect(page.getByTestId("marks-progress")).toHaveText("2 of 2 locked");
  await expect(page.getByText("Every sheet here is submitted and locked.")).toBeVisible();

  // Nothing is published yet: the family sees no results, and the teacher cannot publish or unlock.
  const before = await request.get(`/api/me/children/${kabirId}/results`, asParent);
  expect(before.status()).toBe(200);
  expect(((await before.json()) as { results: unknown[] }).results).toHaveLength(0);
  const teacherToken = await loginViaApi(request, { schoolCode: school.schoolCode, email: ravi.email, password: ravi.password });
  const publishPath = `/api/exams/${examId}/classes/${classId}/results/publish`;
  expect((await request.post(publishPath, { headers: { Authorization: `Bearer ${teacherToken}` } })).status()).toBe(403);
  await signOut(page);

  // 2. The principal publishes the results.
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginViaUi(page, { schoolCode: school.schoolCode, email: lakshmi.email, password: lakshmi.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("tile-resultsToPublish")).toContainText("1");
  await mainNav(page).getByRole("link", { name: "Exams" }).click();
  await expect(page).toHaveURL(/\/app\/exams$/);
  await page.getByTestId("exam-class-link").filter({ hasText: "Class 5" }).click();
  await expect(page.getByRole("heading", { name: "Class 5", exact: true })).toBeVisible();
  await expect(page.getByTestId("results-pending")).toHaveText("Every mark sheet is locked. The results are ready to publish.");
  await page.getByRole("button", { name: "Publish results" }).click();
  const publish = page.getByRole("dialog", { name: "Publish the results of Half-yearly for Class 5?" });
  await publish.getByRole("button", { name: "Publish results" }).click();
  await expect(page.getByText("Results published. Parents will get a message.")).toBeVisible();
  await expect(page.getByTestId("results-status")).toContainText("by Lakshmi Iyer on");

  // Each family gets a (simulated) message through the outbox; numbers stay masked.
  const detail = (await (
    await request.get(`/api/exams/${examId}/classes/${classId}`, asAdmin)
  ).json()) as { examClassId: string };
  const messages = await request.get(`/api/messages?relatedId=${detail.examClassId}`, asAdmin);
  expect(messages.status()).toBe(200);
  const log = (await messages.json()) as { items: { templateKey: string; recipient: string }[] };
  expect(log.items).toHaveLength(3);
  expect(log.items.every((m) => m.templateKey === "exams.results_published")).toBe(true);
  expect(log.items.map((m) => m.recipient)).toContain("98765•••01");
  await signOut(page);

  // 3. Kabir's mother sees the result and the report card on her phone.
  await page.setViewportSize({ width: 390, height: 844 });
  await loginViaUi(page, { schoolCode: school.schoolCode, ...parent });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const card = page.getByTestId("family-results-card");
  await expect(card).toContainText("Half-yearly");
  await expect(card).toContainText("Pass");
  await card.getByRole("link", { name: "Results and report card" }).click();
  await expect(page).toHaveURL(/\/app\/family\/results/);
  await expect(page.getByTestId("family-results")).toContainText("Half-yearly");
  const report = page.getByRole("article", { name: "Report card of Kabir Mehta" });
  await expect(report).toContainText("Report card: Term 1");
  const subjects = report.getByTestId("card-subjects");
  await expect(subjects.getByRole("row", { name: /English/ })).toContainText("86/100");
  await expect(subjects.getByRole("row", { name: /Mathematics/ })).toContainText("92/100");
  await expect(report.getByTestId("card-percent")).toHaveText("89%");
  await expectNoHorizontalScroll(page);

  // Another family's child is not hers, and staff mark sheets are closed to her.
  expect((await request.get(`/api/me/children/${balaId}/results`, asParent)).status()).toBe(404);
  expect((await request.get("/api/exams/marks", asParent)).status()).toBe(403);
});
