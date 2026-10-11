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
  signOut,
  signupViaApi,
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

/** This month in India as "YYYY-MM", with its English name ("October 2026") and short name ("Oct 2026"). */
function monthInIndia() {
  const india = new Date(Date.now() + 330 * 60_000);
  const year = india.getUTCFullYear();
  const index = india.getUTCMonth();
  const name = MONTHS[index];
  return {
    value: `${year}-${String(index + 1).padStart(2, "0")}`,
    label: `${name} ${year}`,
    short: `${name.slice(0, 3)} ${year}`,
  };
}

type Auth = { headers: { Authorization: string } };

async function send<T>(request: APIRequestContext, method: "post" | "put", path: string, auth: Auth, data?: unknown) {
  const res = await request[method](path, { ...auth, data });
  expect(res.status(), `${path}: ${await res.text()}`).toBeLessThan(300);
  return (await res.json()) as T;
}

test("the accountant works out the month, the School Admin approves it and downloads the bank file, and a teacher opens their payslip", async ({
  page,
  request,
}) => {
  test.setTimeout(150_000);
  const school = newSchool("payroll");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const asAdmin = { headers: { Authorization: `Bearer ${adminToken}` } };
  const month = monthInIndia();

  // Through the API: an accountant and a teacher, the school's payroll settings (Telangana professional tax),
  // the standard components, the teacher's bank account and PAN, and both salaries from this month.
  const accountant = { name: "Farah Khan", email: `farah@${school.schoolCode}.example.com`, password: PASSWORD };
  const teacher = { name: "Kiran Rao", email: `kiran@${school.schoolCode}.example.com`, password: PASSWORD };
  const staff = { departmentId: null, employmentType: "PERMANENT", dateOfJoining: "2020-06-01" };
  const accountantId = (
    await send<{ userId: string }>(request, "post", "/api/staff", asAdmin, {
      ...accountant,
      ...staff,
      roles: ["ACCOUNTANT"],
      employeeCode: "E2E-301",
      designation: "Accountant",
      mobile: "98480 22301",
    })
  ).userId;
  const teacherId = (
    await send<{ userId: string }>(request, "post", "/api/staff", asAdmin, {
      ...teacher,
      ...staff,
      roles: ["TEACHER"],
      employeeCode: "E2E-302",
      designation: "TGT Mathematics",
      mobile: "98480 22302",
    })
  ).userId;
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
  const components = await send<{ id: string; code: string }[]>(
    request,
    "post",
    "/api/payroll/components/standard",
    asAdmin,
  );
  const componentId = (code: string) => {
    const found = components.find((c) => c.code === code);
    expect(found, code).toBeTruthy();
    return found!.id;
  };
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
  // Teacher: ₹28,000 gross; PF 12% of ₹15,000 = ₹1,800, professional tax ₹200, no TDS, so ₹26,000 net.
  await send(request, "post", `/api/payroll/staff/${teacherId}/structures`, asAdmin, {
    effectiveFrom: month.value,
    note: "Salary on the new scale",
    lines: [
      { componentId: componentId("BASIC"), monthlyPaise: 20_000_00 },
      { componentId: componentId("HRA"), monthlyPaise: 8_000_00 },
    ],
  });
  // Accountant: ₹24,000 gross, ₹22,000 net, and no bank account yet.
  await send(request, "post", `/api/payroll/staff/${accountantId}/structures`, asAdmin, {
    effectiveFrom: month.value,
    note: null,
    lines: [
      { componentId: componentId("BASIC"), monthlyPaise: 18_000_00 },
      { componentId: componentId("HRA"), monthlyPaise: 6_000_00 },
    ],
  });

  // The accountant works out this month's payroll from the Payroll screen; she cannot approve it.
  await loginViaUi(page, { schoolCode: school.schoolCode, email: accountant.email, password: accountant.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Payroll", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/payroll$/);
  await expect(page.getByRole("heading", { level: 1, name: "Monthly payroll" })).toBeVisible();
  await page.getByRole("button", { name: "Work out a month" }).click();
  const create = page.getByRole("dialog", { name: "Work out a month's payroll" });
  await expect(create.getByLabel("Month")).toHaveValue(month.value);
  await create.getByRole("button", { name: "Work out payroll" }).click();
  await expect(page).toHaveURL(/\/app\/payroll\/runs\/[0-9a-f-]+$/);
  await expect(page.getByRole("heading", { level: 1, name: `Payroll for ${month.label}` })).toBeVisible();
  await expect(page.getByTestId("run-status")).toHaveText("Draft");
  await expect(page.getByTestId("run-net")).toContainText("₹48,000");
  const rows = page.getByTestId("run-payslip-row");
  await expect(rows).toHaveCount(2);
  await expect(rows.filter({ hasText: teacher.name })).toContainText("₹26,000");
  await expect(rows.filter({ hasText: accountant.name })).toContainText("₹22,000");
  await expect(page.getByTestId("warning-NO_BANK")).toContainText(accountant.name);
  await expect(page.getByTestId("approve-run")).toHaveCount(0);
  await signOut(page);

  // The School Admin opens the month, approves and locks it, and downloads the bank transfer file.
  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Payroll", exact: true }).click();
  await page.getByTestId("runs-table").getByRole("link", { name: month.label }).click();
  await expect(page.getByRole("heading", { level: 1, name: `Payroll for ${month.label}` })).toBeVisible();
  await page.getByTestId("approve-run").click();
  const approve = page.getByRole("dialog", { name: `Approve payroll for ${month.label}?` });
  await expect(approve).toContainText("2 staff with a net pay of ₹48,000");
  await approve.getByRole("button", { name: "Approve and lock" }).click();
  await expect(page.getByTestId("run-status")).toHaveText("Approved");
  await expect(page.getByTestId("run-locked")).toBeVisible();
  await expect(page.getByTestId("approve-run")).toHaveCount(0);

  const download = page.waitForEvent("download");
  await page.getByTestId("download-bank").click();
  const file = await download;
  const name = `bank-transfer-${month.value}.csv`;
  expect(file.suggestedFilename()).toBe(name);
  const csv = readFileSync(await file.path(), "utf8");
  // Only the teacher has an account, so only she is in the file, with the full account number.
  expect(csv.split("\r\n").filter(Boolean)).toEqual([
    "Beneficiary name,Account number,IFSC,Amount,Narration",
    `${teacher.name},000000129012,SBIN0001234,26000.00,Salary ${month.short}`,
  ]);
  await expect(page.getByRole("status").filter({ hasText: "Downloaded" })).toContainText(name);
  await signOut(page);

  // The teacher opens their own payslip on a phone: bank by its last four digits, PAN masked, the net pay.
  await page.setViewportSize({ width: 390, height: 844 });
  await loginViaUi(page, { schoolCode: school.schoolCode, email: teacher.email, password: teacher.password });
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/my-payslips");
  await expect(page.getByRole("heading", { level: 1, name: "My payslips" })).toBeVisible();
  const mine = page.getByTestId("my-payslip-link");
  await expect(mine).toHaveCount(1);
  await expect(mine).toContainText(month.label);
  await expect(mine).toContainText("Approved, payment pending");
  await expect(mine).toContainText("₹26,000");
  await expectNoHorizontalScroll(page);
  await mine.click();
  await expect(page).toHaveURL(/\/app\/my-payslips\/[0-9a-f-]+$/);
  await expect(page.getByRole("heading", { level: 1, name: `Payslip for ${month.label}` })).toBeVisible();
  const slip = page.getByTestId("payslip");
  await expect(slip.getByTestId("payslip-name")).toHaveText(teacher.name);
  await expect(slip.getByTestId("payslip-month")).toHaveText(month.label);
  await expect(slip.getByTestId("payslip-bank")).toHaveText("XXXX 9012 · SBIN0001234");
  await expect(slip.getByTestId("payslip-pan")).toHaveText("XXXXXX234F");
  await expect(slip.getByTestId("payslip-net")).toContainText("₹26,000");
  await expect(slip.getByTestId("payslip-words")).toContainText("Rupees Twenty Six Thousand Only");
  await expect(slip).not.toContainText("000000129012");
  await expect(page.getByRole("button", { name: "Print" })).toBeVisible();
  await expectNoHorizontalScroll(page);

  // The teacher has no payroll access beyond their own payslips.
  const teacherToken = await loginViaApi(request, {
    schoolCode: school.schoolCode,
    email: teacher.email,
    password: teacher.password,
  });
  const runs = await request.get("/api/payroll/runs", { headers: { Authorization: `Bearer ${teacherToken}` } });
  expect(runs.status()).toBe(403);
});
