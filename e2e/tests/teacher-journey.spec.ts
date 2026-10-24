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

const MONTHS = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
];

/** "YYYY-MM-DD" of a UTC date. */
const iso = (date: Date) => date.toISOString().slice(0, 10);

const addDays = (date: Date, days: number) => new Date(date.getTime() + days * 86_400_000);

/** Today in India as a UTC-midnight date. */
function todayInIndia(): Date {
  const india = new Date(Date.now() + 330 * 60_000);
  return new Date(Date.UTC(india.getUTCFullYear(), india.getUTCMonth(), india.getUTCDate()));
}

/** The Indian academic year (April to March) that contains today. */
function academicYearAround(today: Date) {
  const year = today.getUTCMonth() >= 3 ? today.getUTCFullYear() : today.getUTCFullYear() - 1;
  return { startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31`, name: `${year}-${String((year + 1) % 100).padStart(2, "0")}` };
}

/** This month in India as "YYYY-MM" with its English name ("October 2026"). */
function monthOf(today: Date) {
  const index = today.getUTCMonth();
  return { value: `${today.getUTCFullYear()}-${String(index + 1).padStart(2, "0")}`, label: `${MONTHS[index]} ${today.getUTCFullYear()}` };
}

/**
 * A Monday and Tuesday (two working days) inside the academic year that do not include today: the next such pair
 * at least three days ahead, or the one before last when the year ends too soon.
 */
function twoWorkingDays(today: Date, endsOn: string): { from: string; to: string } {
  let monday = addDays(today, 3);
  while (monday.getUTCDay() !== 1) monday = addDays(monday, 1);
  if (iso(addDays(monday, 1)) > endsOn) {
    monday = addDays(today, -8);
    while (monday.getUTCDay() !== 1) monday = addDays(monday, -1);
  }
  return { from: iso(monday), to: iso(addDays(monday, 1)) };
}

type Auth = { headers: { Authorization: string } };

async function send<T>(request: APIRequestContext, method: "post" | "put", path: string, auth: Auth, data?: unknown) {
  const res = await request[method](path, { ...auth, data });
  expect(res.status(), `${path}: ${await res.text()}`).toBeLessThan(300);
  return (await res.json()) as T;
}

/** A signed-in page of its own for one person, at a desk-sized screen. */
async function signedIn(browser: Browser, creds: Credentials): Promise<Page> {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  await loginViaUi(page, creds);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  return page;
}

// One long journey: a step that cannot act fails within seconds rather than at the end of the test's time.
test.use({ actionTimeout: 20_000, navigationTimeout: 30_000 });

test("a teacher's whole time at school: joining with a sign-in, class teacher, attendance and leave, salary and payslip, leaving and an experience certificate", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(300_000);
  const school = newSchool("tjourney");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin: Auth = { headers: { Authorization: `Bearer ${adminToken}` } };
  const today = todayInIndia();
  const year = academicYearAround(today);
  const month = monthOf(today);
  const teacher: Credentials & { name: string } = {
    name: "Neha Gupta",
    schoolCode: school.schoolCode,
    email: `neha@${school.schoolCode}.example.com`,
    password: "Teacher-temp-2026",
  };
  const principal: Credentials & { name: string } = {
    name: "Meena Iyer",
    schoolCode: school.schoolCode,
    email: `meena@${school.schoolCode}.example.com`,
    password: PASSWORD,
  };

  // Through the API: the current year, the usual leave types, the principal, Class 5 A without a class teacher, and
  // the school's payroll settings (Telangana professional tax) and standard pay components.
  await send(request, "post", "/api/academics/years", asAdmin, { ...year, current: true });
  await send(request, "post", "/api/leave/types/standard", asAdmin);
  await send(request, "post", "/api/staff", asAdmin, {
    name: principal.name,
    email: principal.email,
    password: principal.password,
    roles: ["PRINCIPAL"],
    employeeCode: "E2E-001",
    designation: "Principal",
    departmentId: null,
    employmentType: "PERMANENT",
    dateOfJoining: "2018-04-01",
    mobile: "98480 12346",
  });
  const { id: classId } = await send<{ id: string }>(request, "post", "/api/academics/classes", asAdmin, { name: "Class 5" });
  const { id: sectionId } = await send<{ id: string }>(request, "post", `/api/academics/classes/${classId}/sections`, asAdmin, {
    name: "A",
    capacity: 40,
  });
  await send(request, "put", "/api/payroll/settings", asAdmin, {
    payDay: 1,
    prorationBasis: "CALENDAR_DAYS",
    pfEnabled: true,
    pfOnFullWages: false,
    esiEnabled: true,
    ptEnabled: true,
    ptState: "TG",
    pfEstablishmentCode: "TSHYD0012345000",
    esiEmployerCode: null,
    ptRegistration: null,
    tan: "HYDA12345B",
    bankName: "State Bank of India",
    bankBranch: "Ameerpet",
    bankAccountNumber: "000000555555",
    bankIfsc: "SBIN0001234",
  });
  const components = await send<{ id: string; code: string }[]>(request, "post", "/api/payroll/components/standard", asAdmin);
  const componentId = (code: string) => components.find((c) => c.code === code)!.id;

  // 1. The School Admin adds the teacher: a sign-in and a complete staff profile in one form.
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Staff", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/staff$/);
  await page.getByRole("button", { name: "Add staff" }).first().click();
  const add = page.getByRole("dialog", { name: "Add staff member" });
  await add.getByLabel("Full name", { exact: true }).fill(teacher.name);
  await add.getByLabel("Email", { exact: true }).fill(teacher.email);
  await add.getByLabel("Temporary password", { exact: true }).fill(teacher.password);
  await add.getByRole("checkbox", { name: "Teacher", exact: true }).check();
  await add.getByLabel("Employee code", { exact: true }).fill("E2E-101");
  await add.getByLabel("Designation", { exact: true }).fill("TGT English");
  await add.getByLabel("Employment type", { exact: true }).selectOption("PERMANENT");
  await add.getByLabel("Date of joining", { exact: true }).fill("2020-06-01");
  await add.getByLabel("Mobile", { exact: true }).first().fill("98480 12345");
  await add.getByLabel("Qualifications", { exact: true }).fill("M.A. English, B.Ed.");
  const emergency = add.getByRole("group", { name: "Emergency contact (optional)" });
  await emergency.getByLabel("Name", { exact: true }).fill("Rohit Gupta");
  await emergency.getByLabel("Mobile", { exact: true }).fill("98480 54321");
  await add.getByRole("button", { name: "Add staff member" }).click();
  await expect(add).toBeHidden();
  await expect(page.getByText(`${teacher.name} added to the staff.`)).toBeVisible();
  await page.getByTestId("staff-table").getByRole("link", { name: new RegExp(teacher.name) }).click();
  await expect(page).toHaveURL(/\/app\/staff\/[0-9a-f-]{36}$/);
  const teacherId = page.url().split("/").pop()!;
  await expect(page.getByTestId("staff-name")).toContainText(teacher.name);
  await expect(page.getByTestId("staff-name")).toContainText("Active");
  const profile = page.getByTestId("staff-profile");
  await expect(profile).toContainText("E2E-101");
  await expect(profile).toContainText("TGT English");
  await expect(profile).toContainText("M.A. English, B.Ed.");
  await expect(profile).toContainText("Rohit Gupta");

  // 2. The admin makes her class teacher of Class 5 A.
  await mainNav(page).getByRole("link", { name: "School setup" }).click();
  await page.getByRole("tab", { name: "Classes & sections" }).click();
  const class5 = page.getByRole("article", { name: "Class 5" });
  await class5.getByRole("button", { name: "Edit Class 5 A" }).click();
  const editSection = page.getByRole("dialog", { name: "Edit Class 5 A" });
  await editSection.getByLabel("Class teacher", { exact: true }).selectOption({ label: teacher.name });
  await editSection.getByRole("button", { name: "Save" }).click();
  await expect(editSection).toBeHidden();
  await expect(class5).toContainText(`Class teacher ${teacher.name}`);

  // 3. The teacher signs in, sees her class, checks in and applies for two days of casual leave.
  const teacherPage = await signedIn(browser, teacher);
  await expect(teacherPage.getByTestId("mark-attendance-cards").getByRole("link", { name: "Mark attendance for Class 5 A" })).toBeVisible();
  const checkIn = teacherPage.getByTestId("check-in-card");
  await expect(checkIn.getByTestId("check-in-state")).toHaveText("Not checked in yet");
  await checkIn.getByRole("button", { name: "Check in" }).click();
  await expect(checkIn.getByTestId("check-in-state")).toContainText("Checked in at");

  const { from, to } = twoWorkingDays(today, year.endsOn);
  await mainNav(teacherPage).getByRole("link", { name: "Leave", exact: true }).click();
  await expect(teacherPage.getByTestId("balance-CL").getByTestId("balance-available")).toHaveText("12");
  await teacherPage.getByTestId("apply-leave").click();
  const apply = teacherPage.getByRole("dialog", { name: "Apply for leave" });
  await apply.getByLabel("Leave type").selectOption({ label: "Casual leave · 12 left" });
  await apply.getByLabel("From").fill(from);
  await apply.getByLabel("To").fill(to);
  await expect(apply.getByTestId("leave-preview")).toContainText("2 days of leave");
  await apply.getByLabel("Reason").fill("Sister's wedding");
  await apply.getByRole("button", { name: "Apply" }).click();
  await expect(teacherPage.getByText("Leave applied for. It goes to the School Admin or Principal.")).toBeVisible();

  // 4. The principal approves it.
  const principalPage = await signedIn(browser, principal);
  const waiting = principalPage.getByTestId("leave-inbox-card");
  await expect(waiting).toContainText(teacher.name);
  await waiting.getByRole("link", { name: "Review requests" }).click();
  await principalPage.getByTestId("leave-inbox").getByRole("button", { name: `Approve leave of ${teacher.name}` }).click();
  await principalPage.getByRole("dialog", { name: "Approve leave" }).getByRole("button", { name: "Approve" }).click();
  await expect(principalPage.getByText(`Leave of ${teacher.name} approved.`)).toBeVisible();
  await teacherPage.reload();
  await expect(teacherPage.getByTestId("balance-CL").getByTestId("balance-available")).toHaveText("10");
  await expect(teacherPage.getByTestId("my-requests").getByTestId("leave-request")).toContainText("Approved by Meena Iyer");

  // 5. Her salary from this month (₹28,000 gross; PF ₹1,800 and professional tax ₹200, so ₹26,000 net), with her bank
  // account and PAN.
  await send(request, "put", `/api/payroll/staff/${teacherId}/pay-profile`, asAdmin, {
    bankAccountNumber: "000000129012",
    bankIfsc: "SBIN0001234",
    accountHolderName: null,
    pan: "ABCDE1234F",
    uan: "100200300400",
    esiNumber: null,
    pfMember: true,
    ptWomenSlab: false,
  });
  await send(request, "post", `/api/payroll/staff/${teacherId}/structures`, asAdmin, {
    effectiveFrom: month.value,
    note: "Salary on joining the payroll",
    lines: [
      { componentId: componentId("BASIC"), monthlyPaise: 20_000_00 },
      { componentId: componentId("HRA"), monthlyPaise: 8_000_00 },
    ],
  });

  // 6. The admin works out the month, approves it and marks it paid.
  await mainNav(page).getByRole("link", { name: "Payroll", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/payroll$/);
  await page.getByRole("button", { name: "Work out a month" }).click();
  const create = page.getByRole("dialog", { name: "Work out a month's payroll" });
  await expect(create.getByLabel("Month")).toHaveValue(month.value);
  await create.getByRole("button", { name: "Work out payroll" }).click();
  await expect(page.getByRole("heading", { level: 1, name: `Payroll for ${month.label}` })).toBeVisible();
  await expect(page.getByTestId("run-status")).toHaveText("Draft");
  await expect(page.getByTestId("run-payslip-row").filter({ hasText: teacher.name })).toContainText("₹26,000");
  await page.getByTestId("approve-run").click();
  await page.getByRole("dialog", { name: `Approve payroll for ${month.label}?` }).getByRole("button", { name: "Approve and lock" }).click();
  await expect(page.getByTestId("run-status")).toHaveText("Approved");
  await page.getByRole("button", { name: "Mark paid" }).click();
  const paid = page.getByRole("dialog", { name: "Mark salaries paid" });
  await paid.getByLabel("Bank reference").fill("UTR2026E2E01");
  await paid.getByRole("button", { name: "Mark paid" }).click();
  await expect(page.getByText("Payment recorded.")).toBeVisible();
  await expect(page.getByTestId("run-status")).toHaveText("Paid");

  // 7. The teacher opens her payslip: paid, ₹26,000.
  await teacherPage.goto("/app/my-payslips");
  const mine = teacherPage.getByTestId("my-payslip-link");
  await expect(mine).toHaveCount(1);
  await expect(mine).toContainText(month.label);
  await expect(mine).toContainText("Paid on");
  await expect(mine).toContainText("₹26,000");
  await mine.click();
  const slip = teacherPage.getByTestId("payslip");
  await expect(slip.getByTestId("payslip-name")).toHaveText(teacher.name);
  await expect(slip.getByTestId("payslip-net")).toContainText("₹26,000");
  await expect(slip.getByTestId("payslip-bank")).toHaveText("XXXX 9012 · SBIN0001234");

  // 8. She resigns: the admin records today as her last working day.
  await page.goto(`/app/staff/${teacherId}`);
  await page.getByRole("button", { name: "Record leaving" }).click();
  const leaving = page.getByRole("dialog", { name: `${teacher.name} is leaving` });
  await expect(leaving.getByLabel("Last working day")).toHaveValue(iso(today));
  await leaving.getByLabel("Reason").fill("Resigned to move to Pune");
  await leaving.getByRole("button", { name: "Record leaving" }).click();
  await expect(leaving).toBeHidden();
  await expect(page.getByText(`${teacher.name} is recorded as left.`)).toBeVisible();
  await expect(page.getByTestId("staff-name")).toContainText("Left");
  await expect(page.getByTestId("staff-left")).toContainText("Resigned to move to Pune");

  // 9. She can no longer sign in.
  await teacherPage.context().clearCookies();
  await loginViaUi(teacherPage, teacher);
  await expect(teacherPage.getByText("Those details don't match. Check the school code, email and password.")).toBeVisible();
  await expect(teacherPage).toHaveURL(/\/login/);
  const refused = await request.post("/api/auth/login", { data: { schoolCode: teacher.schoolCode, email: teacher.email, password: teacher.password } });
  expect(refused.status()).toBe(401);

  // 10. Class 5 A no longer lists her as its class teacher.
  await mainNav(page).getByRole("link", { name: "School setup" }).click();
  await page.getByRole("tab", { name: "Classes & sections" }).click();
  await expect(class5).toContainText("No class teacher");
  await expect(class5).not.toContainText(teacher.name);
  const section = (await (await request.get(`/api/academics/classes`, asAdmin)).json()) as {
    sections: { id: string; classTeacher: { id: string } | null }[];
  }[];
  expect(section.flatMap((c) => c.sections).find((s) => s.id === sectionId)?.classTeacher ?? null).toBeNull();

  // 11. The admin prepares an experience certificate; the principal signs it; it verifies by its QR code.
  await mainNav(page).getByRole("link", { name: "Certificates and ID cards", exact: true }).click();
  await page.getByRole("button", { name: "Prepare a certificate" }).click();
  const prepare = page.getByRole("dialog", { name: "Prepare a certificate" });
  await prepare.getByLabel("Type", { exact: true }).selectOption("EXPERIENCE");
  await prepare.getByRole("searchbox", { name: "Find a staff member" }).fill("Neha");
  await prepare.getByRole("button", { name: new RegExp(teacher.name) }).click();
  await expect(prepare.getByTestId("picked-subject")).toContainText(teacher.name);
  await prepare.getByLabel("Purpose", { exact: true }).fill("new employment");
  await prepare.getByRole("button", { name: "Prepare", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/certificates\/[0-9a-f-]+$/);
  const certificateId = page.url().split("/").pop()!;
  const document = page.getByTestId("certificate-document");
  await expect(document).toHaveAttribute("data-status", "PREPARED");
  await expect(document).toContainText(teacher.name);
  await expect(document).toContainText("TGT English");
  await expect(page.getByTestId("books-warning")).toHaveCount(0);

  await principalPage.goto(`/app/certificates/${certificateId}`);
  await principalPage.getByTestId("sign-certificate").click();
  const sign = principalPage.getByRole("dialog", { name: "Sign this certificate?" });
  await expect(sign).toContainText(teacher.name);
  await sign.getByRole("button", { name: "Sign and issue" }).click();
  await expect(principalPage.getByText("Certificate signed and issued.")).toBeVisible();
  const serial = `EXP/${year.name}/0001`;
  await expect(principalPage.getByTestId("certificate-serial")).toHaveText(serial);
  await expect(principalPage.getByTestId("certificate-document")).toHaveAttribute("data-status", "ISSUED");
  await expect(principalPage.getByTestId("qr-code")).toBeVisible();
  const verifyText = await principalPage.getByTestId("certificate-verify-url").textContent();
  const verifyUrl = verifyText!.replace(/^Verify at /, "").trim();

  const anyone = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const verifyPage = await anyone.newPage();
  await verifyPage.goto(verifyUrl);
  await expect(verifyPage.getByTestId("verify-result")).toHaveAttribute("data-valid", "true");
  await expect(verifyPage.getByTestId("verify-status")).toHaveText("Genuine certificate");
  await expect(verifyPage.getByTestId("verify-serial")).toHaveText(serial);
  await expect(verifyPage.getByTestId("verify-result")).toContainText("Neha");
  await expect(verifyPage.getByTestId("verify-result")).not.toContainText("Gupta");
  await anyone.close();

  await teacherPage.context().close();
  await principalPage.context().close();
});
