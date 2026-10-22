import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import {
  adminCredentials,
  expectNoHorizontalScroll,
  loginViaApi,
  loginViaUi,
  mainNav,
  newSchool,
  PASSWORD,
  signupViaApi,
  type Credentials,
} from "./support/helpers";

/** Today in India as "YYYY-MM-DD". */
function indiaDate(): string {
  return new Date(Date.now() + 330 * 60_000).toISOString().slice(0, 10);
}

/** The Indian academic year (April to March) that contains today. */
function academicYear() {
  const today = indiaDate();
  const year = Number(today.slice(5, 7)) >= 4 ? Number(today.slice(0, 4)) : Number(today.slice(0, 4)) - 1;
  return { startsOn: `${year}-04-01`, endsOn: `${year + 1}-03-31`, name: `${year}-${String((year + 1) % 100).padStart(2, "0")}` };
}

type Auth = { headers: { Authorization: string } };

function client(request: APIRequestContext, auth: Auth) {
  return async <T = { id: string }>(path: string, data?: unknown, status = 201): Promise<T> => {
    const res = await request.post(path, { ...auth, data });
    expect(res.status(), `POST ${path}: ${await res.text()}`).toBe(status);
    return (await res.json()) as T;
  };
}

/**
 * Through the API: a current year, Class 5 A with Arjun Sharma in it, his mother's sign-in, the front office and
 * the principal.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const post = client(request, auth);
  const office: Credentials = { schoolCode, email: `olive@${schoolCode}.example.com`, password: PASSWORD };
  const principal: Credentials = { schoolCode, email: `prem@${schoolCode}.example.com`, password: PASSWORD };
  await post("/api/users", { name: "Olive D'Souza", email: office.email, password: PASSWORD, roles: ["FRONT_OFFICE"] });
  await post("/api/users", { name: "Prem Kumar", email: principal.email, password: PASSWORD, roles: ["PRINCIPAL"] });

  const year = academicYear();
  await post("/api/academics/years", { ...year, current: true });
  const { id: class5 } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: section5A } = await post(`/api/academics/classes/${class5}/sections`, { name: "A", capacity: 40 });
  const arjun = await post<{ id: string; guardians: { id: string }[] }>("/api/students", {
    admissionNo: "E2E/CERT/1",
    firstName: "Arjun",
    lastName: "Sharma",
    dateOfBirth: "2016-03-12",
    gender: "MALE",
    admissionDate: year.startsOn,
    sectionId: section5A,
    rollNo: 1,
    guardians: [{ name: "Neha Sharma", relation: "MOTHER", phone: "98765 30001", primary: true }],
  });
  const parent: Credentials = { schoolCode, email: `neha@${schoolCode}.example.com`, password: "Parent-temp-2026" };
  await post(`/api/students/${arjun.id}/guardians/${arjun.guardians[0].id}/sign-in`, { mode: "CREATE", email: parent.email, password: parent.password }, 200);
  return { office, principal, parent, year };
}

/** Counts window.print calls instead of opening the print dialog. */
async function countPrints(page: Page) {
  await page.addInitScript(() => {
    (window as unknown as { printed: number }).printed = 0;
    window.print = () => {
      (window as unknown as { printed: number }).printed += 1;
    };
  });
}

const printed = (page: Page) => page.evaluate(() => (window as unknown as { printed: number }).printed);

/** Opens a menu item from the phone's bottom bar, through "More" when it is not one of the first four. */
async function openFromMenu(page: Page, label: RegExp) {
  const bar = page.getByTestId("bottom-bar");
  await expect(bar).toBeVisible();
  const direct = bar.getByRole("link", { name: label });
  if ((await direct.count()) > 0) {
    await direct.click();
    return;
  }
  await bar.getByRole("button", { name: "More" }).click();
  await page.getByRole("dialog", { name: "All sections" }).getByRole("link", { name: label }).click();
}

