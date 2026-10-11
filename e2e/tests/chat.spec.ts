import { readFileSync } from "node:fs";
import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";
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

type Auth = { headers: { Authorization: string } };

/** JSON calls through the frontend's /api proxy, expecting `status`. */
function client(request: APIRequestContext, auth: Auth) {
  return async <T = { id: string }>(path: string, data?: unknown, status = 201): Promise<T> => {
    const res = await request.post(path, { ...auth, data });
    expect(res.status(), `POST ${path}: ${await res.text()}`).toBe(status);
    return (await res.json()) as T;
  };
}

type StudentDetail = { id: string; guardians: { id: string }[] };

/**
 * Through the API: Ravi Kumar is class teacher of Class 5 A, where Diya Mehta and Bala Iyer study. Diya's mother
 * Meena Mehta has a sign-in; Lakshmi Iyer is the Principal.
 */
async function setUpSchool(request: APIRequestContext, schoolCode: string, auth: Auth) {
  const post = client(request, auth);
  const person = (local: string, password = PASSWORD): Credentials => ({
    schoolCode,
    email: `${local}@${schoolCode}.example.com`,
    password,
  });
  const teacher = person("ravi");
  const principal = person("lakshmi");
  const parent = person("meena", "Parent-temp-2026");
  const { id: raviId } = await post("/api/users", { name: "Ravi Kumar", email: teacher.email, password: PASSWORD, roles: ["TEACHER"] });
  await post("/api/users", { name: "Lakshmi Iyer", email: principal.email, password: PASSWORD, roles: ["PRINCIPAL"] });

  const year = academicYear();
  await post("/api/academics/years", { ...year, current: true });
  const { id: class5 } = await post("/api/academics/classes", { name: "Class 5" });
  const { id: section5A } = await post(`/api/academics/classes/${class5}/sections`, { name: "A", capacity: 40, classTeacherId: raviId });

  const admit = (admissionNo: string, firstName: string, lastName: string, rollNo: number, guardian: string, phone: string) =>
    post<StudentDetail>("/api/students", {
      admissionNo,
      firstName,
      lastName,
      dateOfBirth: "2016-05-14",
      gender: "FEMALE",
      admissionDate: year.startsOn,
      sectionId: section5A,
      rollNo,
      guardians: [{ name: guardian, relation: "MOTHER", phone, primary: true }],
    });
  const diya = await admit("E2E/CH/1", "Diya", "Mehta", 1, "Meena Mehta", "98765 30001");
  const bala = await admit("E2E/CH/2", "Bala", "Iyer", 2, "Uma Iyer", "98765 30002");
  await post(`/api/students/${diya.id}/guardians/${diya.guardians[0].id}/sign-in`, { mode: "CREATE", email: parent.email, password: parent.password }, 200);

  return { teacher, principal, parent, diyaId: diya.id, balaId: bala.id, year };
}

async function newSchoolWithClass(request: APIRequestContext, tag: string) {
  const school = newSchool(tag);
  await signupViaApi(request, school);
  const adminToken = await loginViaApi(request, adminCredentials(school));
  return { school, ...(await setUpSchool(request, school.schoolCode, { headers: { Authorization: `Bearer ${adminToken}` } })) };
}

/** A phone-sized window of its own, so the parent and the school can be signed in side by side. */
async function phonePage(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true });
  return context.newPage();
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

/** The chat polls every 15 seconds; allow one full round and the request. */
const POLL = { timeout: 25_000 };

