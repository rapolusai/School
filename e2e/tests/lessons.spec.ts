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

const QUESTIONS = {
  mcq: "Which fraction is bigger?",
  tf: "2/4 is equal to 1/2.",
  short: "Explain how you compare two fractions.",
};

/**
 * Through the API: the current year, a maths teacher and a principal, Class 5 A with Mathematics taught by the
 * teacher, two students (Kabir has his own sign-in and so does his mother), the Class 5 Mathematics syllabus, and
 * three questions in the bank: one choice, one true-or-false and one short answer.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const { post, put } = client(request, auth);
  const ravi = { name: "Ravi Kumar", email: `ravi@${schoolCode}.example.com`, password: PASSWORD };
  const lakshmi = { name: "Lakshmi Iyer", email: `lakshmi@${schoolCode}.example.com`, password: PASSWORD };
  const kabir = { email: `kabir@${schoolCode}.example.com`, password: PASSWORD };
  const parent = { email: `meena@${schoolCode}.example.com`, password: PASSWORD };
  const { id: raviId } = await post("/api/users", { ...ravi, roles: ["TEACHER"] });
  await post("/api/users", { ...lakshmi, roles: ["PRINCIPAL"] });

  const today = indiaDate();
  const year = academicYearAround(today);
  await post("/api/academics/years", { ...year, current: true });
  const { id: classId } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: sectionId } = await post(`/api/academics/classes/${classId}/sections`, {
    name: "A",
    capacity: 40,
    classTeacherId: raviId,
  });
  const { id: maths } = await post("/api/academics/subjects", { name: "Mathematics" });
  await put(`/api/academics/classes/${classId}/subjects`, { subjectIds: [maths] });
  await post("/api/timetable/assignments", { sectionId, subjectId: maths, teacherId: raviId, periodsPerWeek: 6 });

  const students: { id: string; guardians: { id: string }[] }[] = [];
  for (const [i, s] of [
    { first: "Kabir", last: "Mehta", guardian: "Meena Mehta", phone: "98765 00001" },
    { first: "Bala", last: "Iyer", guardian: "Uma Iyer", phone: "98765 00002" },
  ].entries()) {
    students.push(
      await post("/api/students", {
        admissionNo: `E2E/LS/${i + 1}`,
        firstName: s.first,
        lastName: s.last,
        dateOfBirth: "2016-04-12",
        gender: s.first === "Kabir" ? "MALE" : "FEMALE",
        admissionDate: year.startsOn,
        sectionId,
        rollNo: i + 1,
        guardians: [{ name: s.guardian, relation: "MOTHER", phone: s.phone, primary: true }],
      }),
    );
  }
  const [kabirRecord, balaRecord] = students;
  await post(`/api/students/${kabirRecord.id}/sign-in`, { mode: "CREATE", ...kabir }, 200);
  await post(`/api/students/${kabirRecord.id}/guardians/${kabirRecord.guardians[0].id}/sign-in`, { mode: "CREATE", ...parent }, 200);

  type Syllabus = { units: { topics: { id: string; title: string }[] }[] };
  const month = today.slice(0, 7);
  const syllabus = await post<Syllabus>("/api/lessons/syllabi", {
    classId,
    subjectId: maths,
    units: [
      {
        title: "Numbers",
        topics: [
          { title: "Fractions", plannedPeriods: 6, targetMonth: month },
          { title: "Decimals", plannedPeriods: 6, targetMonth: year.endsOn.slice(0, 7) },
        ],
      },
    ],
  });
  const fractions = syllabus.units[0].topics.find((topic) => topic.title === "Fractions")!;

  const question = (body: Record<string, unknown>) =>
    post("/api/lessons/questions", { classId, subjectId: maths, topicId: fractions.id, difficulty: "EASY", shared: true, ...body });
  await question({
    type: "MCQ_SINGLE",
    text: QUESTIONS.mcq,
    marks: 1,
    options: [
      { text: "1/2", correct: true },
      { text: "1/3", correct: false },
    ],
  });
  await question({ type: "TRUE_FALSE", text: QUESTIONS.tf, answerText: "TRUE", marks: 1 });
  await question({
    type: "SHORT_ANSWER",
    text: QUESTIONS.short,
    answerText: "Make the denominators the same, then compare the numerators.",
    marks: 2,
    difficulty: "MEDIUM",
  });

  return { ravi, lakshmi, kabir, parent, fractionsId: fractions.id, kabirId: kabirRecord.id, balaId: balaRecord.id };
}

/** "YYYY-MM-DDTHH:MM" for a datetime-local field, in India time. */
function indiaDateTime(days: number, time: string): string {
  return `${indiaDate(days)}T${time}`;
}