test("the office prepares a bonafide for Arjun, the principal signs it, the parent prints it on a phone, anyone verifies it by its QR code, and the office prints Class 5 A's ID cards", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180_000);
  const school = newSchool("cert");
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  const { office, principal, parent, year } = await setUpSchool(request, school.schoolCode, { headers: { Authorization: `Bearer ${adminToken}` } });

  // 1. The front office prepares a bonafide certificate for Arjun.
  await countPrints(page);
  await loginViaUi(page, office);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Certificates and ID cards", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/certificates$/);
  await expect(page.getByTestId("certificates-empty")).toBeVisible();
  await page.getByRole("button", { name: "Prepare a certificate" }).click();
  const prepare = page.getByRole("dialog", { name: "Prepare a certificate" });
  await expect(prepare.getByLabel("Type", { exact: true })).toHaveValue("BONAFIDE");
  await prepare.getByRole("searchbox", { name: "Find a student" }).fill("Arjun");
  await prepare.getByRole("button", { name: /Arjun Sharma/ }).click();
  await expect(prepare.getByTestId("picked-subject")).toContainText("Arjun Sharma");
  await prepare.getByLabel("Purpose", { exact: true }).fill("passport application");
  await prepare.getByRole("button", { name: "Prepare", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/certificates\/[0-9a-f-]+$/);
  const certificateId = page.url().split("/").pop()!;
  const editor = page.getByTestId("certificate-editor");
  await expect(editor.getByLabel("Title", { exact: true })).toHaveValue("Bonafide Certificate");
  await expect(editor.getByLabel("Purpose", { exact: true })).toHaveValue("passport application");
  const document = page.getByTestId("certificate-document");
  await expect(document).toHaveAttribute("data-status", "PREPARED");
  await expect(document).toContainText("Arjun Sharma");
  await expect(document).toContainText("Class 5 A");
  await expect(page.getByTestId("certificate-serial")).toHaveText("Not yet issued");
  // The office cannot sign.
  await expect(page.getByTestId("sign-certificate")).toHaveCount(0);
  const officeAuth = { headers: { Authorization: `Bearer ${await loginViaApi(request, office)}` } };
  expect((await request.post(`/api/certificates/${certificateId}/sign`, officeAuth)).status()).toBe(403);
  await expectNoHorizontalScroll(page);

  // 2. The principal signs it: it gets the first serial number of the year and a QR code.
  const principalContext = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const principalPage = await principalContext.newPage();
  await loginViaUi(principalPage, principal);
  await expect(principalPage).toHaveURL(/\/app\/dashboard$/);
  await principalPage.goto(`/app/certificates/${certificateId}`);
  await principalPage.getByTestId("sign-certificate").click();
  const confirm = principalPage.getByRole("dialog", { name: "Sign this certificate?" });
  await expect(confirm).toContainText("Arjun Sharma");
  await confirm.getByRole("button", { name: "Sign and issue" }).click();
  await expect(principalPage.getByText("Certificate signed and issued.")).toBeVisible();
  const serial = `BC/${year.name}/0001`;
  await expect(principalPage.getByTestId("certificate-serial")).toHaveText(serial);
  await expect(principalPage.getByTestId("certificate-document")).toHaveAttribute("data-status", "ISSUED");
  await expect(principalPage.getByTestId("qr-code")).toBeVisible();
  const verifyText = await principalPage.getByTestId("certificate-verify-url").textContent();
  const verifyUrl = verifyText!.replace(/^Verify at /, "").trim();
  expect(verifyUrl).toMatch(/\/verify\/[A-Za-z0-9_-]+$/);
  // Issued: the wording is frozen for everyone.
  await expect(principalPage.getByTestId("certificate-editor").getByLabel("Title", { exact: true })).toBeDisabled();
  await principalContext.close();

  // 3. The mother, on her phone, finds the certificate in the family app and prints it.
  const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true });
  const parentPage = await phone.newPage();
  await countPrints(parentPage);
  await loginViaUi(parentPage, parent);
  await expect(parentPage).toHaveURL(/\/app\/dashboard$/);
  const homeCard = parentPage.getByTestId("family-certificates-card");
  await expect(homeCard).toContainText("1 certificate issued");
  await openFromMenu(parentPage, /^Certificates/);
  await expect(parentPage).toHaveURL(/\/app\/family\/certificates$/);
  const child = parentPage.getByTestId("family-certificates-child");
  await expect(child).toContainText("Arjun Sharma");
  const row = child.getByTestId("family-certificate");
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("Bonafide certificate");
  await expect(row).toContainText(serial);
  await expect(child.getByTestId("family-idcard-none")).toBeVisible();
  await expectNoHorizontalScroll(parentPage);
  await row.click();
  await expect(parentPage).toHaveURL(new RegExp(`/app/family/certificates/${certificateId}$`));
  await expect(parentPage.getByTestId("certificate-serial")).toHaveText(serial);
  await expect(parentPage.getByTestId("qr-code")).toBeVisible();
  await parentPage.getByTestId("print-certificate").click();
  expect(await printed(parentPage)).toBe(1);
  await expectNoHorizontalScroll(parentPage);
  // The parent cannot reach the register, and another family's certificate is not hers.
  const parentAuth = { headers: { Authorization: `Bearer ${await loginViaApi(request, parent)}` } };
  expect((await request.get("/api/certificates", parentAuth)).status()).toBe(403);
  await phone.close();

  // 4. Anyone who scans the QR code sees that the certificate is genuine, and only the first name.
  const anyone = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const verifyPage = await anyone.newPage();
  await verifyPage.goto(verifyUrl);
  const result = verifyPage.getByTestId("verify-result");
  await expect(result).toHaveAttribute("data-valid", "true");
  await expect(verifyPage.getByTestId("verify-status")).toHaveText("Genuine certificate");
  await expect(verifyPage.getByTestId("verify-serial")).toHaveText(serial);
  await expect(result).toContainText(school.schoolName);
  await expect(result).toContainText("Arjun");
  await expect(result).not.toContainText("Sharma");
  await expect(verifyPage.getByTestId("bottom-bar")).toHaveCount(0);
  await expectNoHorizontalScroll(verifyPage);
  await verifyPage.goto(verifyUrl.replace(/[A-Za-z0-9_-]+$/, "nosuchtoken0000"));
  await expect(verifyPage.getByTestId("verify-unavailable")).toContainText("No such certificate");
  await anyone.close();

  // 5. The office issues ID cards to Class 5 A and prints the sheet; the phone number is masked on screen.
  await page.goto("/app/certificates/id-cards");
  await expect(page.getByRole("heading", { name: "ID cards" })).toBeVisible();
  await page.getByLabel("Section", { exact: true }).selectOption({ label: "Class 5 A" });
  await expect(page.getByTestId("cards-empty")).toBeVisible();
  await page.getByTestId("issue-batch").click();
  await expect(page.getByText("1 card issued.")).toBeVisible();
  const cardRows = page.getByTestId("card-row");
  await expect(cardRows).toHaveCount(1);
  await expect(cardRows.first()).toContainText("Arjun Sharma");
  await expect(cardRows.first()).toContainText(`ID/${year.name}/0001`);
  await expect(cardRows.first().getByTestId("card-phone")).toHaveText("XXXXXX0001");
  await page.getByTestId("print-cards").click();
  const sheet = page.getByTestId("idcard-sheet");
  await expect(sheet).toBeVisible();
  await expect(sheet.getByTestId("idcard-pair")).toHaveCount(1);
  await expect(sheet.getByTestId("idcard-front")).toContainText("E2E/CERT/1");
  await expect(sheet.getByTestId("idcard-barcode")).toBeVisible();
  await expect(sheet.getByTestId("idcard-phone")).toHaveText("98765 30001");
  await expect.poll(() => printed(page)).toBe(1);
  // The register's report lists the issued certificate.
  await page.goto("/app/reports/certificates-issued");
  await expect(page.getByTestId("certificates-issued-total")).toContainText("1");
  await expect(page.getByTestId("certificates-issued-table")).toContainText(serial);
  await expectNoHorizontalScroll(page);
});