test("a parent writes to the class teacher from her phone, the teacher replies on a computer and the parent sees it", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180_000);
  const { teacher, parent } = await newSchoolWithClass(request, "chat");

  // 1. On her phone the mother finds Messages in the family menu and writes to Diya's class teacher.
  const phone = await phonePage(browser);
  await loginViaUi(phone, parent);
  await expect(phone).toHaveURL(/\/app\/dashboard$/);
  const card = phone.getByTestId("family-messages-card");
  await expect(card).toContainText("No new messages.");
  await expect(phone.getByTestId("bottom-bar").getByRole("link")).toHaveCount(4);
  await openFromMenu(phone, "Messages");
  await expect(phone).toHaveURL(/\/app\/family\/chat$/);
  await expect(phone.getByTestId("chat-empty")).toBeVisible();
  await phone.getByRole("button", { name: "New message" }).click();
  const contacts = phone.getByRole("dialog", { name: "New message" });
  await expect(contacts.getByTestId("chat-contact")).toHaveCount(1);
  await expect(contacts.getByTestId("chat-contact")).toContainText("Class teacher · Diya Mehta");
  await contacts.getByRole("button", { name: "Message Ravi Kumar about Diya Mehta" }).click();
  await expect(phone.getByTestId("chat-with")).toHaveText("Ravi Kumar");
  await expect(phone.getByText("No messages yet. Say hello.")).toBeVisible();
  await phone.getByRole("textbox", { name: "Write a message" }).fill("Good morning. Diya finds fractions hard. How can we help at home?");
  await phone.getByRole("button", { name: "Send" }).click();
  const question = phone.getByTestId("chat-message").filter({ hasText: "Diya finds fractions hard" });
  await expect(question).toHaveAttribute("data-mine", "true");
  await expect(question).toContainText("Sent");
  await expectNoHorizontalScroll(phone);

  // 2. Ravi Kumar, on a computer: the dashboard counts the unread message; he opens it from Chat and replies.
  await loginViaUi(page, teacher);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("chat-unread-count")).toHaveText("1");
  await expect(page.getByTestId("chat-unread-card")).toContainText("in 1 conversation");
  await mainNav(page).getByRole("link", { name: "Chat", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/chat$/);
  const row = page.getByTestId("chat-conversation").filter({ hasText: "Meena Mehta" });
  await expect(row).toContainText("Diya Mehta · Class 5 A");
  await expect(row).toContainText("Diya finds fractions hard");
  await row.click();
  await expect(page.getByTestId("chat-with")).toHaveText("Meena Mehta");
  await expect(page.getByTestId("chat-message").filter({ hasText: "Diya finds fractions hard" })).toHaveAttribute("data-mine", "false");
  await page.getByRole("textbox", { name: "Write a message" }).fill("Thank you for writing. Ten minutes with the worksheet each evening will help.");
  await page.getByRole("button", { name: "Send" }).click();
  const answer = page.getByTestId("chat-message").filter({ hasText: "Ten minutes with the worksheet" });
  await expect(answer).toHaveAttribute("data-mine", "true");

  // 3. The open chat on the phone picks the reply up by itself, and shows that her question was seen.
  const reply = phone.getByTestId("chat-message").filter({ hasText: "Ten minutes with the worksheet" });
  await expect(reply).toBeVisible(POLL);
  await expect(reply).toHaveAttribute("data-mine", "false");
  await expect(question.getByTestId("chat-seen")).toBeVisible();
  await expectNoHorizontalScroll(phone);
  // ...and the teacher's screen in turn shows that she read the reply.
  await expect(answer.getByTestId("chat-seen")).toBeVisible(POLL);

  // 4. While she is on her home screen, a second message shows as an unread badge there.
  await phone.goto("/app/dashboard");
  await expect(card).toContainText("No new messages.");
  await page.getByRole("textbox", { name: "Write a message" }).fill("Please also sign her diary today.");
  await page.getByRole("button", { name: "Send" }).click();
  await expect(page.getByTestId("chat-message").filter({ hasText: "sign her diary" })).toBeVisible();
  await expect(card.getByTestId("family-unread-badge")).toHaveText("1", POLL);
  await expect(card).toContainText("1 new message from a teacher");
  await expectNoHorizontalScroll(phone);
  await card.getByRole("link", { name: "Open messages" }).click();
  await expect(phone).toHaveURL(/\/app\/family\/chat$/);
  await phone.getByTestId("chat-conversation").filter({ hasText: "Ravi Kumar" }).click();
  await expect(phone.getByTestId("chat-message").filter({ hasText: "sign her diary" })).toBeVisible();
  await phone.getByRole("button", { name: "Back to conversations" }).click();
  await expect(phone.getByTestId("chat-conversation")).toHaveCount(1);
  await phone.context().close();

  // Phone numbers and email addresses stay out of the chat; each side only reaches its own endpoints.
  const parentAuth = { headers: { Authorization: `Bearer ${await loginViaApi(request, parent)}` } };
  const teacherAuth = { headers: { Authorization: `Bearer ${await loginViaApi(request, teacher)}` } };
  const theirs = await request.get("/api/me/chat/conversations", parentAuth);
  expect(theirs.status()).toBe(200);
  const listed = await theirs.text();
  expect(listed).toContain("Ravi Kumar");
  expect(listed).not.toContain(teacher.email);
  const staffSide = await (await request.get("/api/chat/conversations", teacherAuth)).text();
  expect(staffSide).toContain("Meena Mehta");
  expect(staffSide).not.toContain(parent.email);
  expect(staffSide).not.toContain("30001");
  expect((await request.get("/api/chat/conversations", parentAuth)).status()).toBe(403);
  expect((await request.get("/api/chat/oversight/conversations", teacherAuth)).status()).toBe(403);
  expect((await request.get("/api/ptm/events", teacherAuth)).status()).toBe(403);
});

