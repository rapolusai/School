import { expect, test, type APIRequestContext } from "@playwright/test";
import { adminCredentials, loginViaApi, loginViaUi, mainNav, newSchool, PASSWORD, signOut, signupViaApi } from "./support/helpers";

/** Today in India as "YYYY-MM-DD", moved by `days`. */
function indiaDate(days = 0): string {
  return new Date(Date.now() + 330 * 60_000 + days * 86_400_000).toISOString().slice(0, 10);
}

/** A Monday-to-Saturday day `days` before today (India). */
function schoolDayBefore(days: number): string {
  const date = indiaDate(-days);
  return new Date(`${date}T00:00:00Z`).getUTCDay() === 0 ? indiaDate(-days - 1) : date;
}

/** The Indian academic year (April to March) that contains today. */
function academicYearAround(today: string) {
  const [y, m] = today.split("-").map(Number);
  const year = m >= 4 ? y : y - 1;
  return { startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31`, name: `${year}-${String((year + 1) % 100).padStart(2, "0")}` };
}

type Auth = { headers: { Authorization: string } };

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
  { first: "Kabir", last: "Mehta", phone: "98765 00001", maths: 36, english: 34 },
  { first: "Bala", last: "Iyer", phone: "98765 00002", maths: 9, english: 11 },
  { first: "Chitra", last: "Das", phone: "98765 00003", maths: 30, english: 28 },
] as const;

/**
 * Through the API: assistance switched on, a class teacher and a principal, Class 5 A with Mathematics and English,
 * three students with Unit Test 1 marks (Bala does poorly and missed the last fortnight), and a small Mathematics
 * question bank.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const { post, put } = client(request, auth);
  const ravi = { name: "Ravi Kumar", email: `ravi@${schoolCode}.example.com`, password: PASSWORD };
  const lakshmi = { name: "Lakshmi Iyer", email: `lakshmi@${schoolCode}.example.com`, password: PASSWORD };
  const { id: raviId } = await post("/api/users", { ...ravi, roles: ["TEACHER"] });
  await post("/api/users", { ...lakshmi, roles: ["PRINCIPAL"] });
  await put("/api/assist/settings", {
    enabled: true,
    remarksEnabled: true,
    earlyWarningEnabled: true,
    papersEnabled: true,
    writingEnabled: true,
    monthlyLimit: 500,
    language: "en",
    weights: { attendance30: 25, attendance90: 15, marksTrend: 25, feeDues: 10, homework: 25, threshold: 30 },
  });

  const year = academicYearAround(indiaDate());
  const { id: yearId } = await post("/api/academics/years", { ...year, current: true });
  const { id: classId } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: sectionId } = await post(`/api/academics/classes/${classId}/sections`, { name: "A", capacity: 40, classTeacherId: raviId });
  const { id: maths } = await post("/api/academics/subjects", { name: "Mathematics" });
  const { id: english } = await post("/api/academics/subjects", { name: "English" });
  await put(`/api/academics/classes/${classId}/subjects`, { subjectIds: [maths, english] });

  const ids: string[] = [];
  for (const [i, s] of STUDENTS.entries()) {
    const { id } = await post("/api/students", {
      admissionNo: `E2E/AI/${i + 1}`,
      firstName: s.first,
      lastName: s.last,
      dateOfBirth: "2016-04-12",
      gender: "FEMALE",
      admissionDate: year.startsOn,
      sectionId,
      rollNo: i + 1,
      guardians: [{ name: `Parent of ${s.first}`, relation: "MOTHER", phone: s.phone, primary: true }],
    });
    ids.push(id);
  }

  type Structure = { terms: { id: string; exams: { id: string }[] }[] };
  const withTerm = await post<Structure>("/api/exams/terms", { academicYearId: yearId, name: "Term 1", startsOn: year.startsOn, endsOn: year.endsOn });
  const termId = withTerm.terms[0].id;
  const withExam = await post<Structure>("/api/exams", { termId, name: "Unit Test 1", classIds: [classId] });
  const examId = withExam.terms[0].exams[0].id;
  const written = (days: number) => (schoolDayBefore(days) < year.startsOn ? year.startsOn : schoolDayBefore(days));
  const { papers } = await put<{ papers: { id: string; subjectId: string }[] }>(`/api/exams/${examId}/classes/${classId}/papers`, {
    papers: [
      { subjectId: maths, maxTheory: 40, date: written(7), startTime: "09:30", durationMinutes: 90 },
      { subjectId: english, maxTheory: 40, date: written(6), startTime: "09:30", durationMinutes: 90 },
    ],
  });
  for (const paper of papers) {
    const key = paper.subjectId === maths ? "maths" : "english";
    await put(`/api/exams/papers/${paper.id}/sections/${sectionId}/marks`, {
      version: null,
      entries: STUDENTS.map((s, i) => ({ studentId: ids[i], theory: s[key] })),
    });
  }
  // Bala missed the last fortnight.
  for (let d = 1; d <= 12; d++) {
    const day = indiaDate(-d);
    if (new Date(`${day}T00:00:00Z`).getUTCDay() === 0 || day < year.startsOn) continue;
    await put(`/api/attendance/registers/${sectionId}/${day}`, {
      entries: STUDENTS.map((s, i) => ({ studentId: ids[i], status: s.first === "Bala" ? "ABSENT" : "PRESENT" })),
    });
  }
  // A small question bank: eight 1-mark questions and two 3-mark ones.
  const difficulties = ["EASY", "EASY", "EASY", "MEDIUM", "MEDIUM", "MEDIUM", "HARD", "HARD"];
  for (const [i, difficulty] of difficulties.entries()) {
    await post("/api/lessons/questions", { classId, subjectId: maths, type: "SHORT_ANSWER", difficulty, text: `What is ${i + 2} × ${i + 3}?`, marks: 1, shared: true });
  }
  for (const n of [1, 2]) {
    await post("/api/lessons/questions", { classId, subjectId: maths, type: "SHORT_ANSWER", difficulty: "MEDIUM", text: `Explain fractions with example ${n}.`, marks: 3, shared: true });
  }
  return { ravi, lakshmi, termId, sectionId };
}

test("a teacher drafts and accepts remarks, the principal assigns the watch list and the teacher prints a draft paper", async ({ page, request }) => {
  test.setTimeout(180_000);
  const school = newSchool("assist");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin = { headers: { Authorization: `Bearer ${adminToken}` } };
  const { ravi, lakshmi, termId, sectionId } = await setUpSchool(request, school.schoolCode, asAdmin);

  // 1. The class teacher drafts remarks for Class 5 A, checks what was used, edits Kabir's and accepts it.
  await loginViaUi(page, { schoolCode: school.schoolCode, email: ravi.email, password: ravi.password });
  await mainNav(page).getByRole("link", { name: "Assistance" }).click();
  await expect(page.getByRole("heading", { name: "Report card remark drafts" })).toBeVisible();
  await page.getByLabel("Term").selectOption({ label: "Term 1" });
  await page.getByLabel("Class and section").selectOption({ label: "Class 5 A" });
  await expect(page.getByTestId("remark-row")).toHaveCount(3);
  await page.getByTestId("assist-draft-all").click();
  const kabir = page.getByTestId("remark-row").filter({ hasText: "Kabir Mehta" });
  const box = kabir.getByLabel("Remark for Kabir Mehta");
  await expect(box).toHaveValue(/Kabir has done very well in Class 5 A/);
  await expect(kabir.getByTestId("simulated-label")).toHaveText("Simulated draft");
  await kabir.getByText("What was used").click();
  await expect(kabir.getByTestId("used-inputs")).toContainText("Mathematics: 90%");
  await expect(kabir.getByTestId("used-inputs")).not.toContainText("Mehta");
  await box.fill("Kabir has done very well this term. Keep reading every day.");
  await kabir.getByRole("button", { name: "Accept" }).click();
  await expect(kabir.getByText("Accepted", { exact: true })).toBeVisible();
  const records = await request.get(`/api/exams/terms/${termId}/sections/${sectionId}/records`, asAdmin);
  expect(await records.text()).toContain("Keep reading every day.");
  await signOut(page);

  // 2. The principal runs the watch list, reads Bala's reasons and assigns Ravi to follow up.
  await loginViaUi(page, { schoolCode: school.schoolCode, email: lakshmi.email, password: lakshmi.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/assist/watch-list");
  await page.getByTestId("assist-run-now").click();
  const bala = page.getByTestId("watch-row").filter({ hasText: "Bala Iyer" });
  await expect(bala).toContainText("in the last 30 days");
  await expect(page.getByTestId("watch-row").filter({ hasText: "Kabir Mehta" })).toHaveCount(0);
  await bala.getByRole("button", { name: "Open Bala Iyer" }).click();
  const dialog = page.getByTestId("watch-detail");
  await dialog.getByLabel("Assign to").selectOption({ label: "Ravi Kumar" });
  await expect(dialog.getByLabel("Status")).toHaveValue("FOLLOWING");
  await dialog.getByLabel("Note").fill("Meeting the family on Monday.");
  await dialog.getByRole("button", { name: "Add note" }).click();
  await expect(dialog.getByTestId("watch-notes")).toContainText("Meeting the family on Monday.");
  await page.keyboard.press("Escape");
  await expect(bala).toContainText("Followed up by Ravi Kumar");
  await signOut(page);

  // 3. The teacher builds a draft Mathematics paper from the bank, saves it and prints it.
  await loginViaUi(page, { schoolCode: school.schoolCode, email: ravi.email, password: ravi.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Assistance" }).click();
  await page.getByRole("navigation", { name: "Assistance" }).getByRole("link", { name: "Draft papers" }).click();
  await page.getByRole("link", { name: "New draft paper" }).click();
  await expect(page.getByRole("heading", { name: "Build a draft paper" })).toBeVisible();
  await page.locator("select[name=classId]").selectOption({ label: "Class 5" });
  await page.locator("select[name=subjectId]").selectOption({ label: "Mathematics" });
  await page.getByLabel("Title").fill("Unit test practice");
  await page.getByLabel("Questions").first().fill("6");
  await page.getByLabel("Questions").nth(1).fill("2");
  await page.getByTestId("paper-draft").click();
  const paper = page.getByTestId("paper-document");
  await expect(paper.getByTestId("paper-question")).toHaveCount(8);
  await expect(paper).toContainText("Maximum marks: 12");
  await page.getByTestId("paper-save").click();
  await expect(page).toHaveURL(/\/app\/assist\/papers\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { name: "Unit test practice", level: 1 })).toBeVisible();
  await page.evaluate(() => {
    (window as unknown as { printed: boolean }).printed = false;
    window.print = () => {
      (window as unknown as { printed: boolean }).printed = true;
    };
  });
  await page.getByTestId("paper-print").click();
  expect(await page.evaluate(() => (window as unknown as { printed: boolean }).printed)).toBe(true);
  await expect(page.getByRole("button", { name: /export|excel|csv/i })).toHaveCount(0);
});
