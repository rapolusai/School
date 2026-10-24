import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";
import {
  adminCredentials,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  PASSWORD,
  signupViaApi,
  type Credentials,
} from "./support/helpers";

/** Today in India as "YYYY-MM-DD", moved by `days`. */
function indiaDate(days = 0): string {
  return new Date(Date.now() + 330 * 60_000 + days * 86_400_000).toISOString().slice(0, 10);
}

/** The Indian academic year (April to March) that contains today, moved by `offset` years. */
function academicYear(offset = 0) {
  const today = indiaDate();
  const year = (Number(today.slice(5, 7)) >= 4 ? Number(today.slice(0, 4)) : Number(today.slice(0, 4)) - 1) + offset;
  return { startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31`, name: `${year}-${String((year + 1) % 100).padStart(2, "0")}` };
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
    post: <T = { id: string }>(path: string, data?: unknown, status = 201) => call<T>("post", path, data, status),
    put: <T = unknown>(path: string, data: unknown, status = 200) => call<T>("put", path, data, status),
  };
}

const CHILD = { first: "Aarav", last: "Iyer", name: "Aarav Iyer" };
const MOTHER = { name: "Meera Iyer", phone: "98765 12345" };
const ROUTE = "Route 1 – Lake View";
const BOOK = "The Panchatantra";

/**
 * Through the API, the school as it stands before the family comes: the current year, Class 5 A with its class
 * teacher, English and Mathematics, the front office and the principal, a boys' hostel with one room of two beds, a
 * bus route with one stop in a fee zone, and a library book.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const { post, put } = client(request, auth);
  const person = (name: string, email: string): Credentials & { name: string } => ({
    name,
    schoolCode,
    email: `${email}@${schoolCode}.example.com`,
    password: PASSWORD,
  });
  const ravi = person("Ravi Kumar", "ravi");
  const office = person("Olive D'Souza", "olive");
  const principal = person("Prem Kumar", "prem");
  const { id: raviId } = await post("/api/users", { name: ravi.name, email: ravi.email, password: PASSWORD, roles: ["TEACHER"] });
  await post("/api/users", { name: office.name, email: office.email, password: PASSWORD, roles: ["FRONT_OFFICE"] });
  await post("/api/users", { name: principal.name, email: principal.email, password: PASSWORD, roles: ["PRINCIPAL"] });

  const year = academicYear();
  const { id: yearId } = await post("/api/academics/years", { ...year, current: true });
  const { id: classId } = await post("/api/academics/classes", { name: "Class 5", displayOrder: 5 });
  const { id: sectionId } = await post(`/api/academics/classes/${classId}/sections`, { name: "A", capacity: 40, classTeacherId: raviId });
  const { id: english } = await post("/api/academics/subjects", { name: "English" });
  const { id: maths } = await post("/api/academics/subjects", { name: "Mathematics" });
  await put(`/api/academics/classes/${classId}/subjects`, { subjectIds: [english, maths] });

  const { id: hostelId } = await post("/api/hostel/hostels", { name: "Boys' Hostel", type: "BOYS" });
  const { id: blockId } = await post(`/api/hostel/hostels/${hostelId}/blocks`, { name: "A", floors: 2 });
  const { id: roomId } = await post(`/api/hostel/hostels/${hostelId}/blocks/${blockId}/rooms`, {
    floor: 1,
    number: "101",
    capacity: 2,
    type: "DOUBLE",
    monthlyFeePaise: 250_000,
  });
  await post(`/api/hostel/hostels/${hostelId}/rooms/${roomId}/beds`, { labels: ["A", "B"] });

  const { id: vehicleId } = await post("/api/transport/vehicles", {
    registrationNo: "TS09 UB 4410",
    type: "BUS",
    seats: 40,
    insuranceExpiry: indiaDate(200),
    fitnessExpiry: indiaDate(200),
    permitExpiry: indiaDate(200),
    pollutionExpiry: indiaDate(100),
  });
  const { id: zoneId } = await post("/api/transport/fee-zones", { name: "Zone A", minKm: 0, maxKm: 5, annualFeePaise: 1_200_000 });
  const { id: routeId } = await post("/api/transport/routes", { name: ROUTE, vehicleId });
  await put(`/api/transport/routes/${routeId}/stops`, {
    stops: [{ name: "Market Stop", pickupTime: "07:25", dropTime: "15:25", feeZoneId: zoneId }],
  });

  await post("/api/library/titles", { title: BOOK, authors: "Vishnu Sharma", copies: 1, pricePaise: 18_000 });

  return { ravi, office, principal, year, yearId, classId, sectionId, english, maths, routeId, hostelId };
}

// One long journey: a step that cannot act fails within seconds rather than at the end of the test's time.
test.use({ actionTimeout: 20_000, navigationTimeout: 30_000 });

/** A signed-in page of its own for one person, at a desk-sized screen. */
async function signedIn(browser: Browser, creds: Credentials): Promise<Page> {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  await loginViaUi(page, creds);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  return page;
}

/** Opens a mark sheet from the marks list, fills the one student's marks and submits it. */
async function enterAndLock(page: Page, subject: string, fill: (page: Page) => Promise<void>) {
  await page.getByTestId("marks-sheet-link").filter({ hasText: `Class 5 A · ${subject}` }).click();
  await expect(page.getByRole("heading", { name: `Class 5 A · ${subject}` })).toBeVisible();
  await expect(page.getByTestId("marks-entered")).toHaveText("0 of 1 entered");
  await fill(page);
  await expect(page.getByTestId("marks-entered")).toHaveText("1 of 1 entered");
  await page.getByRole("button", { name: "Submit and lock" }).click();
  await page.getByRole("dialog", { name: "Submit and lock these marks?" }).getByRole("button", { name: "Submit and lock" }).click();
  await expect(page.getByTestId("marks-locked")).toContainText("Submitted by Ravi Kumar");
  await page.getByRole("link", { name: "Marks entry" }).click();
  await expect(page).toHaveURL(/\/app\/exams\/marks$/);
}

test("a child's whole time at school: enquiry, admission, attendance, hostel, bus and library, exams and the report card, promotion, and leaving with a transfer certificate", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(420_000);
  const school = newSchool("journey");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin: Auth = { headers: { Authorization: `Bearer ${adminToken}` } };
  const { post, put } = client(request, asAdmin);
  const setup = await setUpSchool(request, school.schoolCode, asAdmin);
  const { ravi, office, principal, year } = setup;
  const parent: Credentials = { schoolCode: school.schoolCode, email: `meera@${school.schoolCode}.example.com`, password: "Parent-temp-2026" };

  // 1. The mother enquires on the school's public page, without signing in.
  await page.goto(`/enquire/${school.schoolCode}`);
  await expect(page.getByTestId("enquiry-school")).toHaveText(school.schoolName);
  await page.getByLabel("Your name").fill(MOTHER.name);
  await page.getByLabel("Mobile number").fill(MOTHER.phone);
  await page.getByLabel("You are the child's").selectOption("MOTHER");
  await page.getByLabel("First name").fill(CHILD.first);
  await page.getByLabel("Last name").fill(CHILD.last);
  await page.getByLabel("Date of birth").fill("2016-05-20");
  await page.getByLabel("Class", { exact: true }).selectOption("Class 5");
  await expect(page.getByRole("radio", { name: year.name })).toBeChecked();
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Send enquiry" }).click();
  await expect(page.getByTestId("enquiry-sent")).toContainText(`Thank you, ${MOTHER.name}`);

  // 2. The front office moves it to application and offered, and admits Aarav into Class 5 A.
  const officePage = await signedIn(browser, office);
  await mainNav(officePage).getByRole("link", { name: "Admissions", exact: true }).click();
  await expect(officePage).toHaveURL(/\/app\/admissions$/);
  const card = officePage.getByRole("article", { name: CHILD.name });
  await expect(officePage.getByTestId("lane-ENQUIRY").getByRole("article", { name: CHILD.name })).toBeVisible();
  const move = async (item: string, dialogName: string) => {
    await card.getByRole("button", { name: `Move ${CHILD.name} to another stage` }).click();
    await officePage.getByRole("menuitem", { name: item }).click();
    const dialog = officePage.getByRole("dialog", { name: dialogName });
    await dialog.getByRole("button", { name: dialogName }).click();
    await expect(dialog).toBeHidden();
  };
  await move("Move to application", "Move to application");
  await expect(officePage.getByTestId("lane-APPLICATION").getByRole("article", { name: CHILD.name })).toBeVisible();
  await move("Offer a place", "Offer a place");
  await expect(officePage.getByTestId("lane-OFFERED").getByRole("article", { name: CHILD.name })).toBeVisible();
  await card.getByRole("button", { name: `Move ${CHILD.name} to another stage` }).click();
  await officePage.getByRole("menuitem", { name: "Admit as student" }).click();
  const admit = officePage.getByRole("dialog", { name: `Admit ${CHILD.name}` });
  await expect(admit.getByLabel("Class and section")).toHaveValue(setup.sectionId);
  await admit.getByLabel("Admission number").fill("E2E/JRN/001");
  await admit.getByLabel("Gender").selectOption("MALE");
  await admit.getByRole("button", { name: "Admit", exact: true }).click();
  await expect(admit.getByTestId("admit-done")).toContainText(`${CHILD.name} is admitted.`);
  await admit.getByTestId("admit-done").getByRole("button", { name: "Close", exact: true }).click();
  const admitted = officePage.getByTestId("lane-ADMITTED").getByRole("article", { name: CHILD.name });
  await admitted.getByRole("link", { name: CHILD.name }).click();
  await officePage.getByTestId("admitted-banner").getByRole("link", { name: "Open student record" }).click();
  await expect(officePage).toHaveURL(/\/app\/students\/[0-9a-f-]{36}$/);
  const studentId = officePage.url().split("/").pop()!;
  await expect(officePage.getByRole("heading", { level: 1 })).toContainText(CHILD.name);
  await expect(officePage.getByRole("heading", { level: 1 })).toContainText("Active");
  await expect(officePage.getByText(/^Class 5 A · .*E2E\/JRN\/001$/)).toBeVisible();

  // 3. The office gives the mother a sign-in, and she sees her child.
  await officePage.getByRole("button", { name: `Give ${MOTHER.name} a sign-in` }).click();
  const signIn = officePage.getByRole("dialog", { name: `Sign-in for ${MOTHER.name}` });
  await signIn.getByLabel("Email", { exact: true }).fill(parent.email);
  await signIn.getByLabel("Temporary password").fill(parent.password);
  await signIn.getByRole("button", { name: "Give sign-in" }).click();
  await expect(signIn).toBeHidden();
  await expect(officePage.getByTestId("guardians-list")).toContainText(`Signs in as ${parent.email}`);
  const parentPage = await signedIn(browser, parent);
  const child = parentPage.getByTestId("child-card");
  await expect(child).toHaveCount(1);
  await expect(child).toContainText(CHILD.name);
  await expect(child).toContainText("Class 5 A");

  // 4. The class teacher marks today's register: Aarav is present.
  const raviPage = await signedIn(browser, ravi);
  await raviPage.getByTestId("mark-attendance-cards").getByRole("link", { name: "Mark attendance for Class 5 A" }).click();
  await expect(raviPage.getByRole("heading", { name: "Class 5 A" })).toBeVisible();
  await raviPage.getByRole("button", { name: "Mark all present" }).click();
  await expect(raviPage.getByRole("group", { name: CHILD.name }).getByRole("button", { name: "Present" })).toHaveAttribute("aria-pressed", "true");
  await raviPage.getByTestId("attendance-save").click();
  const confirmRegister = raviPage.getByRole("dialog", { name: "Save attendance for Class 5 A?" });
  await expect(confirmRegister).toContainText("1 present, 0 absent");
  await confirmRegister.getByRole("button", { name: "Save attendance" }).click();
  await expect(raviPage.getByText(/^Attendance saved for Class 5 A\./)).toBeVisible();
  await expect(raviPage.getByTestId("register-meta")).toContainText("Marked by Ravi Kumar");

  // 5. The admin gives Aarav a hostel bed, a seat on the bus, and lends him a library book.
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/hostel/occupancy");
  const room = page.getByTestId("room-card");
  await expect(room).toHaveAttribute("data-free", "2");
  await room.getByTestId("bed-allot").first().click();
  const allot = page.getByTestId("allot-form");
  await allot.getByTestId("student-search").fill("Aar");
  await page.getByTestId("student-matches").getByRole("button", { name: new RegExp(CHILD.name) }).click();
  await allot.getByTestId("allot-consent").check();
  await allot.getByTestId("allot-save").click();
  await expect(page.getByText("Bed allotted.")).toBeVisible();
  await expect(room).toHaveAttribute("data-free", "1");
  await expect(room.getByTestId("bed").filter({ hasText: CHILD.name })).toHaveCount(1);

  await page.goto(`/app/transport/allocations?routeId=${setup.routeId}`);
  await page.getByTestId("alloc-one").click();
  const bus = page.getByRole("dialog", { name: "Put a student on a bus" });
  await bus.getByRole("searchbox", { name: "Find a student by name or admission number" }).fill(CHILD.first);
  await bus.getByRole("list", { name: "Students" }).getByRole("button", { name: new RegExp(CHILD.name) }).click();
  await bus.getByLabel("Stop", { exact: true }).selectOption({ label: "Market Stop — Zone A · ₹12,000" });
  await expect(bus.getByLabel("First day on the bus", { exact: true })).toHaveValue(indiaDate());
  await bus.getByRole("button", { name: "Put on the bus", exact: true }).click();
  await expect(page.getByText(`${CHILD.name} is on ${ROUTE}.`)).toBeVisible();
  await expect(page.getByTestId("allocation-row").filter({ hasText: CHILD.name })).toContainText("Market Stop");

  await page.goto("/app/library/desk");
  await page.getByRole("searchbox", { name: "Find a student or member of staff" }).fill(CHILD.first);
  await page.getByRole("button", { name: new RegExp(CHILD.name) }).click();
  await expect(page.getByTestId("desk-member-name")).toHaveText(CHILD.name);
  const lend = page.getByTestId("issue-form").getByLabel("Accession number", { exact: true });
  await lend.pressSequentially("LIB00001");
  await lend.press("Enter");
  await expect(page.getByText(new RegExp(`^${BOOK} lent, due `))).toBeVisible();
  await expect(page.getByTestId("desk-loans")).toContainText(BOOK);

  // 6. The half-yearly exam: both papers written in the last few days (never before the year starts).
  type Structure = { terms: { id: string; exams: { id: string }[] }[] };
  const withTerm = await post<Structure>("/api/exams/terms", { academicYearId: setup.yearId, name: "Term 1", startsOn: year.startsOn, endsOn: year.endsOn });
  const withExam = await post<Structure>("/api/exams", { termId: withTerm.terms[0].id, name: "Half-yearly", classIds: [setup.classId] });
  const examId = withExam.terms[0].exams[0].id;
  const written = (days: number) => (indiaDate(-days) < year.startsOn ? year.startsOn : indiaDate(-days));
  const paper = (subjectId: string, date: string, maxTheory: number, maxInternal: number | null) => ({
    subjectId,
    maxTheory,
    maxPractical: null,
    maxInternal,
    date,
    startTime: "09:30",
    durationMinutes: 120,
    room: "Room 12",
  });
  await put(`/api/exams/${examId}/classes/${setup.classId}/papers`, {
    papers: [paper(setup.english, written(2), 80, 20), paper(setup.maths, written(1), 100, null)],
  });

  // The class teacher enters and locks the marks on a phone.
  await raviPage.setViewportSize({ width: 390, height: 844 });
  await raviPage.goto("/app/exams/marks");
  await expect(raviPage.getByTestId("marks-progress")).toHaveText("0 of 2 locked");
  await enterAndLock(raviPage, "English", async (p) => {
    await p.getByRole("textbox", { name: `Theory marks for ${CHILD.name}, out of 80` }).fill("68");
    await p.getByRole("textbox", { name: `Internal marks for ${CHILD.name}, out of 20` }).fill("18");
  });
  await enterAndLock(raviPage, "Mathematics", async (p) => {
    await p.getByRole("textbox", { name: `Theory marks for ${CHILD.name}, out of 100` }).fill("92");
  });
  await expect(raviPage.getByTestId("marks-progress")).toHaveText("2 of 2 locked");

  // The principal publishes the results.
  const principalPage = await signedIn(browser, principal);
  await mainNav(principalPage).getByRole("link", { name: "Exams" }).click();
  await principalPage.getByTestId("exam-class-link").filter({ hasText: "Class 5" }).click();
  await expect(principalPage.getByTestId("results-pending")).toHaveText("Every mark sheet is locked. The results are ready to publish.");
  await principalPage.getByRole("button", { name: "Publish results" }).click();
  await principalPage
    .getByRole("dialog", { name: "Publish the results of Half-yearly for Class 5?" })
    .getByRole("button", { name: "Publish results" })
    .click();
  await expect(principalPage.getByText("Results published. Parents will get a message.")).toBeVisible();

  // The mother opens the report card (the marks memo).
  await parentPage.reload();
  const results = parentPage.getByTestId("family-results-card");
  await expect(results).toContainText("Half-yearly");
  await expect(results).toContainText("Pass");
  await results.getByRole("link", { name: "Results and report card" }).click();
  await expect(parentPage).toHaveURL(/\/app\/family\/results/);
  const report = parentPage.getByRole("article", { name: `Report card of ${CHILD.name}` });
  await expect(report).toContainText("Report card: Term 1");
  await expect(report.getByTestId("card-subjects").getByRole("row", { name: /English/ })).toContainText("86/100");
  await expect(report.getByTestId("card-subjects").getByRole("row", { name: /Mathematics/ })).toContainText("92/100");
  await expect(report.getByTestId("card-percent")).toHaveText("89%");

  // 7. At the end of the year Class 5 A moves up to Class 6 A of the next year.
  const next = academicYear(1);
  await post("/api/academics/years", { ...next, current: false });
  const { id: class6 } = await post("/api/academics/classes", { name: "Class 6", displayOrder: 6 });
  await post(`/api/academics/classes/${class6}/sections`, { name: "A", capacity: 40 });
  await page.goto("/app/students");
  await page.getByRole("button", { name: "Promote" }).click();
  const promote = page.getByRole("dialog", { name: "Promote a section" });
  await promote.getByLabel("From year").selectOption({ label: year.name });
  await promote.getByLabel("From section").selectOption({ label: "Class 5 A" });
  await promote.getByRole("radio", { name: "Move up" }).check();
  await promote.getByLabel("To year").selectOption({ label: next.name });
  await promote.getByLabel("To section").selectOption({ label: "Class 6 A" });
  await promote.getByRole("button", { name: "Promote students" }).click();
  await expect(promote.getByTestId("promotion-result")).toContainText("1 student promoted.");
  await promote.getByRole("button", { name: "Done" }).click();
  const detail = (await (await request.get(`/api/students/${studentId}`, asAdmin)).json()) as {
    enrollments: { academicYearName: string; className: string; sectionName: string }[];
  };
  expect(detail.enrollments.map((e) => `${e.academicYearName} ${e.className} ${e.sectionName}`)).toEqual([
    `${next.name} Class 6 A`,
    `${year.name} Class 5 A`,
  ]);

  // 8. The family moves away. The office prepares a TC and is warned that a library book is still out.
  await mainNav(officePage).getByRole("link", { name: "Certificates and ID cards", exact: true }).click();
  await officePage.getByRole("button", { name: "Prepare a certificate" }).click();
  const prepare = officePage.getByRole("dialog", { name: "Prepare a certificate" });
  await prepare.getByLabel("Type", { exact: true }).selectOption("TRANSFER");
  await prepare.getByRole("searchbox", { name: "Find a student" }).fill(CHILD.first);
  await prepare.getByRole("button", { name: new RegExp(CHILD.name) }).click();
  await expect(prepare.getByTestId("picked-subject")).toContainText(CHILD.name);
  await prepare.getByLabel("Purpose", { exact: true }).fill("Family moving to Bengaluru");
  await prepare.getByRole("button", { name: "Prepare", exact: true }).click();
  await expect(officePage).toHaveURL(/\/app\/certificates\/[0-9a-f-]+$/);
  const certificateId = officePage.url().split("/").pop()!;
  await expect(officePage.getByTestId("certificate-document")).toHaveAttribute("data-status", "PREPARED");
  await expect(officePage.getByTestId("certificate-document")).toContainText(CHILD.name);
  await expect(officePage.getByTestId("books-warning")).toHaveText("Library books still out: 1. Ask for them back before signing.");

  // The book comes back at the desk (on time, so no fine), and the warning goes.
  await page.goto("/app/library/desk");
  const returned = page.getByTestId("return-form").getByLabel("Accession number", { exact: true });
  await returned.pressSequentially("LIB00001");
  await returned.press("Enter");
  await expect(page.getByTestId("return-result")).toContainText(`${BOOK} (LIB00001) back from ${CHILD.name}.`);
  await officePage.reload();
  await expect(officePage.getByTestId("certificate-document")).toHaveAttribute("data-status", "PREPARED");
  await expect(officePage.getByTestId("books-warning")).toHaveCount(0);

  // 9. The principal signs the TC: the first TC serial of the year and a QR code.
  await principalPage.goto(`/app/certificates/${certificateId}`);
  await expect(principalPage.getByTestId("books-warning")).toHaveCount(0);
  await principalPage.getByTestId("sign-certificate").click();
  const sign = principalPage.getByRole("dialog", { name: "Sign this certificate?" });
  await expect(sign).toContainText(CHILD.name);
  await sign.getByRole("button", { name: "Sign and issue" }).click();
  await expect(principalPage.getByText("Certificate signed and issued.")).toBeVisible();
  const serial = `TC/${year.name}/0001`;
  await expect(principalPage.getByTestId("certificate-serial")).toHaveText(serial);
  await expect(principalPage.getByTestId("certificate-document")).toHaveAttribute("data-status", "ISSUED");
  await expect(principalPage.getByTestId("qr-code")).toBeVisible();
  const verifyText = await principalPage.getByTestId("certificate-verify-url").textContent();
  const verifyUrl = verifyText!.replace(/^Verify at /, "").trim();
  expect(verifyUrl).toMatch(/\/verify\/[A-Za-z0-9_-]+$/);

  // 10. Aarav is now Transferred; his hostel bed is vacated and his bus seat ended, both from today.
  await officePage.goto(`/app/students/${studentId}`);
  await expect(officePage.getByRole("heading", { level: 1 })).toContainText("Transferred");
  await expect(officePage.getByRole("status").filter({ hasText: "Family moving to Bengaluru" })).toBeVisible();
  await expect(officePage.getByTestId("student-boarder")).toHaveText("Day scholar");
  const boarding = (await (await request.get(`/api/hostel/students/${studentId}/boarding`, asAdmin)).json()) as {
    boarder: boolean;
    history: { status: string; toDate: string | null; vacateReason: string | null }[];
  };
  expect(boarding.boarder).toBe(false);
  expect(boarding.history).toHaveLength(1);
  expect(boarding.history[0]).toMatchObject({ status: "VACATED", toDate: indiaDate(), vacateReason: "Left the school (transferred)" });

  await page.goto("/app/hostel/occupancy");
  await expect(page.getByTestId("room-card")).toHaveAttribute("data-free", "2");
  await expect(page.getByTestId("room-card").getByTestId("bed").filter({ hasText: CHILD.name })).toHaveCount(0);

  await page.goto(`/app/transport/allocations?routeId=${setup.routeId}`);
  await expect(page.getByTestId("allocation-row")).toHaveCount(0);
  await page.getByTestId("allocations").getByLabel("Status").selectOption("ENDED");
  const ended = page.getByTestId("allocation-row").filter({ hasText: CHILD.name });
  await expect(ended).toHaveCount(1);
  await expect(ended).toContainText("Ended");
  await expect(ended).toContainText("Left the school (transferred)");

  // 11. Anyone who scans the QR code sees a genuine TC with only the first name.
  const anyone = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const verifyPage = await anyone.newPage();
  await verifyPage.goto(verifyUrl);
  await expect(verifyPage.getByTestId("verify-result")).toHaveAttribute("data-valid", "true");
  await expect(verifyPage.getByTestId("verify-status")).toHaveText("Genuine certificate");
  await expect(verifyPage.getByTestId("verify-serial")).toHaveText(serial);
  await expect(verifyPage.getByTestId("verify-result")).toContainText(CHILD.first);
  await expect(verifyPage.getByTestId("verify-result")).not.toContainText(CHILD.last);
  await anyone.close();

  // 12. A second TC for the same child is refused while this one stands.
  const second = await request.post("/api/certificates", { ...asAdmin, data: { type: "TRANSFER", studentId, purpose: "Again" } });
  expect(second.status(), await second.text()).toBe(409);
  expect(((await second.json()) as { errors?: Record<string, string> }).errors).toHaveProperty("studentId");

  for (const p of [officePage, parentPage, raviPage, principalPage]) await p.context().close();
});