test("the principal sets up a PTM, a parent books a slot on her phone and the class teacher sees it in the schedule", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(180_000);
  const { teacher, principal, parent, diyaId, balaId, year } = await newSchoolWithClass(request, "ptm");
  const date = indiaDate(7) <= year.endsOn ? indiaDate(7) : year.endsOn;

  // 1. The Principal sets up a morning meeting for Class 5 A with a short break and blocks the first slot.
  await loginViaUi(page, principal);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "PTM", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/ptm$/);
  await expect(page.getByTestId("ptm-events")).toContainText("No meetings yet.");
  await page.getByRole("button", { name: "New meeting" }).click();
  const form = page.getByRole("dialog", { name: "New parent-teacher meeting" });
  await form.getByLabel("Title", { exact: true }).fill("Half-yearly parent-teacher meeting");
  await form.getByLabel("Date", { exact: true }).fill(date);
  await form.getByLabel("Starts at", { exact: true }).fill("09:00");
  await form.getByLabel("Ends at", { exact: true }).fill("10:00");
  await form.getByLabel("Slot length").selectOption("10");
  await form.getByRole("button", { name: "Add a break" }).click();
  await form.getByLabel("Break from", { exact: true }).fill("09:30");
  await form.getByLabel("Break to", { exact: true }).fill("09:40");
  await form.getByLabel("Place", { exact: true }).fill("Class 5 A classroom, first floor");
  await form.getByRole("checkbox", { name: "Class 5 A" }).check();
  await form.getByRole("button", { name: "Set up meeting" }).click();
  await expect(page).toHaveURL(/\/app\/ptm\/[0-9a-f-]{36}$/);
  const eventId = page.url().split("/").pop()!;
  await expect(page.getByTestId("ptm-info")).toContainText("09:00–10:00");
  await expect(page.getByTestId("ptm-info")).toContainText("10 minutes · breaks 09:30–09:40");
  await expect(page.getByTestId("ptm-sections")).toContainText("Class teacher Ravi Kumar");
  await expect(page.getByTestId("ptm-sections")).toContainText("0 of 2 booked");
  const managed = page.getByTestId("ptm-schedule");
  await expect(managed).toContainText("Ravi Kumar");
  await expect(managed).toContainText("Class teacher · 09:00–10:00");
  const slotRows = managed.getByTestId("ptm-slot-row");
  await expect(slotRows).toHaveCount(5);
  await slotRows.filter({ hasText: "09:00–09:10" }).getByRole("button", { name: "Block" }).click();
  await expect(slotRows.filter({ hasText: "09:00–09:10" })).toContainText("Blocked");
  await expect(managed).toContainText("0 booked · 4 free");

  // 2. On her phone the mother sees the meeting under PTM and books 09:10 with the class teacher.
  const phone = await phonePage(browser);
  await loginViaUi(phone, parent);
  await expect(phone).toHaveURL(/\/app\/dashboard$/);
  await openFromMenu(phone, "PTM");
  await expect(phone).toHaveURL(/\/app\/family\/ptm$/);
  const meeting = phone.getByTestId("ptm-family-event");
  await expect(meeting).toContainText("Half-yearly parent-teacher meeting");
  await expect(meeting).toContainText("At school: Class 5 A classroom, first floor");
  const withRavi = meeting.getByTestId("ptm-family-teacher");
  await expect(withRavi).toContainText("Ravi Kumar");
  await expect(withRavi).toContainText("Not booked");
  // The blocked 09:00 slot is not offered at all.
  await expect(withRavi.getByTestId("ptm-slots").getByRole("button")).toHaveCount(4);
  await expect(withRavi.getByRole("button", { name: /09:00/ })).toHaveCount(0);
  await expect(withRavi).toContainText("4 free slots.");
  await expectNoHorizontalScroll(phone);
  await withRavi.getByRole("button", { name: "Book 09:10" }).click();
  const confirm = phone.getByRole("dialog", { name: "Book 09:10 with Ravi Kumar?" });
  await expect(confirm).toContainText("For Diya Mehta on");
  await confirm.getByRole("button", { name: "Book" }).click();
  await expect(phone.getByText("Booked for 09:10.")).toBeVisible();
  await expect(withRavi).toContainText("Booked 09:10–09:20");
  await expect(withRavi.getByRole("button", { name: "Change time" })).toBeVisible();
  await expectNoHorizontalScroll(phone);
  await phone.context().close();

  // A second meeting with the same teacher, or a booking for another family's child, is refused.
  const parentAuth = { headers: { Authorization: `Bearer ${await loginViaApi(request, parent)}` } };
  const family = (await (await request.get("/api/me/ptm", parentAuth)).json()) as {
    children: { events: { teachers: { slots: { id: string; startsAt: string; available: boolean }[] }[] }[] }[];
  };
  const free = family.children[0].events[0].teachers[0].slots.find((s) => s.startsAt === "09:20:00")!;
  expect(free.available).toBe(true);
  const again = await request.post("/api/me/ptm/bookings", { ...parentAuth, data: { slotId: free.id, studentId: diyaId } });
  expect(again.status()).toBe(409);
  const notHers = await request.post("/api/me/ptm/bookings", { ...parentAuth, data: { slotId: free.id, studentId: balaId } });
  expect(notHers.status()).toBe(400);
  expect((await request.get("/api/ptm/events", parentAuth)).status()).toBe(403);

  // 3. Ravi Kumar sees the booking on his dashboard and in his schedule, and can print it.
  await signOut(page);
  await loginViaUi(page, teacher);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await expect(page.getByTestId("ptm-next-card")).toContainText("1 of 4 slots booked");
  await mainNav(page).getByRole("link", { name: "PTM", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/ptm$/);
  const mine = page.getByTestId("ptm-mine");
  await expect(mine).toContainText("Your hours 09:00–10:00 · 1 of 4 slots booked");
  await expect(page.getByTestId("ptm-events")).toHaveCount(0);
  await mine.getByRole("link", { name: /Half-yearly parent-teacher meeting/ }).click();
  await expect(page).toHaveURL(new RegExp(`/app/ptm/${eventId}$`));
  const schedule = page.getByTestId("ptm-schedule");
  await expect(schedule).toContainText("1 booked · 3 free");
  const booked = schedule.getByTestId("ptm-slot-row").filter({ hasText: "09:10–09:20" });
  await expect(booked).toContainText("Diya Mehta");
  await expect(booked).toContainText("Class 5 A");
  await expect(booked).toContainText("Meena Mehta");
  await expect(booked).toContainText("Not marked");
  await expect(schedule.getByRole("button", { name: "Book for a family" })).toHaveCount(0);
  await page.getByRole("link", { name: "Print schedules" }).click();
  await expect(page).toHaveURL(new RegExp(`/app/ptm/${eventId}/print$`));
  const printed = page.getByTestId("ptm-print-teacher");
  await expect(printed).toHaveCount(1);
  await expect(printed).toContainText("Ravi Kumar");
  await expect(printed).toContainText("Diya Mehta");
  await signOut(page);

  // 4. The Principal's PTM attendance report counts the booking, and the CSV opens in Excel (UTF-8 with a BOM).
  await loginViaUi(page, principal);
  await expect(page).toHaveURL(/\/app\/dashboard$/);
  await mainNav(page).getByRole("link", { name: "Reports", exact: true }).click();
  await page.getByTestId("report-link-ptmAttendance").click();
  await expect(page).toHaveURL(/\/app\/reports\/ptm$/);
  const report = page.getByTestId("report-ptm");
  await expect(report.getByTestId("ptm-report-booked")).toContainText("1");
  await expect(report.getByTestId("ptm-report-classes")).toContainText("Class 5 A");
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "CSV", exact: true }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe("ptm-attendance.csv");
  const csv = readFileSync(await file.path(), "utf8");
  expect(csv.charCodeAt(0)).toBe(0xfeff);
  const lines = csv.slice(1).split("\r\n").filter(Boolean);
  expect(lines[0]).toBe("Meeting,Date,Type,Name,Students or slots,Booked,Attended,No-show,Not marked");
  expect(lines).toContain(`Half-yearly parent-teacher meeting,${date},Class,Class 5 A,2,1,0,0,1`);
  expect(lines).toContain(`Half-yearly parent-teacher meeting,${date},Teacher,Ravi Kumar,4,1,0,0,1`);
});