async function openLessons(page: Page) {
  await mainNav(page).getByRole("link", { name: "Lessons", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/lessons$/);
}

test("a teacher's plan is approved, a test from the bank is taken on a phone, marked and released to the parent", async ({
  page,
  request,
}) => {
  test.setTimeout(240_000);
  const school = newSchool("lesson");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin = { headers: { Authorization: `Bearer ${adminToken}` } };
  const { ravi, lakshmi, kabir, parent, fractionsId, kabirId, balaId } = await setUpSchool(
    request,
    school.schoolCode,
    asAdmin,
  );
  const code = school.schoolCode;

  // Messages about new tests are off unless the school switches them on.
  const settings = await request.get("/api/lessons/settings", asAdmin);
  expect(settings.status()).toBe(200);
  expect(await settings.json()).toEqual({ testNotificationsEnabled: false });

  // 1. The teacher writes a lesson plan for the Fractions topic and submits it.
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginViaUi(page, { schoolCode: code, email: ravi.email, password: ravi.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await openLessons(page);
  await expect(page.getByTestId("plans-empty")).toBeVisible();
  await page.getByRole("button", { name: "New plan" }).click();
  const editor = page.getByTestId("plan-editor");
  await editor.getByLabel("Section", { exact: true }).selectOption({ label: "Class 5 A" });
  await expect(editor.getByLabel("Subject", { exact: true })).toHaveValue(/.+/);
  await expect(editor.getByLabel("Topic").locator(`option[value="${fractionsId}"]`)).toHaveCount(1);
  await editor.getByLabel("Topic").selectOption(fractionsId);
  await editor.getByLabel("Periods").fill("2");
  await editor.getByLabel("Learning objectives").fill("Compare fractions with the same denominator.");
  await editor.getByRole("button", { name: "Save draft" }).click();
  await expect(page).toHaveURL(/\/app\/lessons\/plans\/[0-9a-f-]+$/);
  const plan = page.getByTestId("plan-detail");
  await expect(page.getByRole("heading", { level: 1, name: "Fractions" })).toBeVisible();
  await expect(plan.getByText("Draft", { exact: true })).toBeVisible();
  await plan.getByRole("button", { name: "Submit for approval" }).click();
  await expect(plan.getByText("Waiting for approval", { exact: true })).toBeVisible();
  await signOut(page);

  // 2. The principal finds it waiting on her dashboard and approves it.
  await loginViaUi(page, { schoolCode: code, email: lakshmi.email, password: lakshmi.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const waiting = page.getByTestId("tile-plansToApprove");
  await expect(waiting).toContainText("1");
  await waiting.getByRole("link", { name: "Plans waiting for approval" }).click();
  await expect(page).toHaveURL(/\/app\/lessons\/approvals$/);
  await page.getByTestId("plan-row").filter({ hasText: "Ravi Kumar" }).click();
  await page.getByTestId("plan-detail").getByRole("button", { name: "Approve" }).click();
  const approve = page.getByRole("dialog", { name: "Approve this plan?" });
  await approve.getByLabel("Comment (optional)").fill("Good. Use fraction strips.");
  await approve.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByTestId("plan-detail").getByText("Approved", { exact: true })).toBeVisible();
  await expect(page.getByTestId("plan-comment")).toContainText("Good. Use fraction strips.");

  // Syllabus coverage in the reports hub: nothing is taught yet.
  await mainNav(page).getByRole("link", { name: "Reports", exact: true }).click();
  await page.getByTestId("report-link-syllabusCoverage").click();
  await expect(page).toHaveURL(/\/app\/reports\/syllabus-coverage$/);
  const coverage = page.getByTestId("coverage-row").filter({ hasText: "Class 5 A" });
  await expect(coverage).toContainText("Mathematics");
  await expect(coverage).toContainText("0 / 2");
  await expect(page.getByRole("button", { name: "CSV" })).toBeEnabled();
  await signOut(page);

  // 3. The teacher builds an online test from the question bank and publishes it.
  await loginViaUi(page, { schoolCode: code, email: ravi.email, password: ravi.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await openLessons(page);
  await expect(page.getByTestId("plan-row").filter({ hasText: "Fractions" })).toContainText("Approved");
  await page.getByRole("navigation", { name: "Lessons" }).getByRole("link", { name: "Online tests" }).click();
  await expect(page).toHaveURL(/\/app\/lessons\/tests$/);
  await page.getByRole("button", { name: "New test" }).click();
  const settingsForm = page.getByTestId("test-form");
  await settingsForm.getByLabel("Class", { exact: true }).selectOption({ label: "Class 5" });
  await settingsForm.getByLabel("Subject", { exact: true }).selectOption({ label: "Mathematics" });
  await settingsForm.getByRole("checkbox", { name: "Class 5 A" }).check();
  await settingsForm.getByLabel("Title", { exact: true }).fill("Fractions quiz");
  await settingsForm.getByLabel("Opens", { exact: true }).fill(indiaDateTime(-1, "08:00"));
  await settingsForm.getByLabel("Closes", { exact: true }).fill(indiaDateTime(2, "20:00"));
  await settingsForm.getByLabel("Time allowed (minutes)").fill("20");
  await settingsForm.getByRole("button", { name: "Save" }).click();
  await expect(page).toHaveURL(/\/app\/lessons\/tests\/[0-9a-f-]+$/);
  const testUrl = page.url();
  await expect(page.getByRole("heading", { level: 1, name: "Fractions quiz" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Publish" })).toBeDisabled();

  await page.getByRole("button", { name: "Add from bank" }).click();
  const bank = page.getByRole("dialog", { name: "Add from bank" });
  for (const text of [QUESTIONS.mcq, QUESTIONS.tf, QUESTIONS.short]) {
    await bank.getByRole("checkbox", { name: `Add “${text}”` }).check();
  }
  await bank.getByRole("button", { name: "Add 3 questions" }).click();
  const questions = page.getByTestId("test-questions");
  await expect(questions.getByTestId("test-question")).toHaveCount(3);
  await expect(questions).toContainText("3 questions · 4 marks");
  await page.getByRole("button", { name: "Publish" }).click();
  await expect(page.getByText("Test published")).toBeVisible();
  await expect(page.getByTestId("test-settings")).toContainText("Published");
  await signOut(page);

  // A parent cannot reach the staff test screens.
  const parentToken = await loginViaApi(request, { schoolCode: code, ...parent });
  const asParent = { headers: { Authorization: `Bearer ${parentToken}` } };
  expect((await request.get("/api/lessons/tests", asParent)).status()).toBe(403);

  // 4. Kabir takes the test on his phone: one question per screen, saved as he goes.
  await page.setViewportSize({ width: 390, height: 844 });
  await loginViaUi(page, { schoolCode: code, ...kabir });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const studyCard = page.getByTestId("family-study-card");
  await expect(studyCard).toContainText("1 online test open");
  await studyCard.getByRole("link", { name: "Study material and tests" }).click();
  await expect(page).toHaveURL(/\/app\/family\/study/);
  await page.getByTestId("study-test").filter({ hasText: "Fractions quiz" }).click();
  await expect(page.getByTestId("test-intro")).toContainText("20 minutes");
  await expectNoHorizontalScroll(page);
  await page.getByRole("button", { name: "Start the test" }).click();

  const runner = page.getByTestId("attempt-runner");
  await expect(runner.getByRole("timer")).toHaveText(/^(20:00|19:5\d)$/);
  await expect(runner.getByTestId("attempt-progress")).toHaveText("Question 1 of 3");
  await expect(runner.getByText(QUESTIONS.mcq)).toBeVisible();
  await expect(runner.getByText(QUESTIONS.tf)).toHaveCount(0);
  await runner.getByRole("radio", { name: "1/2" }).check();
  await expect(runner.getByTestId("save-state")).toHaveText("Saved");
  await expectNoHorizontalScroll(page);
  await runner.getByRole("button", { name: "Next" }).click();

  await expect(runner.getByTestId("attempt-progress")).toHaveText("Question 2 of 3");
  await expect(runner.getByText(QUESTIONS.tf)).toBeVisible();
  await runner.getByRole("radio", { name: "True" }).check();
  await expect(runner.getByTestId("save-state")).toHaveText("Saved");
  await runner.getByRole("button", { name: "Next" }).click();

  await expect(runner.getByTestId("attempt-progress")).toHaveText("Question 3 of 3");
  await runner.getByRole("textbox", { name: "Your answer" }).fill("I make the bottom numbers the same.");
  await expect(runner.getByTestId("save-state")).toHaveText("Saved");
  await expect(runner).toContainText("3 of 3 answered");
  await runner.getByTestId("submit-test").click();
  const submit = page.getByRole("dialog", { name: "Submit your answers?" });
  await expect(submit).toContainText("You have answered every question.");
  await submit.getByRole("button", { name: "Submit test" }).click();
  await expect(page.getByTestId("attempt-done")).toContainText("Your answers are submitted.");
  await expectNoHorizontalScroll(page);

  // His one attempt is used, and the staff results stay closed to him.
  const kabirToken = await loginViaApi(request, { schoolCode: code, ...kabir });
  const asKabir = { headers: { Authorization: `Bearer ${kabirToken}` } };
  const testId = testUrl.split("/").pop()!;
  expect((await request.post(`/api/me/tests/${testId}/attempts`, asKabir)).status()).toBe(409);
  expect((await request.get(`/api/lessons/tests/${testId}/results`, asKabir)).status()).toBe(403);
  await signOut(page);

  // 5. The teacher marks the short answer and releases the results.
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginViaUi(page, { schoolCode: code, email: ravi.email, password: ravi.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("tile-answersToMark")).toContainText("1");
  await page.goto(`${testUrl}/results`);
  await expect(page.getByTestId("results-pending")).toContainText("1");
  await expect(page.getByTestId("release-blocker")).toHaveText("The test is still open. Close it or wait until it closes.");
  await expect(page.getByRole("button", { name: "Release results" })).toBeDisabled();
  await page.getByRole("link", { name: "Open Kabir Mehta's answers" }).click();
  const marking = page.getByTestId("attempt-marking");
  await expect(marking.getByTestId("attempt-score")).toHaveText(/2 \/ 4/);
  const short = marking.getByTestId("answer-review").filter({ hasText: QUESTIONS.short });
  await expect(short).toContainText("I make the bottom numbers the same.");
  await short.getByLabel("Marks (of 2)").fill("1.5");
  await short.getByLabel("Feedback").fill("Say what you then do with the top numbers.");
  await marking.getByRole("button", { name: "Save marks" }).click();
  await expect(page.getByText("Marks saved")).toBeVisible();
  await expect(marking.getByTestId("attempt-score")).toHaveText(/3\.5 \/ 4/);

  // Results go out once the test has closed: the teacher closes it early.
  await page.goto(testUrl);
  await page.getByRole("button", { name: "Close now" }).click();
  const closeNow = page.getByRole("dialog", { name: "Close this test now?" });
  await closeNow.getByRole("button", { name: "Close now" }).click();
  await expect(page.getByText("Test closed")).toBeVisible();
  await page.goto(`${testUrl}/results`);
  await page.getByRole("button", { name: "Release results" }).click();
  const release = page.getByRole("dialog", { name: "Release the results?" });
  await release.getByRole("button", { name: "Release results" }).click();
  await expect(page.getByTestId("test-results")).toContainText("Released");
  await signOut(page);

  // 6. Kabir's mother sees the released score on her phone.
  await page.setViewportSize({ width: 390, height: 844 });
  await loginViaUi(page, { schoolCode: code, ...parent });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  const familyCard = page.getByTestId("family-study-card");
  await expect(familyCard).toContainText("Fractions quiz: Score 3.5 / 4 · 87.5%");
  await familyCard.getByRole("link", { name: "Study material and tests" }).click();
  await expect(page).toHaveURL(/\/app\/family\/study/);
  const row = page.getByTestId("study-test").filter({ hasText: "Fractions quiz" });
  await expect(row.getByTestId("study-test-status")).toHaveText("Score 3.5 / 4 · 87.5%");
  await expectNoHorizontalScroll(page);

  // The score reached the parent through her own child only.
  const study = await request.get(`/api/me/children/${kabirId}/study`, asParent);
  expect(study.status()).toBe(200);
  expect(((await study.json()) as { tests: { score: number | null }[] }).tests[0].score).toBe(3.5);
  expect((await request.get(`/api/me/children/${balaId}/study`, asParent)).status()).toBe(404);
});
