import { readFile } from "node:fs/promises";
import { expect, test } from "@playwright/test";
import { adminCredentials, loginViaApi, loginViaUi, newSchool, signupViaApi } from "./support/helpers";
import { workbook } from "./support/xlsx";

const STUDENT_HEADINGS = [
  "Admission no",
  "Roll no",
  "First name",
  "Last name",
  "Date of birth",
  "Gender",
  "Guardian name",
  "Relation",
  "Guardian mobile",
  "Guardian email",
  "Admission date",
];
const STAFF_HEADINGS = [
  "Name",
  "Email",
  "Mobile",
  "Role",
  "Designation",
  "Department",
  "Employment type",
  "Joining date",
  "Employee code",
];

/** The Indian academic year (April to March) that contains today. */
function academicYearAround(now: Date) {
  const india = new Date(now.getTime() + 330 * 60_000);
  const year = india.getUTCMonth() >= 3 ? india.getUTCFullYear() : india.getUTCFullYear() - 1;
  return { name: `${year}-${String((year + 1) % 100).padStart(2, "0")}`, startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31` };
}

test("a new school brings its students and staff in from one Excel file", async ({ page, request }) => {
  const school = newSchool("imp");
  await signupViaApi(request, school);
  const token = await loginViaApi(request, adminCredentials(school));
  const year = academicYearAround(new Date());
  const created = await request.post("/api/academics/years", {
    headers: { Authorization: `Bearer ${token}` },
    data: { ...year, current: true },
  });
  expect(created.status(), await created.text()).toBe(201);

  await loginViaUi(page, adminCredentials(school));
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await page.goto("/app/setup");

  // The setup page offers the import step: the template downloads as a real Excel file.
  const card = page.getByTestId("school-import-card");
  await expect(card.getByRole("heading", { name: "Bring your existing students and staff" })).toBeVisible();
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    card.getByRole("button", { name: "Download template" }).click(),
  ]);
  expect(download.suggestedFilename()).toBe("school-import-template.xlsx");
  const template = await readFile(await download.path());
  expect(template.subarray(0, 2).toString("latin1")).toBe("PK");

  // A small file: two staff and three students over two sections the school has not set up yet.
  const file = workbook([
    {
      name: "Staff",
      rows: [
        STAFF_HEADINGS,
        ["Meera Iyer", `meera@${school.schoolCode}.example.com`, "9876511111", "Teacher", "PGT Maths", "Mathematics", "PERMANENT", "2019-06-03", "IMP-1"],
        ["Ravi Kumar", `ravi@${school.schoolCode}.example.com`, "9876511112", "Accountant", "Accountant", "", "CONTRACT", "03/06/2020", "IMP-2"],
      ],
    },
    {
      name: "Class 5 - A",
      rows: [
        STUDENT_HEADINGS,
        ["IMP/1", "1", "Arjun", "Sharma", "2015-05-14", "M", "Anitha Sharma", "MOTHER", "9876500001", "", ""],
        ["IMP/2", "2", "Diya", "Sharma", "14/09/2016", "F", "Anitha Sharma", "MOTHER", "9876500001", "", ""],
      ],
    },
    {
      name: "Class 5 - B",
      rows: [STUDENT_HEADINGS, ["IMP/3", "1", "Kabir", "Khan", "2015-01-01", "M", "Imran Khan", "FATHER", "9876500003", "", ""]],
    },
  ]);

  await card.getByRole("link", { name: "Upload file" }).click();
  await expect(page).toHaveURL(/\/app\/setup\/import$/);
  await page.getByLabel("Excel file (.xlsx)").setInputFiles({
    name: "our-school.xlsx",
    mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    buffer: file,
  });

  // Until the import may add sections, the check lists the missing ones and nothing can be imported.
  await page.getByRole("button", { name: "Check file" }).click();
  const report = page.getByTestId("school-import-report");
  await expect(report.getByRole("status")).toContainText("problems need fixing");
  await expect(report.getByTestId("school-import-sheets")).toContainText("Not set up");
  await expect(page.getByRole("button", { name: /^Import \d/ })).toHaveCount(0);

  await page.getByRole("checkbox", { name: /Add classes, sections and departments/ }).check();
  await page.getByRole("button", { name: "Check file" }).click();
  await expect(report.getByRole("status")).toContainText("Everything is ready to import: 3 students and 2 staff.");
  await expect(page.getByTestId("school-import-to-create")).toContainText("Class 5");
  await expect(page.getByTestId("school-import-to-create")).toContainText("Mathematics");

  await page.getByRole("button", { name: "Import 3 students and 2 staff" }).click();
  const done = page.getByTestId("school-import-done");
  await expect(done.getByRole("heading", { name: "Imported 3 students and 2 staff" })).toBeVisible();
  await expect(done).toContainText("created without a password");

  // The students and staff are there.
  await done.getByRole("link", { name: "View students" }).click();
  await expect(page).toHaveURL(/\/app\/students$/);
  for (const name of ["Arjun Sharma", "Diya Sharma", "Kabir Khan"]) {
    await expect(page.getByText(name).first()).toBeVisible();
  }
  await page.goto("/app/staff");
  await expect(page.getByText("Meera Iyer").first()).toBeVisible();
  await expect(page.getByText("Ravi Kumar").first()).toBeVisible();
  await page.getByRole("link", { name: "Meera Iyer" }).first().click();
  await expect(page.getByTestId("staff-name")).toContainText("Meera Iyer");
  await expect(page.getByTestId("staff-no-sign-in")).toBeVisible();

  // The API agrees, and the imported staff member cannot sign in until a password is set.
  const students = await request.get("/api/students?size=10", { headers: { Authorization: `Bearer ${token}` } });
  expect(((await students.json()) as { total: number }).total).toBe(3);
  const login = await request.post("/api/auth/login", {
    data: { schoolCode: school.schoolCode, email: `meera@${school.schoolCode}.example.com`, password: "anything-at-all" },
  });
  expect(login.status()).toBe(401);
});
